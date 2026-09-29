package com.mirror.bench;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;

/** Offline baseline, following the official Face Landmarker Android guide. */
public class BenchActivity extends Activity {
    private static final String TAG = "MirrorBench";
    private volatile boolean stopped;
    private TextView status;
    private String runId;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status = new TextView(this);
        status.setTextSize(22);
        status.setPadding(32, 48, 32, 32);
        status.setText("MediaPipe offline benchmark");
        setContentView(status);
        runId = getIntent().getStringExtra("run_id");
        if (runId == null || !runId.matches("[a-zA-Z0-9_-]+")) runId = "baseline";
        new Thread(this::runBenchmark, "FaceBenchmark").start();
    }

    private void runBenchmark() {
        JSONObject summary = new JSONObject();
        String delegateName = getIntent().getStringExtra("delegate");
        boolean gpu = "GPU".equals(delegateName);
        int duration = getIntent().getIntExtra("seconds", 20);
        int targetFps = getIntent().getIntExtra("analysis_fps", 0);
        ArrayList<Double> timings = new ArrayList<>();
        FaceLandmarker task = null;
        MPImage image = null;
        try {
            Bitmap source;
            try (var input = getAssets().open("portrait.jpg")) {
                source = BitmapFactory.decodeStream(input);
            }
            Bitmap bitmap = source.copy(Bitmap.Config.ARGB_8888, false);
            source.recycle();
            image = new BitmapImageBuilder(bitmap).build();
            long initStart = SystemClock.elapsedRealtimeNanos();
            BaseOptions base = BaseOptions.builder().setModelAssetPath("face_landmarker.task")
                    .setDelegate(gpu ? Delegate.GPU : Delegate.CPU).build();
            // https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker/android
            task = FaceLandmarker.createFromOptions(this, FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(base).setRunningMode(RunningMode.VIDEO).setNumFaces(1)
                    .setOutputFaceBlendshapes(true).setOutputFacialTransformationMatrixes(true).build());
            summary.put("initialization_ms", (SystemClock.elapsedRealtimeNanos() - initStart) / 1e6);
            long timestamp = SystemClock.uptimeMillis();
            for (int i = 0; i < 10 && !stopped; i++) task.detectForVideo(image, ++timestamp);
            long start = SystemClock.elapsedRealtimeNanos();
            long cpuStart = android.os.Process.getElapsedCpuTime();
            long finish = start + duration * 1_000_000_000L;
            int faces = 0, landmarks = 0, blendshapes = 0;
            while (!stopped && SystemClock.elapsedRealtimeNanos() < finish) {
                long before = SystemClock.elapsedRealtimeNanos();
                timestamp = Math.max(timestamp + 1, SystemClock.uptimeMillis());
                FaceLandmarkerResult result = task.detectForVideo(image, timestamp);
                timings.add((SystemClock.elapsedRealtimeNanos() - before) / 1e6);
                if (!result.faceLandmarks().isEmpty()) {
                    faces++;
                    landmarks = result.faceLandmarks().get(0).size();
                    if (result.faceBlendshapes().isPresent())
                        blendshapes = result.faceBlendshapes().get().get(0).size();
                }
                if (targetFps > 0) {
                    long remaining = 1_000_000_000L / targetFps - (SystemClock.elapsedRealtimeNanos() - before);
                    if (remaining > 0) SystemClock.sleep((remaining + 999999) / 1_000_000);
                }
            }
            double elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1e9;
            summary.put("elapsed_s", elapsed).put("frames", timings.size()).put("face_frames", faces)
                    .put("effective_fps", timings.size() / elapsed).put("landmarks", landmarks)
                    .put("blendshapes", blendshapes).put("p50_ms", percentile(timings, .5))
                    .put("p95_ms", percentile(timings, .95)).put("mean_ms", timings.stream().mapToDouble(x -> x).average().orElse(0))
                    .put("process_cpu_percent_4cores", (android.os.Process.getElapsedCpuTime() - cpuStart) / (elapsed * 40));
            summary.put("inference_samples_ms", new org.json.JSONArray(timings));
            summary.put("status", stopped ? "interrupted" : "success");
        } catch (Throwable error) {
            Log.e(TAG, "Benchmark failed", error);
            try { summary.put("status", "error").put("error", error.toString()); } catch (Exception ignored) {}
        } finally {
            if (task != null) task.close();
            if (image != null) image.close();
        }
        try {
            summary.put("run_id", runId).put("delegate", gpu ? "GPU" : "CPU")
                    .put("mediapipe_version", "1.0.0").put("analysis_target_fps", targetFps)
                    .put("input", "official portrait.jpg repeated in VIDEO mode; no camera or video decode");
            File output = new File(getFilesDir(), runId + ".json");
            Files.write(output.toPath(), summary.toString(2).getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "RESULT " + summary);
            runOnUiThread(() -> status.setText(summary.toString()));
        } catch (Exception error) { Log.e(TAG, "Save failed", error); }
    }

    static double percentile(ArrayList<Double> values, double fraction) {
        if (values.isEmpty()) return 0;
        ArrayList<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(Math.max(0, (int)Math.ceil(fraction * sorted.size()) - 1));
    }

    @Override protected void onDestroy() { stopped = true; super.onDestroy(); }
}
