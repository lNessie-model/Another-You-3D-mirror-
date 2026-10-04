package com.mirror.bench;

import android.app.Activity;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;
import org.json.JSONArray;

/** Independent IMAGE results on identical decoded frames, plus exact-input tensor fixtures. */
public final class NpuQualityActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); TextView label=new TextView(this); label.setText("NPU face accuracy check"); setContentView(label);
        new Thread(this::check,"NpuQuality").start();
    }
    private void check() {
        JSONObject result=new JSONObject(); JSONArray samples=new JSONArray();
        File root=new File(getFilesDir(),"npu-quality"); root.mkdirs();
        try(MediaMetadataRetriever video=new MediaMetadataRetriever(); NpuFacePipeline npu=new NpuFacePipeline(this,false);
                FaceLandmarker reference=FaceLandmarker.createFromOptions(this,FaceLandmarker.FaceLandmarkerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").setDelegate(Delegate.CPU).build())
                        .setRunningMode(RunningMode.IMAGE).setNumFaces(1).setOutputFaceBlendshapes(true).setOutputFacialTransformationMatrixes(true).build())) {
            video.setDataSource(new File(getFilesDir(),"recordings/face-reference-stable-20261001-01.mp4").getPath());
            long duration=Long.parseLong(video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            Bitmap blank=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888);
            blank.eraseColor(android.graphics.Color.BLACK);
            boolean blankRejected=npu.detect(blank,1,false)==null;
            Bitmap first=video.getFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST).copy(Bitmap.Config.ARGB_8888,false);
            boolean firstFound=npu.detect(first,2,false)!=null;
            boolean trackedBlankRejected=npu.detect(blank,3,true)==null;
            boolean recovered=npu.detect(first,4,true)!=null;
            blank.recycle(); first.recycle();
            result.put("blank_rejected",blankRejected).put("tracked_blank_rejected",trackedBlankRejected).put("recovered_after_blank",recovered);
            if(!blankRejected||!firstFound||!trackedBlankRejected||!recovered) throw new IllegalStateException("No-face/reacquisition regression failed");
            int complete=0;
            for(long time=0;time<duration;time+=1000) {
                Bitmap decoded=video.getFrameAtTime(time*1000,MediaMetadataRetriever.OPTION_CLOSEST);
                if(decoded==null) throw new IllegalStateException("Missing reference frame");
                Bitmap bitmap=decoded.getConfig()==Bitmap.Config.ARGB_8888?decoded:decoded.copy(Bitmap.Config.ARGB_8888,false);
                if(bitmap!=decoded) decoded.recycle();
                try(var image=new BitmapImageBuilder(bitmap).build()) {
                    npu.dumpTo(time%12000==0?new File(root,"frame-"+time):null);
                    var candidate=npu.detect(bitmap,time+100,false); var expected=reference.detect(image);
                    JSONObject row=new JSONObject().put("time_ms",time).put("npu_face",candidate!=null).put("reference_face",!expected.faceLandmarks().isEmpty());
                    if(candidate!=null&&!expected.faceLandmarks().isEmpty()) {
                        complete++; double xySum=0,xyMax=0,zSum=0,shapeSum=0,shapeMax=0;
                        JSONArray npuLm=new JSONArray(),refLm=new JSONArray(),npuShapes=new JSONArray(),refShapes=new JSONArray();
                        var points=expected.faceLandmarks().get(0);
                        for(int i=0;i<478;i++) {
                            double dx=(candidate.landmarks()[i*3]-points.get(i).x())*bitmap.getWidth();
                            double dy=(candidate.landmarks()[i*3+1]-points.get(i).y())*bitmap.getHeight();
                            xySum+=dx*dx+dy*dy; xyMax=Math.max(xyMax,Math.hypot(dx,dy));
                            double dz=(candidate.landmarks()[i*3+2]-points.get(i).z())*bitmap.getWidth(); zSum+=dz*dz;
                            npuLm.put(candidate.landmarks()[i*3]).put(candidate.landmarks()[i*3+1]).put(candidate.landmarks()[i*3+2]);
                            refLm.put(points.get(i).x()).put(points.get(i).y()).put(points.get(i).z());
                        }
                        var shapes=expected.faceBlendshapes().get().get(0);
                        for(int i=0;i<52;i++) { double delta=Math.abs(candidate.blendshapes()[i]-shapes.get(i).score()); shapeSum+=delta; shapeMax=Math.max(shapeMax,delta); npuShapes.put(candidate.blendshapes()[i]); refShapes.put(shapes.get(i).score()); }
                        row.put("xy_rmse_pixels",Math.sqrt(xySum/478)).put("xy_max_pixels",xyMax).put("z_rmse_pixels",Math.sqrt(zSum/478))
                                .put("blendshape_mae",shapeSum/52).put("blendshape_max_error",shapeMax).put("landmarks",478).put("blendshapes",52)
                                .put("presence",candidate.presence()).put("pose_elements",candidate.pose().length)
                                .put("npu_landmarks",npuLm).put("reference_landmarks",refLm).put("npu_blendshapes",npuShapes).put("reference_blendshapes",refShapes);
                    }
                    samples.put(row);
                } finally { if(!bitmap.isRecycled()) bitmap.recycle(); }
            }
            result.put("status","success").put("complete_samples",complete).put("samples",samples).put("pipeline",npu.summary())
                    .put("scope","Same MP4 decoded frames, independent IMAGE mode without temporal smoothing; not a throughput test");
        } catch(Throwable error) { Log.e("MirrorBench","NPU quality check failed",error); try { result.put("status","error").put("error",error.toString()); } catch(Exception ignored) {} }
        try { Files.write(new File(root,"quality.json").toPath(),result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8)); Log.i("MirrorBench","NPU_QUALITY "+result.optString("status")); }
        catch(Exception error) { Log.e("MirrorBench","Save NPU quality failed",error); }
    }
}
