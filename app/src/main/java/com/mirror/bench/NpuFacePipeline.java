package com.mirror.bench;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import org.json.JSONObject;

/** RKNN image networks plus unmodified CPU MediaPipe expression/geometry graphs. */
final class NpuFacePipeline implements AutoCloseable {
    record Detection(float[] landmarks,float[] blendshapes,float[] pose,float presence) {
        float[] renderWeights() { return new float[]{blendshapes[25],(blendshapes[9]+blendshapes[10])*.5f,
                (blendshapes[44]+blendshapes[45])*.5f,blendshapes[3]}; }
    }
    private RknnModel detector,mesh;
    private interface PostProcessor extends AutoCloseable {
        float[][] process(float[] xyz,int width,int height,long timestamp);
        JSONObject summary() throws Exception;
        @Override void close();
    }
    // Allocate the adapter before its graph acquires native resources.
    private static final class CpuPost implements PostProcessor {
        private final FacePostGraph graph;
        CpuPost(String model,String metadata,boolean smoothing){graph=new FacePostGraph(model,metadata,smoothing);}
        public float[][] process(float[] xyz,int width,int height,long timestamp){return graph.process(xyz,width,height,timestamp);}
        public JSONObject summary()throws Exception{return new JSONObject().put("backend","MediaPipe CPU 52 blendshapes + canonical pose");}
        public void close(){graph.close();}
    }
    private static final class CandidatePost implements PostProcessor {
        private final NpuExpressionPostGraph graph;
        CandidatePost(String model,String apk,String metadata,boolean smoothing)throws java.io.IOException{
            graph=new NpuExpressionPostGraph(model,apk,metadata,smoothing);
        }
        public float[][] process(float[] xyz,int width,int height,long timestamp){return graph.process(xyz,width,height,timestamp);}
        public JSONObject summary()throws Exception{return graph.summary();}
        public void close(){graph.close();}
    }
    private PostProcessor post;
    private java.util.concurrent.ExecutorService postWorker;
    private java.util.concurrent.Future<?> pendingPost;
    private volatile boolean closeRequested,closeSequenceCompleted,postExecutorTerminated;
    private volatile Throwable closeFailure;
    record CloseStatus(boolean requested,boolean sequenceCompleted,boolean postExecutorTerminated,boolean failed,String failureType) {}
    CloseStatus closeStatus(){
        // Completion is published last; observing it also observes termination and failure.
        boolean completed=closeSequenceCompleted;Throwable failure=closeFailure;
        String type=failure==null?"":failure.getClass().getSimpleName();
        return new CloseStatus(closeRequested,completed,postExecutorTerminated,failure!=null,type.substring(0,Math.min(type.length(),96)));
    }
    private void requireOpen(){if(closeRequested)throw new IllegalStateException("NPU face pipeline is stopping or closed");}
    private boolean pipelined;
    private record Raw(float[] xyz,int width,int height,long timestamp,float presence,boolean reset,long startedNs) {}
    private File modelDirectory;
    private final boolean smoothing;
    private final boolean npuExpressions;
    private final String applicationApkPath;
    private boolean resetPost;
    private boolean referenceCrop,processingStarted;
    private final ByteBuffer detectorInput=RknnModel.buffer(128*128*3),meshInput=RknnModel.buffer(256*256*3);
    private float[] roi;
    private int detections,meshCalls,postCalls;
    private double cropNs,detectorNs,meshNs,postNs,completionNs;
    private File dumpDirectory;
    void dumpTo(File directory) { dumpDirectory=directory; }
    void setReferenceCrop(boolean enabled) {
        if(processingStarted) throw new IllegalStateException("Choose crop implementation before processing frames");
        referenceCrop=enabled;
    }
    void beginMeasurement() { requireOpen();drain(); detections=0; meshCalls=0; postCalls=0; cropNs=0; detectorNs=0; meshNs=0; postNs=0; completionNs=0; }
    private void dump(String model,ByteBuffer input,float[][] output,JSONObject info) {
        if(dumpDirectory==null) return;
        try {
            dumpDirectory.mkdirs();
            try(var channel=java.nio.channels.FileChannel.open(new File(dumpDirectory,model+"-input.f32").toPath(),
                    java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,java.nio.file.StandardOpenOption.WRITE)) {
                ByteBuffer data=input.duplicate(); data.clear(); while(data.hasRemaining()) channel.write(data);
            }
            org.json.JSONArray rows=new org.json.JSONArray();
            for(float[] row:output) { org.json.JSONArray values=new org.json.JSONArray(); for(float value:row) values.put(value); rows.put(values); }
            Files.write(new File(dumpDirectory,model+"-outputs.json").toPath(),new JSONObject().put("info",info).put("values",rows).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch(Exception error) { throw new IllegalStateException("Cannot save numerical validation fixture",error); }
    }
    NpuFacePipeline(Context context) throws Exception { this(context,true); }
    NpuFacePipeline(Context context,boolean smoothing) throws Exception { this(context,smoothing,false); }
    NpuFacePipeline(Context context,boolean smoothing,boolean npuExpressions) throws Exception {
        this.smoothing=smoothing;
        this.npuExpressions=npuExpressions;
        applicationApkPath=context.getApplicationInfo().sourceDir;
        File dir=new File(context.getFilesDir(),"npu-face"); dir.mkdirs();
        modelDirectory=dir;
        try {
            for(String name:new String[]{"face_detector.rknn","face_landmarks_detector.rknn","face_blendshapes.tflite","geometry_pipeline_metadata_landmarks.binarypb"}) {
                try(var in=context.getAssets().open(name)) { Files.copy(in,new File(dir,name).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            }
            if(npuExpressions) {
                String name="face_blendshapes_normalized_suffix.rknn";
                try(var in=context.getAssets().open(name)) { Files.copy(in,new File(dir,name).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            }
            detector=new RknnModel(new File(dir,"face_detector.rknn").getPath());
            mesh=new RknnModel(new File(dir,"face_landmarks_detector.rknn").getPath());
            post=createPost();
        } catch(Throwable error) {
            try { close(); }
            catch(Throwable cleanup) {
                if(cleanup!=error)error.addSuppressed(cleanup instanceof NativeCleanupUnconfirmed?cleanup
                        :new NativeCleanupUnconfirmed("NPU initialization cleanup unconfirmed",cleanup));
            }
            throw error;
        }
    }
    Detection detect(Bitmap image,long timestamp,boolean tracking) {
        requireOpen();
        drain();
        Raw raw=detectRaw(image,timestamp,tracking);
        return raw==null?null:process(raw);
    }
    /** One CPU job at most; publish its result immediately, without waiting for the next NPU frame. */
    void submit(Bitmap image,long timestamp,boolean tracking,java.util.function.Consumer<Detection> callback) {
        requireOpen();
        if(callback==null) throw new IllegalArgumentException("A pipeline result callback is required");
        Raw raw=detectRaw(image,timestamp,tracking); // Overlap with the PREVIOUS frame's CPU postprocessing.
        drain(); // Backpressure: never enqueue another CPU job while one is outstanding.
        pipelined=true;
        if(raw==null) { callback.accept(null); return; }
        if(postWorker==null) postWorker=java.util.concurrent.Executors.newSingleThreadExecutor(
                work->new Thread(work,"FaceCpuPost"));
        pendingPost=postWorker.submit(()->callback.accept(process(raw)));
    }
    /** Await final callback and establish visibility before reading metrics or closing native graphs. */
    void drain() {
        if(pendingPost==null) return;
        boolean interrupted=false;
        try {
            for(;;) {
                try { pendingPost.get(); break; }
                catch(InterruptedException error) { interrupted=true; }
            }
        } catch(java.util.concurrent.ExecutionException error) {
            throw new IllegalStateException("CPU face pipeline failed",error.getCause());
        } finally { pendingPost=null; if(interrupted) Thread.currentThread().interrupt(); }
    }
    private Raw detectRaw(Bitmap image,long timestamp,boolean tracking) {
        processingStarted=true;
        long started=SystemClock.elapsedRealtimeNanos();
        int w=image.getWidth(),h=image.getHeight();
        if(!tracking) roi=null;
        if(roi==null) {
            float side=Math.max(w,h); long before=SystemClock.elapsedRealtimeNanos();
            crop(image,detectorInput,128,w*.5f,h*.5f,side,0,127.5f,127.5f);
            cropNs+=SystemClock.elapsedRealtimeNanos()-before;
            before=SystemClock.elapsedRealtimeNanos(); float[][] output=detector.run(detectorInput);
            detectorNs+=SystemClock.elapsedRealtimeNanos()-before; detections++;
            dump("face_detector",detectorInput,output,detector.info);
            roi=decodeDetector(output,w,h,side);
            if(roi==null) return null;
        }
        long before=SystemClock.elapsedRealtimeNanos();
        crop(image,meshInput,256,roi[0],roi[1],roi[2],roi[3],0,255);
        cropNs+=SystemClock.elapsedRealtimeNanos()-before;
        before=SystemClock.elapsedRealtimeNanos(); float[][] output=mesh.run(meshInput);
        meshNs+=SystemClock.elapsedRealtimeNanos()-before; meshCalls++;
        dump("face_landmarks_detector",meshInput,output,mesh.info);
        float[] values=null; float logit=Float.NaN;
        for(int i=0;i<output.length;i++) {
            String name=mesh.info.optJSONArray("outputs").optJSONObject(i).optString("name");
            if(output[i].length==1434) values=output[i];
            // Official graph splits the FIRST TWO outputs: coordinates and Identity_1 presence logit.
            // Identity_2 is an auxiliary output, not face presence.
            if(name.equals("Identity_1")) logit=output[i][0];
        }
        if(values==null||!Float.isFinite(logit)) throw new IllegalStateException("RKNN mesh tensor mapping mismatch");
        float presence=(float)(1/(1+Math.exp(-logit)));
        if(presence<.5f) { roi=null; resetPost=smoothing; return null; }
        float cs=(float)Math.cos(roi[3]),sn=(float)Math.sin(roi[3]);
        float[] xyz=new float[1434];
        for(int i=0;i<xyz.length;i+=3) {
            float x=(values[i]/256-.5f)*roi[2],y=(values[i+1]/256-.5f)*roi[2];
            xyz[i]=(roi[0]+cs*x-sn*y)/w; xyz[i+1]=(roi[1]+sn*x+cs*y)/h; xyz[i+2]=values[i+2]/256*roi[2]/w;
            if(!Float.isFinite(xyz[i])||!Float.isFinite(xyz[i+1])||!Float.isFinite(xyz[i+2])) throw new IllegalStateException("Non-finite landmarks");
        }
        roi=nextRoi(xyz,w,h);
        boolean reset=resetPost; resetPost=false;
        return new Raw(xyz,w,h,timestamp,presence,reset,started);
    }
    private void crop(Bitmap image,ByteBuffer target,int size,float cx,float cy,float side,float rotation,float mean,float std) {
        if(referenceCrop) RknnModel.cropReference(image,target,size,cx,cy,side,rotation,mean,std);
        else RknnModel.crop(image,target,size,cx,cy,side,rotation,mean,std);
    }
    private Detection process(Raw raw) {
        long before=SystemClock.elapsedRealtimeNanos();
        if(raw.reset) { post.close(); post=createPost(); }
        float[][] extras=post.process(raw.xyz,raw.width,raw.height,raw.timestamp);
        postNs+=SystemClock.elapsedRealtimeNanos()-before; postCalls++;
        completionNs+=SystemClock.elapsedRealtimeNanos()-raw.startedNs;
        return new Detection(extras[2],extras[0],extras[1],raw.presence);
    }
    private PostProcessor createPost() {
        String metadata=new File(modelDirectory,"geometry_pipeline_metadata_landmarks.binarypb").getPath();
        if(npuExpressions) {
            try { return new CandidatePost(new File(modelDirectory,"face_blendshapes_normalized_suffix.rknn").getPath(),applicationApkPath,metadata,smoothing); }
            catch(Exception error) { throw new IllegalStateException("Cannot initialize experimental expression postprocessor",error); }
        }
        return new CpuPost(new File(modelDirectory,"face_blendshapes.tflite").getPath(),metadata,smoothing);
    }
    private static float[] decodeDetector(float[][] output,int w,int h,float side) {
        float[] boxes=null,scores=null;
        for(float[] row:output) { if(row.length==896*16) boxes=row; if(row.length==896) scores=row; }
        if(boxes==null||scores==null) throw new IllegalStateException("RKNN detector tensor mapping mismatch");
        float[][] candidates=new float[896][]; float best=.5f; int top=-1;
        int index=0;
        for(int group=0;group<2;group++) {
            int grid=group==0?16:8,repeats=group==0?2:6;
            for(int y=0;y<grid;y++) for(int x=0;x<grid;x++) for(int a=0;a<repeats;a++,index++) {
                float score=(float)(1/(1+Math.exp(-Math.max(-100,Math.min(100,scores[index])))));
                if(score<.5f) continue;
                float[] box=new float[9]; box[8]=score;
                float ax=(x+.5f)/grid,ay=(y+.5f)/grid;
                box[0]=(boxes[index*16]/128+ax)*side+(w-side)*.5f;
                box[1]=(boxes[index*16+1]/128+ay)*side+(h-side)*.5f;
                box[2]=boxes[index*16+2]/128*side; box[3]=boxes[index*16+3]/128*side;
                for(int k=0;k<4;k++) box[4+k]=(boxes[index*16+4+k]/128+(k%2==0?ax:ay))*side+((k%2==0?w:h)-side)*.5f;
                candidates[index]=box;
                if(score>best) { best=score; top=index; }
            }
        }
        if(top<0) return null;
        float[] sum=new float[8],chosen=candidates[top]; float weight=0;
        for(float[] box:candidates) if(box!=null&&iou(chosen,box)>.3f) {
            weight+=box[8]; for(int k=0;k<8;k++) sum[k]+=box[k]*box[8];
        }
        for(int k=0;k<8;k++) sum[k]/=weight;
        return new float[]{sum[0],sum[1],Math.max(sum[2],sum[3])*1.5f,(float)Math.atan2(sum[7]-sum[5],sum[6]-sum[4])};
    }
    private static float iou(float[] a,float[] b) {
        float intersection=Math.max(0,Math.min(a[0]+a[2]/2,b[0]+b[2]/2)-Math.max(a[0]-a[2]/2,b[0]-b[2]/2))
                *Math.max(0,Math.min(a[1]+a[3]/2,b[1]+b[3]/2)-Math.max(a[1]-a[3]/2,b[1]-b[3]/2));
        return intersection/Math.max(1e-8f,a[2]*a[3]+b[2]*b[3]-intersection);
    }
    private static float[] nextRoi(float[] xyz,int w,int h) {
        float minX=Float.POSITIVE_INFINITY,minY=minX,maxX=Float.NEGATIVE_INFINITY,maxY=maxX;
        for(int i=0;i<xyz.length;i+=3) { minX=Math.min(minX,xyz[i]*w); maxX=Math.max(maxX,xyz[i]*w); minY=Math.min(minY,xyz[i+1]*h); maxY=Math.max(maxY,xyz[i+1]*h); }
        float rotation=(float)Math.atan2((xyz[263*3+1]-xyz[33*3+1])*h,(xyz[263*3]-xyz[33*3])*w);
        return new float[]{(minX+maxX)*.5f,(minY+maxY)*.5f,Math.max(maxX-minX,maxY-minY)*1.5f,rotation};
    }
    JSONObject summary() throws Exception {
        requireOpen();
        drain();
        return new JSONObject().put("backend",npuExpressions?
                "RKNN detector + 478 landmarks; CPU FP32 normalization + mixed CPU/NPU 52 suffix; CPU canonical pose":
                "RKNN 1.3 direct NPU detector + 478 landmarks; MediaPipe CPU 52 blendshapes + canonical pose")
                .put("expression_backend",npuExpressions?"normalized_rknn_experimental":"mediapipe_cpu")
                .put("expression_post",post.summary())
                .put("crop_implementation",referenceCrop?"scalar_reference":"precomputed_u_row_v_inbounds_unrolled")
                .put("scheduling",pipelined?"NPU image network overlaps previous post; one outstanding post job, immediate ordered callbacks":"Synchronous NPU then post")
                .put("max_pending_cpu_jobs",pipelined?1:0)
                .put("detector",detector.info).put("mesh",mesh.info).put("detector_calls",detections).put("mesh_calls",meshCalls).put("post_calls",postCalls)
                .put("temporal_smoothing",smoothing?"MediaPipe OneEuro: min_cutoff .05, beta 80, derivative cutoff 1; reset after lost face":"Disabled for independent IMAGE comparison")
                .put("crop_total_ms",cropNs/1e6).put("detector_mean_ms",detections==0?0:detectorNs/detections/1e6)
                .put("mesh_mean_ms",meshCalls==0?0:meshNs/meshCalls/1e6).put("post_mean_ms",postCalls==0?0:postNs/postCalls/1e6)
                .put("completed_face_latency_mean_ms",postCalls==0?0:completionNs/postCalls/1e6);
    }
    /** Input-worker-owned. Never release native graphs while the post executor can still use them. */
    @Override public synchronized void close() {
        if(closeRequested){
            if(closeFailure!=null)throw closeError(closeFailure);
            if(!closeSequenceCompleted)throw new IllegalStateException("NPU face pipeline close remains incomplete");
            return;
        }
        closeRequested=true;
        ResourceCleanup cleanup=new ResourceCleanup(null);
        cleanup.close("CPU face result",this::drain);
        var worker=postWorker;
        boolean[] terminated={worker==null};
        if(worker!=null){
            cleanup.close("CPU face executor shutdown",worker::shutdown);
            cleanup.close("CPU face executor termination",()->{
                if(!worker.isShutdown())throw new IllegalStateException("Face post executor shutdown not confirmed");
                awaitPostTermination(worker);
                terminated[0]=worker.isTerminated();
                if(!terminated[0])throw new IllegalStateException("Face post executor termination not confirmed");
            });
        }
        postExecutorTerminated=terminated[0];
        if(!terminated[0]){
            // Keep all native ownership: a live job may still access post/mesh/detector.
            closeFailure=cleanup.failure();
            if(closeFailure==null)closeFailure=new IllegalStateException("Face post executor remains live");
            throw closeError(closeFailure);
        }
        postWorker=null;
        PostProcessor ownedPost=post;RknnModel ownedMesh=mesh,ownedDetector=detector;
        post=null;mesh=null;detector=null;
        cleanup.close("face post graph",ownedPost);cleanup.close("face landmarks model",ownedMesh);
        cleanup.close("face detector model",ownedDetector);
        closeFailure=cleanup.failure();closeSequenceCompleted=true;
        if(closeFailure!=null)throw closeError(closeFailure);
    }
    private static void awaitPostTermination(java.util.concurrent.ExecutorService worker){
        boolean interrupted=false;
        try{
            for(;;)try{if(worker.awaitTermination(1,java.util.concurrent.TimeUnit.SECONDS))return;}
            catch(InterruptedException ignored){interrupted=true;}
        }finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    private static RuntimeException closeError(Throwable failure){
        if(failure instanceof Error)throw (Error)failure;
        return failure instanceof RuntimeException?(RuntimeException)failure:new IllegalStateException(failure);
    }
}
