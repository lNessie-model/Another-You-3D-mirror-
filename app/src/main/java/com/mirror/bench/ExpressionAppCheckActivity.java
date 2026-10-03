package com.mirror.bench;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/** Device gates for Android FP32 front/JNI and the ordered, smoothed complete face pipeline. No FPS claim. */
public final class ExpressionAppCheckActivity extends Activity {
    private volatile boolean cancelled;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label=new TextView(this);label.setText("Expression integration validation");setContentView(label);
        if((getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)==0){label.setText("Debug validation unavailable");return;}
        new Thread(this::check,"ExpressionAppCheck").start();
    }
    @Override public void onDestroy(){cancelled=true;super.onDestroy();}
    private void requireRunning(){if(cancelled)throw new IllegalStateException("Validation cancelled");}
    private void check() {
        final long startedElapsedNs=SystemClock.elapsedRealtimeNanos();
        final String runId=java.util.UUID.randomUUID().toString();
        JSONObject result=new JSONObject();JSONArray cases=new JSONArray(),frames=new JSONArray();
        try {
            result.put("run_uuid",runId).put("check_started_elapsed_ns",startedElapsedNs);
            float[] raw=floats("raw-inputs.f32",107456,"36aea412c6765dfbe7222db47253e2e3c734ef6366a2578c0ede282fb4cc13fc");
            float[] normalized=floats("normalized-inputs.f32",107456,"ff9ffb0b905d58966fb2c0a19f3406c8a4a7250e285837d2c7f9376c36357c8b");
            float[] references=floats("original-tflite52.f32",19136,"5945bbecedd7ee827a75091b81e12a84a88ca8f05807b034a52f528c9b917602");
            File model=new File(getFilesDir(),"expression-app-check-model.rknn");
            try(var in=getAssets().open("face_blendshapes_normalized_suffix.rknn")){
                Files.copy(in,model.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            double max=0,sum=0,frontMax=0;int unequalBits=0;
            ByteBuffer input=RknnExpression.buffer();
            try(RknnExpression expression=new RknnExpression(model.getPath(),getApplicationInfo().sourceDir)) {
                result.put("native",new JSONObject(expression.info()));
                for(int i=-5;i<92;i++) {
                    requireRunning();int index=Math.max(0,i);
                    float[] pixels=java.util.Arrays.copyOfRange(raw,index*292,(index+1)*292);
                    var vector=NormalizedBlendshapeInput.normalizePixels(pixels);
                    input.clear();for(int j=0;j<292;j++)input.putFloat(vector.get(j));input.flip();
                    long before=SystemClock.elapsedRealtimeNanos();float[] output=expression.run(input);
                    double elapsed=(SystemClock.elapsedRealtimeNanos()-before)/1e6;
                    if(i<0)continue;
                    double rowMax=0,rowSum=0,rowFront=0;JSONArray values=new JSONArray();
                    for(int j=0;j<292;j++) {
                        rowFront=Math.max(rowFront,Math.abs((double)vector.get(j)-normalized[index*292+j]));
                        if(Float.floatToIntBits(vector.get(j))!=Float.floatToIntBits(normalized[index*292+j]))unequalBits++;
                    }
                    for(int j=0;j<52;j++) {
                        double delta=Math.abs((double)output[j]-references[index*52+j]);
                        rowSum+=delta;rowMax=Math.max(rowMax,delta);values.put(output[j]);
                    }
                    max=Math.max(max,rowMax);sum+=rowSum;frontMax=Math.max(frontMax,rowFront);
                    cases.put(new JSONObject().put("index",index).put("group",index<72?"real":"synthetic")
                        .put("front_max_absolute_error",rowFront).put("max_absolute_error",rowMax)
                        .put("mean_absolute_error",rowSum/52).put("jni_wall_ms",elapsed)
                        .put("normalized_input",array(vector.toArray())).put("outputs",values));
                }
            }
            result.put("cases",cases).put("completed_cases",cases.length()).put("warmup",5)
                .put("front_max_absolute_error",frontMax).put("front_unequal_float_bits",unequalBits)
                .put("max_absolute_error",max).put("mean_absolute_error",sum/(92*52))
                .put("model_sha256",RknnExpression.MODEL_SHA256).put("runtime_sha256",RknnExpression.RUNTIME_SHA256);
            if(cases.length()!=92||frontMax>1e-5||max>.01||sum/(92*52)>.002)
                throw new IllegalStateException("Original front/TF52 numerical gate failed");
            pipeline(frames,result);
            requireRunning();
            result.put("status","success").put("scope","Android CPU FP32 front + actual app JNI on fixed original 92 cases; "
                +"24 identical decoded frames comparing smoothed CPU reference with ordered mixed CPU/NPU post callbacks; "
                +"not camera capture, rendering, presentation FPS or arbitrary-face accuracy");
        } catch(Throwable error) {
            Log.e("MirrorBench","Expression application validation failed",error);
            try {result.put("status","error").put("error",error.toString()).put("cases",cases).put("frames",frames);}catch(Exception ignored){}
        }
        try {
            result.put("run_uuid",runId).put("check_started_elapsed_ns",startedElapsedNs)
                .put("check_completed_elapsed_ns",SystemClock.elapsedRealtimeNanos());
            Files.write(new File(getFilesDir(),"expression-app-check.json").toPath(),result.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        catch(Exception error){Log.e("MirrorBench","Expression validation report save failed",error);}
    }
    private void pipeline(JSONArray rows,JSONObject result)throws Exception {
        try(MediaMetadataRetriever video=new MediaMetadataRetriever();
            NpuFacePipeline reference=new NpuFacePipeline(this,true,false);
            NpuFacePipeline candidate=new NpuFacePipeline(this,true,true)) {
            video.setDataSource(new File(getFilesDir(),"recordings/face-reference-stable-20261001-01.mp4").getPath());
            ArrayList<NpuFacePipeline.Detection> expected=new ArrayList<>(),actual=new ArrayList<>();
            for(int i=0;i<24;i++) {
                requireRunning();Bitmap image;
                if(i==8||i==17){image=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888);image.eraseColor(android.graphics.Color.BLACK);}
                else {
                    Bitmap decoded=video.getFrameAtTime(i*100_000L,MediaMetadataRetriever.OPTION_CLOSEST);
                    if(decoded==null)throw new IllegalStateException("Missing recorded comparison frame");
                    try{image=decoded.copy(Bitmap.Config.ARGB_8888,false);}finally{decoded.recycle();}
                }
                try{expected.add(reference.detect(image,100+i*100,true));candidate.submit(image,100+i*100,true,actual::add);}
                finally{image.recycle();}
            }
            candidate.drain();
            if(actual.size()!=24)throw new IllegalStateException("Lost or unordered expression callbacks");
            double shapeMax=0,shapeSum=0,landmarkMax=0,poseMax=0;int complete=0;
            for(int i=0;i<24;i++) {
                var a=expected.get(i);var b=actual.get(i);
                if((a==null)!=(b==null))throw new IllegalStateException("Face presence changed at "+i);
                JSONObject row=new JSONObject().put("index",i).put("face",a!=null);rows.put(row);
                if(a==null)continue;
                complete++;double max=delta(a.blendshapes(),b.blendshapes(),52);
                landmarkMax=Math.max(landmarkMax,delta(a.landmarks(),b.landmarks(),1434));
                poseMax=Math.max(poseMax,delta(a.pose(),b.pose(),16));shapeMax=Math.max(shapeMax,max);
                for(int j=0;j<52;j++)shapeSum+=Math.abs((double)a.blendshapes()[j]-b.blendshapes()[j]);
                row.put("cpu52",array(a.blendshapes())).put("candidate52",array(b.blendshapes()))
                    .put("cpu_landmarks",array(a.landmarks())).put("candidate_landmarks",array(b.landmarks()))
                    .put("cpu_pose",array(a.pose())).put("candidate_pose",array(b.pose()));
            }
            result.put("frames",rows).put("pipeline_complete_frames",complete).put("pipeline_landmark_max_error",landmarkMax)
                .put("pipeline_pose_max_error",poseMax).put("pipeline_shape_max_error",shapeMax)
                .put("pipeline_shape_mean_error",complete==0?JSONObject.NULL:shapeSum/(complete*52))
                .put("reference_pipeline",reference.summary()).put("candidate_pipeline",candidate.summary());
            if(complete==0||actual.get(8)!=null||actual.get(17)!=null||actual.get(9)==null||actual.get(18)==null
                ||landmarkMax>1e-5||poseMax>1e-5||shapeMax>.01||shapeSum/(complete*52)>.002)
                throw new IllegalStateException("Smoothed full pipeline precision/presence/recovery gate failed");
            if(candidate.summary().getInt("post_calls")!=complete)throw new IllegalStateException("Expression post count mismatch");
        }
    }
    private float[] floats(String name,int bytes,String expectedHash)throws Exception {
        ByteArrayOutputStream result=new ByteArrayOutputStream(bytes);
        try(var input=getAssets().open("expression-validation/"+name)){
            byte[] buffer=new byte[16384];int n;
            while((n=input.read(buffer))!=-1){if(result.size()+n>bytes)throw new IllegalStateException("Fixture size overflow");result.write(buffer,0,n);}
        }
        byte[] data=result.toByteArray();if(data.length!=bytes)throw new IllegalStateException("Incomplete original fixture");
        StringBuilder hash=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(data))hash.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        if(!expectedHash.contentEquals(hash))throw new IllegalStateException("Original fixture SHA256 changed");
        float[] values=new float[bytes/4];ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values);
        for(float value:values)if(!Float.isFinite(value))throw new IllegalStateException("Nonfinite fixture");return values;
    }
    private static JSONArray array(float[] values)throws Exception{JSONArray out=new JSONArray();for(float v:values)out.put(v);return out;}
    private static double delta(float[] a,float[] b,int length) {
        if(a.length!=length||b.length!=length)throw new IllegalStateException("Incomplete full pipeline output");
        double max=0;for(int i=0;i<length;i++){if(!Float.isFinite(a[i])||!Float.isFinite(b[i]))throw new IllegalStateException("Nonfinite comparison");max=Math.max(max,Math.abs((double)a[i]-b[i]));}return max;
    }
}
