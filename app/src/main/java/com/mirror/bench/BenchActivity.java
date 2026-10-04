package com.mirror.bench;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;
import android.view.View;
import android.opengl.GLSurfaceView;
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
    private GLSurfaceView surface;
    private InterlaceRenderer renderer;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status = new TextView(this);
        status.setTextSize(22);
        status.setPadding(32, 48, 32, 32);
        status.setText("MediaPipe offline benchmark");
        setContentView(status);
        String renderMode=getIntent().getStringExtra("render");
        if(renderMode!=null&&!"none".equals(renderMode)) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    |View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    |View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    |View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            renderer=new InterlaceRenderer(getIntent().getIntExtra("views",4),
                    getIntent().getFloatExtra("scale",1),"scene".equals(renderMode));
            renderer.setProfileStages(getIntent().getBooleanExtra("profile_stages",false));
            renderer.setPipeline(getIntent().getBooleanExtra("pipeline",false));
            renderer.setCull(getIntent().getBooleanExtra("cull",false),getIntent().getBooleanExtra("verify_cull",false));
            renderer.setAtlas(getIntent().getBooleanExtra("atlas",false),getIntent().getBooleanExtra("verify_atlas",false));
            renderer.setAtlasCopy(getIntent().getBooleanExtra("atlas_copy",false));
            renderer.setMultiview(getIntent().getBooleanExtra("multiview",false),getIntent().getBooleanExtra("verify_multiview",false));
            renderer.setPreblend(getIntent().getBooleanExtra("preblend",false),getIntent().getBooleanExtra("verify_preblend",false));
            renderer.setExplicitLod(getIntent().getBooleanExtra("explicit_lod",false));
            renderer.setVerifyCombined(getIntent().getBooleanExtra("verify_combined",false));
            if(getIntent().hasExtra("width_scale")) renderer.setWidthScale(getIntent().getFloatExtra("width_scale",.5f));
            renderer.setLookup(getIntent().getBooleanExtra("lookup",false),getIntent().getBooleanExtra("verify_lookup",false));
            if(getIntent().hasExtra("pitch")) renderer.setPanelParameters(getIntent().getFloatExtra("pitch",10),
                    getIntent().getFloatExtra("tan",.2777777f),getIntent().getStringExtra("pitch_units"));
            surface=new GLSurfaceView(this);
            surface.setEGLContextClientVersion(3);
            surface.setEGLConfigChooser(8,8,8,8,16,0);
            surface.setRenderer(renderer);
            setContentView(surface);
        }
        runId = getIntent().getStringExtra("run_id");
        if (runId == null || !runId.matches("[a-zA-Z0-9_-]+")) runId = "baseline";
        new Thread(this::runBenchmark, "FaceBenchmark").start();
    }

    private void runBenchmark() {
        JSONObject summary = new JSONObject();
        String delegateName = getIntent().getStringExtra("delegate");
        if(delegateName==null) delegateName="CPU";
        boolean gpu = "GPU".equals(delegateName);
        boolean inference=!"NONE".equals(delegateName);
        int duration = getIntent().getIntExtra("seconds", 20);
        int targetFps = getIntent().getIntExtra("analysis_fps", 0);
        boolean imageMode="IMAGE".equals(getIntent().getStringExtra("running_mode"));
        ArrayList<Double> timings = new ArrayList<>();
        FaceLandmarker task = null;
        MPImage image = null;
        try {
            if(inference) {
            Bitmap source;
            try (var input = getAssets().open("portrait.jpg")) {
                source = BitmapFactory.decodeStream(input);
            }
            Bitmap bitmap = source.copy(Bitmap.Config.ARGB_8888, false);
            source.recycle();
            image = new BitmapImageBuilder(bitmap).build();
            long initStart = SystemClock.elapsedRealtimeNanos();
            BaseOptions base = BaseOptions.builder().setModelAssetPath("face_landmarker.task")
                    .setDelegate(Delegate.valueOf(delegateName)).build();
            // https://ai.google.dev/edge/mediapipe/solutions/vision/face_landmarker/android
            task = FaceLandmarker.createFromOptions(this, FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(base).setRunningMode(imageMode?RunningMode.IMAGE:RunningMode.VIDEO).setNumFaces(1)
                    .setOutputFaceBlendshapes(true).setOutputFacialTransformationMatrixes(true).build());
            summary.put("initialization_ms", (SystemClock.elapsedRealtimeNanos() - initStart) / 1e6);
            long timestamp = SystemClock.uptimeMillis();
            for (int i = 0; i < 10 && !stopped; i++) {
                if(imageMode) task.detect(image); else task.detectForVideo(image, ++timestamp);
            }
            }
            if(renderer!=null) {
                SystemClock.sleep(2000);
                java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(1);
                surface.queueEvent(()->{ renderer.beginMeasurement(); ready.countDown(); });
                if(!ready.await(30,java.util.concurrent.TimeUnit.SECONDS))
                    throw new IllegalStateException("Renderer initialization/validation did not finish");
            }
            long start = SystemClock.elapsedRealtimeNanos();
            long startEpoch=System.currentTimeMillis();
            long startMonotonic=System.nanoTime();
            long cpuStart = android.os.Process.getElapsedCpuTime();
            long finish = start + duration * 1_000_000_000L;
            int faces = 0, landmarks = 0, blendshapes = 0;
            long timestamp=SystemClock.uptimeMillis();
            while (!stopped && SystemClock.elapsedRealtimeNanos() < finish) {
                if(!inference) { SystemClock.sleep(20); continue; }
                long before = SystemClock.elapsedRealtimeNanos();
                timestamp = Math.max(timestamp + 1, SystemClock.uptimeMillis());
                FaceLandmarkerResult result = imageMode?task.detect(image):task.detectForVideo(image, timestamp);
                timings.add((SystemClock.elapsedRealtimeNanos() - before) / 1e6);
                if (!result.faceLandmarks().isEmpty()) {
                    faces++;
                    landmarks = result.faceLandmarks().get(0).size();
                    if (result.faceBlendshapes().isPresent())
                        blendshapes = result.faceBlendshapes().get().get(0).size();
                    if(renderer!=null&&result.faceBlendshapes().isPresent()) {
                        float[] weights=new float[4];
                        for(var category:result.faceBlendshapes().get().get(0)) {
                            String name=category.categoryName(); float score=category.score();
                            if("jawOpen".equals(name)) weights[0]=score;
                            if("eyeBlinkLeft".equals(name)||"eyeBlinkRight".equals(name)) weights[1]+=score*.5f;
                            if("mouthSmileLeft".equals(name)||"mouthSmileRight".equals(name)) weights[2]+=score*.5f;
                            if("browInnerUp".equals(name)) weights[3]=score;
                        }
                        renderer.setExpressions(weights);
                    }
                }
                if (targetFps > 0) {
                    long remaining = 1_000_000_000L / targetFps - (SystemClock.elapsedRealtimeNanos() - before);
                    if (remaining > 0) SystemClock.sleep((remaining + 999999) / 1_000_000);
                }
            }
            double elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1e9;
            summary.put("elapsed_s", elapsed).put("frames", timings.size()).put("face_frames", faces)
                    .put("measurement_start_epoch_ms",startEpoch).put("measurement_end_epoch_ms",System.currentTimeMillis())
                    .put("measurement_start_monotonic_ns",startMonotonic).put("measurement_end_monotonic_ns",System.nanoTime())
                    .put("effective_fps", timings.size() / elapsed).put("landmarks", landmarks)
                    .put("blendshapes", blendshapes).put("p50_ms", percentile(timings, .5))
                    .put("p95_ms", percentile(timings, .95)).put("mean_ms", timings.stream().mapToDouble(x -> x).average().orElse(0))
                    .put("process_cpu_percent_4cores", (android.os.Process.getElapsedCpuTime() - cpuStart) / (elapsed * 40));
            summary.put("inference_samples_ms", new org.json.JSONArray(timings));
            summary.put("status", stopped ? "interrupted" : "success");
            if(renderer!=null) {
                JSONObject rendering=renderer.summary(); summary.put("render",rendering);
                if(!rendering.getString("error").isEmpty()||rendering.getInt("frames")==0)
                    summary.put("status","error").put("error",rendering.optString("error","No rendered frames"));
            }
        } catch (Throwable error) {
            Log.e(TAG, "Benchmark failed", error);
            try { summary.put("status", "error").put("error", error.toString()); } catch (Exception ignored) {}
        } finally {
            if (task != null) task.close();
            if (image != null) image.close();
        }
        try {
            summary.put("run_id", runId).put("delegate", inference?(gpu ? "GPU" : "CPU"):"NONE")
                    .put("mediapipe_version", "1.0.0").put("analysis_target_fps", targetFps)
                    .put("running_mode",imageMode?"IMAGE":"VIDEO")
                    .put("input", "official portrait.jpg repeated; no camera or video decode");
            File output = new File(getFilesDir(), runId + ".json");
            Files.write(output.toPath(), summary.toString(2).getBytes(StandardCharsets.UTF_8));
            JSONObject compact=new JSONObject(summary.toString()); compact.remove("inference_samples_ms");
            if(compact.has("render")) {
                compact.getJSONObject("render").remove("frame_work_samples_ms");
                compact.getJSONObject("render").remove("frame_interval_samples_ms");
            }
            Log.i(TAG, "RESULT " + compact);
            runOnUiThread(() -> status.setText(compact.toString()));
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
