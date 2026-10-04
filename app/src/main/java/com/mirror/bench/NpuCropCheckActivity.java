package com.mirror.bench;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.TextView;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bit-exact scalar/optimized crop comparison and alternating-order device microbenchmark. */
public final class NpuCropCheckActivity extends Activity {
    private record Fixture(String name,Bitmap bitmap) {}
    private record Roi(String name,float cx,float cy,float side,float angle) {}
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); TextView label=new TextView(this); label.setText("NPU crop equivalence and timing"); setContentView(label);
        new Thread(this::check,"NpuCropCheck").start();
    }
    private void check() {
        JSONObject result=new JSONObject(); JSONArray comparisons=new JSONArray(),timings=new JSONArray();
        ArrayList<Fixture> fixtures=new ArrayList<>();
        long comparedBytes=0,mismatchBytes=0,mismatchFloats=0; int cases=0;
        try {
            fixtures.add(new Fixture("deterministic_random_rgba",pattern(false)));
            fixtures.add(new Fixture("half_value_checker",pattern(true)));
            try(var input=getAssets().open("portrait.jpg")) {
                Bitmap decoded=BitmapFactory.decodeStream(input);
                fixtures.add(new Fixture("official_portrait",rgba(decoded)));
            }
            try(MediaMetadataRetriever video=new MediaMetadataRetriever()) {
                File recording=new File(getFilesDir(),"recordings/face-reference-stable-20261001-01.mp4");
                video.setDataSource(recording.getPath());
                for(long time:new long[]{0,12_000_000,24_000_000})
                    fixtures.add(new Fixture("recording_"+(time/1000)+"ms",rgba(video.getFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST))));
                result.put("recording",recording.getName());
            }
            for(int size:new int[]{128,256}) {
                ByteBuffer reference=RknnModel.buffer(size*size*3),optimized=RknnModel.buffer(size*size*3);
                for(Fixture fixture:fixtures) for(Roi roi:rois(fixture.bitmap,size)) for(int norm=0;norm<2;norm++) {
                    float mean=norm==0?0:127.5f,std=norm==0?255:127.5f;
                    crop(false,fixture.bitmap,reference,size,roi,mean,std);
                    crop(true,fixture.bitmap,optimized,size,roi,mean,std);
                    long bytes=0,floats=0; int first=-1; double maxError=0;
                    for(int offset=0;offset<reference.capacity();offset+=4) {
                        int a=reference.getInt(offset),b=optimized.getInt(offset);
                        if(a!=b) {
                            if(first<0) first=offset/4;
                            floats++; int difference=a^b;
                            for(int shift=0;shift<32;shift+=8) if(((difference>>>shift)&255)!=0) bytes++;
                            maxError=Math.max(maxError,Math.abs((double)Float.intBitsToFloat(a)-Float.intBitsToFloat(b)));
                        }
                    }
                    comparedBytes+=reference.capacity(); mismatchBytes+=bytes; mismatchFloats+=floats; cases++;
                    JSONObject row=new JSONObject().put("fixture",fixture.name).put("size",size).put("roi",roi.name)
                            .put("cx",roi.cx).put("cy",roi.cy).put("side",roi.side).put("rotation",roi.angle)
                            .put("mean",mean).put("std",std).put("compared_bytes",reference.capacity())
                            .put("mismatched_float_bits",floats).put("mismatched_bytes",bytes).put("max_absolute_error",maxError);
                    if(first>=0) row.put("first_mismatch_element",first)
                            .put("reference_bits",Integer.toHexString(reference.getInt(first*4)))
                            .put("optimized_bits",Integer.toHexString(optimized.getInt(first*4)));
                    comparisons.put(row);
                }
                // Include both a regular interior face ROI and detector-style letterboxing.
                Bitmap image=fixtures.get(3).bitmap;
                timings.put(benchmark(image,size,new Roi("interior_rotated",image.getWidth()*.5f,image.getHeight()*.5f,
                        Math.min(image.getWidth(),image.getHeight())*.65f,.37f),reference,optimized));
                timings.put(benchmark(image,size,new Roi("full_image_letterbox",image.getWidth()*.5f,image.getHeight()*.5f,
                        Math.max(image.getWidth(),image.getHeight()),0),reference,optimized));
            }
            result.put("passed",mismatchBytes==0).put("status",mismatchBytes==0?"success":"failed");
        } catch(Throwable error) {
            Log.e("MirrorBench","NPU crop check failed",error);
            try { result.put("passed",false).put("status","error").put("error",error.toString()); } catch(Exception ignored) { }
        } finally { for(Fixture fixture:fixtures) fixture.bitmap.recycle(); }
        try {
            result.put("cases",cases).put("compared_bytes",comparedBytes).put("mismatched_float_bits",mismatchFloats)
                    .put("mismatched_bytes",mismatchBytes).put("required_tolerance","bit-exact; no float or pixel tolerance")
                    .put("comparison",comparisons).put("timing",timings).put("timing_scope","JNI validation, bitmap lock, crop, bitmap unlock; no inference")
                    .put("implementation","precomputed identical u coordinates and row v; in-bounds four neighbors unrolled; original accumulation and roundf");
            Files.write(new File(getFilesDir(),"npu-crop-check.json").toPath(),result.toString(2).getBytes(StandardCharsets.UTF_8));
            Log.i("MirrorBench","NPU_CROP_CHECK passed="+result.optBoolean("passed")+" cases="+cases+" mismatched_bytes="+mismatchBytes);
        } catch(Throwable error) { Log.e("MirrorBench","Cannot save crop check",error); }
    }
    private static Bitmap pattern(boolean halfValues) {
        int width=640,height=480; int[] pixels=new int[width*height]; Random random=new Random(0x4d495252L);
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
            // Opaque RGBA prevents Android premultiplication from erasing the half-value fixture.
            int rgb=halfValues?(((x+y)&1)==0?0:0x010101):random.nextInt(0x1000000);
            pixels[y*width+x]=0xff000000|rgb;
        }
        return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);
    }
    private static Bitmap rgba(Bitmap decoded) {
        if(decoded==null) throw new IllegalStateException("Cannot decode a required image fixture");
        if(decoded.getConfig()==Bitmap.Config.ARGB_8888) return decoded;
        Bitmap copy=decoded.copy(Bitmap.Config.ARGB_8888,false); decoded.recycle();
        if(copy==null) throw new IllegalStateException("Cannot create RGBA image fixture");
        return copy;
    }
    private static Roi[] rois(Bitmap image,int size) {
        float w=image.getWidth(),h=image.getHeight(),interior=Math.min(w,h)*.65f,half=size*.5f+.5f;
        return new Roi[]{
                new Roi("full_image",w*.5f,h*.5f,Math.max(w,h),0),
                new Roi("interior",w*.5f,h*.5f,interior,0),
                new Roi("rotate_positive",w*.5f,h*.5f,interior,.37f),
                new Roi("rotate_negative",w*.5f,h*.5f,interior,-.79f),
                new Roi("quarter_turn",w*.5f,h*.5f,interior,(float)(Math.PI*.5)),
                new Roi("half_turn",w*.5f,h*.5f,interior,(float)Math.PI),
                new Roi("negative_corner",-.5f,-.5f,interior,0),
                new Roi("left_rotated",-1.25f,h*.3f,interior,.31f),
                new Roi("right_bottom",w+.5f,h+.5f,interior,-.43f),
                new Roi("fully_outside",-w-64,-h-64,128,.13f),
                new Roi("exact_half_values",half,half,size,0),
                new Roi("above_half_values",Math.nextUp(half),half,size,0),
                new Roi("below_half_values",Math.nextDown(half),half,size,0),
                new Roi("tiny_roi",25.5f,25.5f,1,0),
                new Roi("fractional_roi",w*.51f,h*.4f,93.75f,.123f)};
    }
    private static void crop(boolean optimized,Bitmap image,ByteBuffer output,int size,Roi roi,float mean,float std) {
        if(optimized) RknnModel.cropOptimized(image,output,size,roi.cx,roi.cy,roi.side,roi.angle,mean,std);
        else RknnModel.cropReference(image,output,size,roi.cx,roi.cy,roi.side,roi.angle,mean,std);
    }
    private static JSONObject benchmark(Bitmap image,int size,Roi roi,ByteBuffer reference,ByteBuffer optimized) throws Exception {
        float mean=size==128?127.5f:0,std=size==128?127.5f:255;
        for(int i=0;i<20;i++) { crop(false,image,reference,size,roi,mean,std); crop(true,image,optimized,size,roi,mean,std); }
        double[] oldMs=new double[100],newMs=new double[100];
        for(int i=0;i<100;i++) for(int turn=0;turn<2;turn++) {
            boolean fast=((i+turn)&1)!=0; long start=SystemClock.elapsedRealtimeNanos();
            crop(fast,image,fast?optimized:reference,size,roi,mean,std);
            (fast?newMs:oldMs)[i]=(SystemClock.elapsedRealtimeNanos()-start)/1e6;
        }
        double oldMean=Arrays.stream(oldMs).average().orElseThrow(),newMean=Arrays.stream(newMs).average().orElseThrow();
        JSONObject result=new JSONObject().put("size",size).put("roi",roi.name).put("iterations_per_path",100).put("warmup_pairs",20)
                .put("order","alternating reference/optimized then optimized/reference")
                .put("reference_ms",new JSONArray(oldMs)).put("optimized_ms",new JSONArray(newMs))
                .put("reference_mean_ms",oldMean).put("optimized_mean_ms",newMean).put("mean_speedup",oldMean/newMean);
        Arrays.sort(oldMs); Arrays.sort(newMs);
        return result.put("reference_p50_ms",oldMs[49]).put("optimized_p50_ms",newMs[49])
                .put("reference_p95_ms",oldMs[94]).put("optimized_p95_ms",newMs[94]);
    }
}
