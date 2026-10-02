package com.mirror.bench;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
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
    private UiLoadView uiLoad;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if("landscape".equals(getIntent().getStringExtra("orientation")))
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
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
            renderer.setTargetFps(getIntent().getIntExtra("render_fps",0));
            renderer.setPipeline(getIntent().getBooleanExtra("pipeline",false));
            renderer.setCull(getIntent().getBooleanExtra("cull",false),getIntent().getBooleanExtra("verify_cull",false));
            renderer.setAtlas(getIntent().getBooleanExtra("atlas",false),getIntent().getBooleanExtra("verify_atlas",false));
            renderer.setAtlasCopy(getIntent().getBooleanExtra("atlas_copy",false));
            renderer.setMultiview(getIntent().getBooleanExtra("multiview",false),getIntent().getBooleanExtra("verify_multiview",false));
            renderer.setPreblend(getIntent().getBooleanExtra("preblend",false),getIntent().getBooleanExtra("verify_preblend",false));
            renderer.setExplicitLod(getIntent().getBooleanExtra("explicit_lod",false));
            renderer.setVerifyCombined(getIntent().getBooleanExtra("verify_combined",false));
            renderer.setDiscardDepth(getIntent().getBooleanExtra("discard_depth",false),getIntent().getBooleanExtra("verify_discard_depth",false));
            renderer.setSharedPhase(getIntent().getBooleanExtra("shared_phase",false),getIntent().getBooleanExtra("verify_shared_phase",false));
            if(getIntent().hasExtra("view_width")||getIntent().hasExtra("view_height"))
                renderer.setViewSize(getIntent().getIntExtra("view_width",0),getIntent().getIntExtra("view_height",0));
            if(getIntent().hasExtra("width_scale")) renderer.setWidthScale(getIntent().getFloatExtra("width_scale",.5f));
            renderer.setLookup(getIntent().getBooleanExtra("lookup",false),getIntent().getBooleanExtra("verify_lookup",false));
            if(getIntent().hasExtra("pitch")) renderer.setPanelParameters(getIntent().getFloatExtra("pitch",10),
                    getIntent().getFloatExtra("tan",.2777777f),getIntent().getStringExtra("pitch_units"));
            surface=new GLSurfaceView(this); surface.setEGLContextClientVersion(3);
            // Only offscreen 3D FBOs use depth; the window receives a full-screen interlace triangle.
            surface.setEGLConfigChooser(8,8,8,8,getIntent().getBooleanExtra("discard_depth",false)?0:16,0); surface.setRenderer(renderer);
            if(getIntent().getBooleanExtra("ui_load",false)) {
                android.widget.FrameLayout root=new android.widget.FrameLayout(this);
                root.addView(surface,new android.widget.FrameLayout.LayoutParams(-1,-1));
                uiLoad=new UiLoadView(this);
                root.addView(uiLoad,new android.widget.FrameLayout.LayoutParams(-1,320)); setContentView(root);
            } else setContentView(surface);
        }
        new Thread(this::measure,"CameraFaceBench").start();
    }
    private void measure() {
        JSONObject result=new JSONObject();
        String delegate=getIntent().getStringExtra("delegate");
        if(delegate==null) delegate="NONE";
        boolean inference=!"NONE".equals(delegate);
        boolean replay="replay".equals(getIntent().getStringExtra("input"));
        boolean portrait="portrait".equals(getIntent().getStringExtra("face_input"));
        boolean referenceFace="replay".equals(getIntent().getStringExtra("face_input"));
        boolean convert=inference||getIntent().getBooleanExtra("convert",false);
        int width=getIntent().getIntExtra("camera_width",640),height=getIntent().getIntExtra("camera_height",480);
        int requestedFps=getIntent().getIntExtra("capture_fps",width>=1280?15:25);
        int targetFps=getIntent().getIntExtra("analysis_fps",0),seconds=getIntent().getIntExtra("seconds",30);
        int rotation=getIntent().getIntExtra("rotation",0);
        boolean rga="rga".equals(getIntent().getStringExtra("conversion_backend"));
        boolean nativePacking=getIntent().getBooleanExtra("native_packing",false);
        boolean npuPipeline=getIntent().getBooleanExtra("npu_pipeline",false);
        boolean referenceCrop=getIntent().getBooleanExtra("crop_reference",false);
        java.util.concurrent.atomic.AtomicInteger asyncFaces=new java.util.concurrent.atomic.AtomicInteger();
        FrameInput camera=null; YuvConverter converter=null; FaceLandmarker task=null; MPImage image=null;
        NpuFacePipeline npu=null; Bitmap inferenceBitmap=null;
        FrameInput referenceFrames=null; YuvConverter referenceConverter=null;
        ArrayList<Double> conversion=new ArrayList<>(),inferenceMs=new ArrayList<>(),queueAge=new ArrayList<>();
        ArrayList<Double> referenceConversion=new ArrayList<>();
        Throwable failure=null;
        try {
            if(seconds<1||seconds>600||targetFps<0) throw new IllegalArgumentException("Invalid measurement duration/rate");
            if(npuPipeline&&!"RKNN".equals(delegate)) throw new IllegalArgumentException("NPU pipeline requires RKNN backend");
            if(referenceCrop&&!"RKNN".equals(delegate)) throw new IllegalArgumentException("Reference crop requires RKNN backend");
            if(referenceFace&&(replay||!inference))
                throw new IllegalArgumentException("Recorded face reference requires live camera plus inference");
            if(convert) {
                converter=new YuvConverter(this,width,height,rga,nativePacking);
                if(rga) result.put("rga_version",RgaConvert.version());
                Bitmap input=converter.bitmap;
                if(inference&&portrait) {
                    // Controlled full-landmark load while REAL camera capture/conversion still run.
                    Bitmap asset;
                    try(var stream=getAssets().open("portrait.jpg")) { asset=BitmapFactory.decodeStream(stream); }
                    input=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
                    Canvas canvas=new Canvas(input); canvas.drawColor(android.graphics.Color.BLACK);
                    float ratio=Math.min(width/(float)asset.getWidth(),height/(float)asset.getHeight());
                    float w=asset.getWidth()*ratio,h=asset.getHeight()*ratio;
                    canvas.drawBitmap(asset,null,new RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2),new Paint(Paint.FILTER_BITMAP_FLAG));
                    asset.recycle();
                }
                if(referenceFace) {
                    referenceConverter=new YuvConverter(this,width,height,rga,false);
                    referenceFrames=new ReplayFrameSource(this,width,height,getIntent().getStringExtra("record_id"),
                            getIntent().getFloatExtra("replay_fps",24.369907f),getIntent().getBooleanExtra("preload",false));
                    input=referenceConverter.bitmap;
                }
                inferenceBitmap=input;
                image=new BitmapImageBuilder(input).build();
            }
            if(inference) {
                long init=SystemClock.elapsedRealtimeNanos();
                if("RKNN".equals(delegate)) {
                    if(rotation!=0) throw new IllegalArgumentException("RKNN benchmark currently requires rotation=0");
                    npu=new NpuFacePipeline(this);
                    npu.setReferenceCrop(referenceCrop);
                } else {
                BaseOptions base=BaseOptions.builder().setModelAssetPath("face_landmarker.task")
                        .setDelegate(Delegate.valueOf(delegate)).build();
                task=FaceLandmarker.createFromOptions(this,FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(base).setRunningMode(RunningMode.VIDEO).setNumFaces(1)
                        .setOutputFaceBlendshapes(true).setOutputFacialTransformationMatrixes(true).build());
                }
                result.put("initialization_ms",(SystemClock.elapsedRealtimeNanos()-init)/1e6);
            }
            ImageProcessingOptions processing=ImageProcessingOptions.builder().setRotationDegrees(rotation).build();
            camera=replay?new ReplayFrameSource(this,width,height,getIntent().getStringExtra("record_id"),
                    getIntent().getFloatExtra("replay_fps",24.369907f),getIntent().getBooleanExtra("preload",false))
                    :new CameraFrameSource(this,width,height,requestedFps);
            long timestamp=0;
            // Warm up the actual camera conversion and actual face input, not an asset portrait.
            for(int i=0;i<10&&!stopped;i++) {
                FrameInput.Frame frame=camera.take();
                try { if(convert) converter.convert(frame); } finally { frame.close(); }
                if(referenceFrames!=null) convertReference(referenceFrames,referenceConverter);
                if(inference) {
                    timestamp=Math.max(timestamp+1,SystemClock.uptimeMillis());
                    if(npuPipeline) npu.submit(inferenceBitmap,timestamp,true,ignored->{});
                    else if(npu!=null) npu.detect(inferenceBitmap,timestamp,true);
                    else task.detectForVideo(image,processing,timestamp);
                }
            }
            if(npu!=null) npu.drain();
            if(renderer!=null) {
                SystemClock.sleep(1500);
                java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(1);
                surface.queueEvent(()->{ renderer.beginMeasurement(); ready.countDown(); });
                if(!ready.await(30,java.util.concurrent.TimeUnit.SECONDS))
                    throw new IllegalStateException("Renderer initialization/validation did not finish");
            }
            camera.beginMeasurement();
            if(referenceFrames!=null) referenceFrames.beginMeasurement();
            if(npu!=null) npu.beginMeasurement();
            if(uiLoad!=null) uiLoad.beginMeasurement();
            long start=SystemClock.elapsedRealtimeNanos(),startEpoch=System.currentTimeMillis();
            long startMonotonic=System.nanoTime();
            long cpuStart=android.os.Process.getElapsedCpuTime(),workerCpuStart=android.os.Debug.threadCpuTimeNanos();
            long conversionCpu=0,inferenceCallerCpu=0;
            int consumed=0,faces=0,landmarks=0,blendshapes=0;
            long nextProgress=start;
            while(!stopped&&SystemClock.elapsedRealtimeNanos()<start+seconds*1_000_000_000L) {
                long iteration=SystemClock.elapsedRealtimeNanos();
                FrameInput.Frame frame=camera.take();
                queueAge.add((SystemClock.elapsedRealtimeNanos()-frame.receivedNs)/1e6);
                try {
                    if(convert) {
                        long before=SystemClock.elapsedRealtimeNanos(),cpu=android.os.Debug.threadCpuTimeNanos();
                        converter.convert(frame);
                        conversion.add((SystemClock.elapsedRealtimeNanos()-before)/1e6);
                        conversionCpu+=android.os.Debug.threadCpuTimeNanos()-cpu;
                    }
                } finally { frame.close(); }
                consumed++;
                if(referenceFrames!=null) {
                    long before=SystemClock.elapsedRealtimeNanos();
                    convertReference(referenceFrames,referenceConverter);
                    referenceConversion.add((SystemClock.elapsedRealtimeNanos()-before)/1e6);
                }
                if(inference) {
                    timestamp=Math.max(timestamp+1,SystemClock.uptimeMillis());
                    long before=SystemClock.elapsedRealtimeNanos(),cpu=android.os.Debug.threadCpuTimeNanos();
                    FaceLandmarkerResult detection=null;
                    NpuFacePipeline.Detection npuDetection=null;
                    if(npuPipeline) {
                        npu.submit(inferenceBitmap,timestamp,true,value->{
                            if(value!=null) {
                                asyncFaces.incrementAndGet();
                                if(renderer!=null) renderer.setExpressions(value.renderWeights());
                            }
                        });
                    } else if(npu!=null) npuDetection=npu.detect(inferenceBitmap,timestamp,true);
                    else detection=task.detectForVideo(image,processing,timestamp);
                    inferenceMs.add((SystemClock.elapsedRealtimeNanos()-before)/1e6);
                    inferenceCallerCpu+=android.os.Debug.threadCpuTimeNanos()-cpu;
                    if(npuDetection!=null) {
                        faces++; landmarks=npuDetection.landmarks().length/3; blendshapes=npuDetection.blendshapes().length;
                        if(renderer!=null) renderer.setExpressions(npuDetection.renderWeights());
                    } else if(detection!=null&&!detection.faceLandmarks().isEmpty()) {
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
                    save(runId+"-progress",new JSONObject().put("consumed",consumed).put("face_frames",npuPipeline?asyncFaces.get():faces)
                            .put("elapsed_s",(now-start)/1e9).put("delegate",delegate));
                    nextProgress=now+3_000_000_000L;
                }
                if(inference&&targetFps>0) {
                    long rest=1_000_000_000L/targetFps-(SystemClock.elapsedRealtimeNanos()-iteration);
                    if(rest>0) SystemClock.sleep((rest+999999)/1_000_000);
                }
            }
            if(npuPipeline) {
                npu.drain(); faces=asyncFaces.get();
                if(faces>0) { landmarks=478; blendshapes=52; }
            }
            double elapsed=(SystemClock.elapsedRealtimeNanos()-start)/1e9;
            result.put("elapsed_s",elapsed).put("measurement_start_epoch_ms",startEpoch)
                    .put("measurement_start_monotonic_ns",startMonotonic)
                    .put("measurement_end_monotonic_ns",System.nanoTime())
                    .put("measurement_end_epoch_ms",System.currentTimeMillis())
                    .put("frames",inferenceMs.size()).put("consumed_images",consumed).put("consumption_fps",consumed/elapsed)
                    .put("face_frames",faces).put("face_fraction",inferenceMs.isEmpty()?0:faces/(double)inferenceMs.size())
                    .put("effective_fps",inferenceMs.size()/elapsed).put("landmarks",landmarks).put("blendshapes",blendshapes)
                    .put(replay?"replay":"camera",camera.summary(elapsed)).put("conversion",stats(conversion))
                    .put("inference",stats(inferenceMs)).put("pending_frame_age",stats(queueAge))
                    .put("npu_pipeline_enabled",npuPipeline)
                    .put("inference_timing_scope",npuPipeline?"NPU producer call, including backpressure; full face latency in npu_pipeline.completed_face_latency_mean_ms":"Synchronous image network and complete postprocessing")
                    .put("process_cpu_percent_4cores",(android.os.Process.getElapsedCpuTime()-cpuStart)/(elapsed*40))
                    .put("worker_cpu_percent_4cores",(android.os.Debug.threadCpuTimeNanos()-workerCpuStart)/(elapsed*4e7))
                    .put("conversion_caller_cpu_percent_4cores",conversionCpu/(elapsed*4e7))
                    .put("inference_caller_cpu_percent_4cores",inferenceCallerCpu/(elapsed*4e7))
                    .put("cpu_attribution","Caller CPU times exclude MediaPipe/RenderScript worker threads; use process/isolation metrics for total load")
                    .put("inference_samples_ms",new JSONArray(inferenceMs)).put("conversion_samples_ms",new JSONArray(conversion))
                    .put("status",stopped?"interrupted":"success");
            if(referenceFrames!=null) result.put("face_reference",referenceFrames.summary(elapsed))
                    .put("reference_conversion",stats(referenceConversion))
                    .put("reference_conversion_samples_ms",new JSONArray(referenceConversion));
            if(npu!=null) result.put("npu_pipeline",npu.summary());
            if(uiLoad!=null) result.put("ui_load",uiLoad.summary());
            if(renderer!=null) {
                JSONObject render=renderer.summary(); result.put("render",render);
                if(!render.getString("error").isEmpty()||render.getInt("frames")==0)
                    result.put("status","error").put("error",render.optString("error"));
            }
            if(!result.getJSONObject(replay?"replay":"camera").getString("error").isEmpty()||consumed==0)
                result.put("status","error").put("error","Camera input failed");
        } catch(Throwable error) {
            failure=error;
            Log.e("MirrorBench","Camera benchmark failed",error);
            try { result.put("status","error").put("error",error.toString()); } catch(Exception ignored) {}
        } finally {
            ResourceCleanup cleanup=new ResourceCleanup(failure);
            cleanup.close("camera",camera==null?null:camera::close);
            cleanup.close("face_landmarker",task==null?null:task::close);
            cleanup.close("npu_pipeline",npu==null?null:npu::close);
            cleanup.close("image",image==null?null:image::close);
            cleanup.close("converter",converter==null?null:converter::close);
            cleanup.close("reference_frames",referenceFrames==null?null:referenceFrames::close);
            cleanup.close("reference_converter",referenceConverter==null?null:referenceConverter::close);
            if(!cleanup.errors().isEmpty()) {
                try {
                    result.put("status","error");
                    // Preserve both caught exceptions and previously reported camera/render errors.
                    if(result.optString("error").isEmpty()) result.put("error",cleanup.failure().toString());
                    JSONArray errors=new JSONArray();
                    for(ResourceCleanup.Failure entry:cleanup.errors()) {
                        errors.put(new JSONObject().put("resource",entry.resource()).put("error",entry.error().toString()));
                        Log.e("MirrorBench","Cleanup failed: "+entry.resource(),entry.error());
                    }
                    result.put("cleanup_errors",errors);
                } catch(Exception error) { Log.e("MirrorBench","Record cleanup errors failed",error); }
            }
        }
        try {
            result.put("run_id",runId).put("delegate",delegate).put("analysis_target_fps",targetFps)
                    .put("input",replay?"recorded NV21 replay; no USB capture or video decoder":"live USB Camera2 YUV_420_888; native MJPEG decode in external camera HAL")
                    .put("camera_width",width).put("camera_height",height).put("requested_capture_fps",requestedFps)
                    .put("rotation",rotation).put("conversion_enabled",convert).put("running_mode","VIDEO");
            result.put("conversion_backend",rga?"RGA BT601 limited":"RenderScript YuvToRGB");
            result.put("packing_backend",nativePacking?"native direct-plane packing; Java fallback for heap planes":"bulk Java plane rows");
            result.put("face_input",referenceFace?"recorded face video; live USB camera capture/conversion also running":portrait?"controlled official portrait fitted to camera dimensions":replay?"recorded camera frames":"real camera frames")
                    .put("condition",referenceFace?"Live USB acquisition, native MJPEG decode and conversion, plus separately converted recorded face video for complete moving-face load":portrait?"Real camera capture and conversion; independent fixed face for full-landmark load":replay?"Paced recorded face clip; excludes camera acquisition and decode":"Real camera input; inspect face_fraction for complete face load");
        } catch(Exception error) {
            Log.e("MirrorBench","Finalize camera result failed",error);
            try {
                result.put("status","error");
                if(result.optString("error").isEmpty()) result.put("error",error.toString());
            } catch(Exception ignored) {}
        }
        // Attempt persistence even if metadata or cleanup diagnostics failed.
        try {
            save(runId,result);
            JSONObject compact=new JSONObject(result.toString()); compact.remove("inference_samples_ms"); compact.remove("conversion_samples_ms");
            compact.remove("reference_conversion_samples_ms");
            if(compact.has("render")) { compact.getJSONObject("render").remove("frame_work_samples_ms"); compact.getJSONObject("render").remove("frame_interval_samples_ms"); }
            Log.i("MirrorBench","RESULT "+compact);
        } catch(Exception error) { Log.e("MirrorBench","Save camera result failed",error); }
    }
    private static JSONObject stats(ArrayList<Double> values) throws Exception {
        return new JSONObject().put("count",values.size()).put("mean_ms",values.stream().mapToDouble(x->x).average().orElse(0))
                .put("p50_ms",BenchActivity.percentile(values,.5)).put("p95_ms",BenchActivity.percentile(values,.95));
    }
    private static void convertReference(FrameInput source,YuvConverter converter) throws Exception {
        FrameInput.Frame frame=source.take();
        try { converter.convert(frame); } finally { frame.close(); }
    }
    private void save(String name,JSONObject data) throws Exception {
        Files.write(new File(getFilesDir(),name+".json").toPath(),data.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    @Override protected void onDestroy() { stopped=true; super.onDestroy(); }
}
