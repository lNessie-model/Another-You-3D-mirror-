package com.mirror.bench;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.opengl.GLSurfaceView;
import android.widget.TextView;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;

/** Real USB Camera2 input; synchronous VIDEO inference consumes only the latest frame. */
public final class CameraBenchActivity extends Activity {
    private volatile boolean stopped;
    private String runId;
    private GLSurfaceView surface;
    private InterlaceRenderer renderer;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        TextView label=new TextView(this); label.setText("USB camera performance measurement"); setContentView(label);
        runId=getIntent().getStringExtra("run_id");
        if(runId==null||!runId.matches("[a-zA-Z0-9_-]+")) runId="camera";
        String mode=getIntent().getStringExtra("render");
        if(mode!=null&&!"none".equals(mode)) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    |View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    |View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            renderer=new InterlaceRenderer(getIntent().getIntExtra("views",4),getIntent().getFloatExtra("scale",.5f),"scene".equals(mode));
            renderer.setProfileStages(true);
            surface=new GLSurfaceView(this); surface.setEGLContextClientVersion(3);
            surface.setEGLConfigChooser(8,8,8,8,16,0); surface.setRenderer(renderer); setContentView(surface);
        }
        new Thread(this::measure,"CameraFaceBench").start();
    }
    private void measure() {
        JSONObject result=new JSONObject();
        String delegate=getIntent().getStringExtra("delegate");
        if(delegate==null) delegate="NONE";
        boolean inference=!"NONE".equals(delegate);
        boolean convert=inference||getIntent().getBooleanExtra("convert",false);
        int width=getIntent().getIntExtra("camera_width",640),height=getIntent().getIntExtra("camera_height",480);
        int requestedFps=getIntent().getIntExtra("capture_fps",width>=1280?15:25);
        int targetFps=getIntent().getIntExtra("analysis_fps",0),seconds=getIntent().getIntExtra("seconds",30);
        int rotation=getIntent().getIntExtra("rotation",0);
        CameraFrameSource camera=null; YuvConverter converter=null; FaceLandmarker task=null; MPImage image=null;
        ArrayList<Double> conversion=new ArrayList<>(),inferenceMs=new ArrayList<>(),queueAge=new ArrayList<>();
        try {
            if(seconds<1||seconds>600||targetFps<0) throw new IllegalArgumentException("Invalid measurement duration/rate");
            if(convert) {
                converter=new YuvConverter(this,width,height);
                image=new BitmapImageBuilder(converter.bitmap).build();
            }
            if(inference) {
                long init=SystemClock.elapsedRealtimeNanos();
                BaseOptions base=BaseOptions.builder().setModelAssetPath("face_landmarker.task")
                        .setDelegate("GPU".equals(delegate)?Delegate.GPU:Delegate.CPU).build();
                task=FaceLandmarker.createFromOptions(this,FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(base).setRunningMode(RunningMode.VIDEO).setNumFaces(1)
                        .setOutputFaceBlendshapes(true).setOutputFacialTransformationMatrixes(true).build());
                result.put("initialization_ms",(SystemClock.elapsedRealtimeNanos()-init)/1e6);
            }
            ImageProcessingOptions processing=ImageProcessingOptions.builder().setRotationDegrees(rotation).build();
            camera=new CameraFrameSource(this,width,height,requestedFps);
            long timestamp=0;
            // Warm up the actual camera conversion and actual face input, not an asset portrait.
            for(int i=0;i<10&&!stopped;i++) {
                CameraFrameSource.Frame frame=camera.take();
                try { if(convert) converter.convert(frame.image); } finally { frame.image.close(); }
                if(inference) {
                    timestamp=Math.max(timestamp+1,SystemClock.uptimeMillis());
                    task.detectForVideo(image,processing,timestamp);
                }
            }
            if(renderer!=null) { SystemClock.sleep(1500); surface.queueEvent(renderer::beginMeasurement); }
            camera.beginMeasurement();
            long start=SystemClock.elapsedRealtimeNanos(),startEpoch=System.currentTimeMillis();
            long cpuStart=android.os.Process.getElapsedCpuTime(),workerCpuStart=android.os.Debug.threadCpuTimeNanos();
            long conversionCpu=0,inferenceCallerCpu=0;
            int consumed=0,faces=0,landmarks=0,blendshapes=0;
            long nextProgress=start;
            while(!stopped&&SystemClock.elapsedRealtimeNanos()<start+seconds*1_000_000_000L) {
                long iteration=SystemClock.elapsedRealtimeNanos();
                CameraFrameSource.Frame frame=camera.take();
                queueAge.add((SystemClock.elapsedRealtimeNanos()-frame.receivedNs)/1e6);
                try {
                    if(convert) {
                        long before=SystemClock.elapsedRealtimeNanos(),cpu=android.os.Debug.threadCpuTimeNanos();
                        converter.convert(frame.image);
                        conversion.add((SystemClock.elapsedRealtimeNanos()-before)/1e6);
                        conversionCpu+=android.os.Debug.threadCpuTimeNanos()-cpu;
                    }
                } finally { frame.image.close(); }
                consumed++;
                if(inference) {
                    timestamp=Math.max(timestamp+1,SystemClock.uptimeMillis());
                    long before=SystemClock.elapsedRealtimeNanos(),cpu=android.os.Debug.threadCpuTimeNanos();
                    FaceLandmarkerResult detection=task.detectForVideo(image,processing,timestamp);
                    inferenceMs.add((SystemClock.elapsedRealtimeNanos()-before)/1e6);
                    inferenceCallerCpu+=android.os.Debug.threadCpuTimeNanos()-cpu;
                    if(!detection.faceLandmarks().isEmpty()) {
                        faces++; landmarks=detection.faceLandmarks().get(0).size();
                        if(detection.faceBlendshapes().isPresent()) {
                            blendshapes=detection.faceBlendshapes().get().get(0).size();
                            float[] weights=new float[4];
                            for(var category:detection.faceBlendshapes().get().get(0)) {
                                String name=category.categoryName(); float score=category.score();
                                if("jawOpen".equals(name)) weights[0]=score;
                                if("eyeBlinkLeft".equals(name)||"eyeBlinkRight".equals(name)) weights[1]+=score*.5f;
                                if("mouthSmileLeft".equals(name)||"mouthSmileRight".equals(name)) weights[2]+=score*.5f;
                                if("browInnerUp".equals(name)) weights[3]=score;
                            }
                            if(renderer!=null) renderer.setExpressions(weights);
                        }
                    }
                }
                long now=SystemClock.elapsedRealtimeNanos();
                if(now>=nextProgress) {
                    save(runId+"-progress",new JSONObject().put("consumed",consumed).put("face_frames",faces)
                            .put("elapsed_s",(now-start)/1e9).put("delegate",delegate));
                    nextProgress=now+3_000_000_000L;
                }
                if(inference&&targetFps>0) {
                    long rest=1_000_000_000L/targetFps-(SystemClock.elapsedRealtimeNanos()-iteration);
                    if(rest>0) SystemClock.sleep((rest+999999)/1_000_000);
                }
            }
            double elapsed=(SystemClock.elapsedRealtimeNanos()-start)/1e9;
            result.put("elapsed_s",elapsed).put("measurement_start_epoch_ms",startEpoch)
                    .put("measurement_end_epoch_ms",System.currentTimeMillis())
                    .put("frames",inferenceMs.size()).put("consumed_images",consumed).put("consumption_fps",consumed/elapsed)
                    .put("face_frames",faces).put("face_fraction",inferenceMs.isEmpty()?0:faces/(double)inferenceMs.size())
                    .put("effective_fps",inferenceMs.size()/elapsed).put("landmarks",landmarks).put("blendshapes",blendshapes)
                    .put("camera",camera.summary(elapsed)).put("conversion",stats(conversion))
                    .put("inference",stats(inferenceMs)).put("pending_frame_age",stats(queueAge))
                    .put("process_cpu_percent_4cores",(android.os.Process.getElapsedCpuTime()-cpuStart)/(elapsed*40))
                    .put("worker_cpu_percent_4cores",(android.os.Debug.threadCpuTimeNanos()-workerCpuStart)/(elapsed*4e7))
                    .put("conversion_caller_cpu_percent_4cores",conversionCpu/(elapsed*4e7))
                    .put("inference_caller_cpu_percent_4cores",inferenceCallerCpu/(elapsed*4e7))
                    .put("cpu_attribution","Caller CPU times exclude MediaPipe/RenderScript worker threads; use process/isolation metrics for total load")
                    .put("inference_samples_ms",new JSONArray(inferenceMs)).put("conversion_samples_ms",new JSONArray(conversion))
                    .put("status",stopped?"interrupted":"success");
            if(renderer!=null) {
                JSONObject render=renderer.summary(); result.put("render",render);
                if(!render.getString("error").isEmpty()||render.getInt("frames")==0)
                    result.put("status","error").put("error",render.optString("error"));
            }
            if(!result.getJSONObject("camera").getString("error").isEmpty()||consumed==0)
                result.put("status","error").put("error","Camera input failed");
        } catch(Throwable error) {
            Log.e("MirrorBench","Camera benchmark failed",error);
            try { result.put("status","error").put("error",error.toString()); } catch(Exception ignored) {}
        } finally {
            if(camera!=null) camera.close();
            if(task!=null) task.close();
            if(image!=null) image.close();
            if(converter!=null) converter.close();
        }
        try {
            result.put("run_id",runId).put("delegate",delegate).put("analysis_target_fps",targetFps)
                    .put("input","live USB Camera2 YUV_420_888; native MJPEG decode in external camera HAL")
                    .put("camera_width",width).put("camera_height",height).put("requested_capture_fps",requestedFps)
                    .put("rotation",rotation).put("conversion_enabled",convert).put("running_mode","VIDEO");
            save(runId,result);
            JSONObject compact=new JSONObject(result.toString()); compact.remove("inference_samples_ms"); compact.remove("conversion_samples_ms");
            if(compact.has("render")) { compact.getJSONObject("render").remove("frame_work_samples_ms"); compact.getJSONObject("render").remove("frame_interval_samples_ms"); }
            Log.i("MirrorBench","RESULT "+compact);
        } catch(Exception error) { Log.e("MirrorBench","Save camera result failed",error); }
    }
    private static JSONObject stats(ArrayList<Double> values) throws Exception {
        return new JSONObject().put("count",values.size()).put("mean_ms",values.stream().mapToDouble(x->x).average().orElse(0))
                .put("p50_ms",BenchActivity.percentile(values,.5)).put("p95_ms",BenchActivity.percentile(values,.95));
    }
    private void save(String name,JSONObject data) throws Exception {
        Files.write(new File(getFilesDir(),name+".json").toPath(),data.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    @Override protected void onDestroy() { stopped=true; super.onDestroy(); }
}
