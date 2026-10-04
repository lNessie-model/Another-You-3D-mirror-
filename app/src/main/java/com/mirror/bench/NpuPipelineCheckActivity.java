package com.mirror.bench;

import android.app.Activity;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import org.json.JSONObject;

/** Same ordered frames must retain all outputs when NPU and CPU stages overlap. */
public final class NpuPipelineCheckActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); TextView label=new TextView(this); label.setText("NPU pipeline regression check"); setContentView(label);
        new Thread(this::check,"NpuPipelineCheck").start();
    }
    private void check() {
        JSONObject result=new JSONObject();
        try(MediaMetadataRetriever video=new MediaMetadataRetriever();
                NpuFacePipeline serial=new NpuFacePipeline(this); NpuFacePipeline parallel=new NpuFacePipeline(this)) {
            video.setDataSource(new File(getFilesDir(),"recordings/face-reference-stable-20261001-01.mp4").getPath());
            ArrayList<NpuFacePipeline.Detection> expected=new ArrayList<>(),actual=new ArrayList<>();
            for(int i=0;i<24;i++) {
                Bitmap bitmap;
                if(i==8||i==17) {
                    bitmap=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888); bitmap.eraseColor(android.graphics.Color.BLACK);
                } else {
                    Bitmap decoded=video.getFrameAtTime(i*100_000L,MediaMetadataRetriever.OPTION_CLOSEST);
                    bitmap=decoded.copy(Bitmap.Config.ARGB_8888,false); decoded.recycle();
                }
                try {
                    expected.add(serial.detect(bitmap,100+i*100,true));
                    parallel.submit(bitmap,100+i*100,true,actual::add);
                } finally { bitmap.recycle(); }
            }
            parallel.drain();
            if(actual.size()!=24) throw new IllegalStateException("Lost pipeline callbacks: "+actual.size());
            double landmarkError=0,blendError=0,poseError=0; int complete=0;
            for(int i=0;i<24;i++) {
                var a=expected.get(i); var b=actual.get(i);
                if((a==null)!=(b==null)) throw new IllegalStateException("Face order/presence mismatch at "+i);
                if(a==null) continue;
                complete++;
                landmarkError=Math.max(landmarkError,maxDelta(a.landmarks(),b.landmarks(),1434));
                blendError=Math.max(blendError,maxDelta(a.blendshapes(),b.blendshapes(),52));
                poseError=Math.max(poseError,maxDelta(a.pose(),b.pose(),16));
            }
            if(actual.get(8)!=null||actual.get(17)!=null||actual.get(9)==null||actual.get(18)==null)
                throw new IllegalStateException("Pipelined no-face/reacquisition failed");
            if(landmarkError>1e-5||blendError>1e-5||poseError>1e-5)
                throw new IllegalStateException("Temporal pipeline changed model outputs");
            if(parallel.summary().getInt("post_calls")!=complete) throw new IllegalStateException("Postprocessing count mismatch");
            result.put("status","success").put("frames",24).put("complete",complete).put("ordered_callbacks",true)
                    .put("blank_and_recovery",true).put("landmark_max_absolute_error",landmarkError)
                    .put("blendshape_max_absolute_error",blendError).put("pose_max_absolute_error",poseError)
                    .put("serial",serial.summary()).put("parallel",parallel.summary());
        } catch(Throwable error) {
            Log.e("MirrorBench","NPU pipeline check failed",error);
            try { result.put("status","error").put("error",error.toString()); } catch(Exception ignored) {}
        }
        try { Files.write(new File(getFilesDir(),"npu-pipeline-check.json").toPath(),result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch(Exception error) { Log.e("MirrorBench","Save NPU pipeline check failed",error); }
    }
    private static double maxDelta(float[] a,float[] b,int length) {
        if(a.length!=length||b.length!=length) throw new IllegalStateException("Incomplete output array");
        double maximum=0;
        for(int i=0;i<length;i++) {
            if(!Float.isFinite(a[i])||!Float.isFinite(b[i])) throw new IllegalStateException("Nonfinite output");
            maximum=Math.max(maximum,Math.abs(a[i]-b[i]));
        }
        return maximum;
    }
}
