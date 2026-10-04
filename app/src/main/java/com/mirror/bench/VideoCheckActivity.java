package com.mirror.bench;

import android.app.Activity;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
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
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Check that a recorded clip decodes on the target and exercises all face outputs. */
public final class VideoCheckActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        TextView label=new TextView(this); label.setText("Checking recorded face video"); setContentView(label);
        new Thread(()->check(label),"VideoCheck").start();
    }
    private void check(TextView label) {
        String name=getIntent().getStringExtra("record_id");
        if(name==null||!name.matches("[a-zA-Z0-9_-]+")) name="invalid";
        File dir=new File(getFilesDir(),"recordings");
        JSONObject result=new JSONObject(); JSONArray samples=new JSONArray();
        try(MediaMetadataRetriever video=new MediaMetadataRetriever();
            FaceLandmarker face=FaceLandmarker.createFromOptions(this,FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").setDelegate(Delegate.GPU).build())
                    .setRunningMode(RunningMode.VIDEO).setNumFaces(1).setOutputFaceBlendshapes(true)
                    .setOutputFacialTransformationMatrixes(true).build())) {
            video.setDataSource(new File(dir,name+".mp4").getAbsolutePath());
            long duration=Long.parseLong(video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            if(duration<1||duration>120001) throw new IllegalArgumentException("Expected a clip of at most 120 seconds");
            int decoded=0,faces=0,complete=0;
            JSONObject ranges=new JSONObject();
            for(long time=0;time<duration;time+=1000) {
                Bitmap bitmap=video.getFrameAtTime(time*1000,MediaMetadataRetriever.OPTION_CLOSEST);
                if(bitmap==null) throw new IllegalStateException("Cannot decode frame at "+time);
                if(bitmap.getConfig()!=Bitmap.Config.ARGB_8888) {
                    Bitmap rgba=bitmap.copy(Bitmap.Config.ARGB_8888,false);
                    bitmap.recycle(); bitmap=rgba;
                    if(bitmap==null) throw new IllegalStateException("Cannot convert decoded frame to RGBA");
                }
                try(MPImage image=new BitmapImageBuilder(bitmap).build()) {
                    FaceLandmarkerResult found=face.detectForVideo(image,time);
                    decoded++; int landmarks=found.faceLandmarks().isEmpty()?0:found.faceLandmarks().get(0).size();
                    int shapes=0;
                    if(landmarks>0) {
                        faces++;
                        if(found.faceBlendshapes().isPresent()) {
                            shapes=found.faceBlendshapes().get().get(0).size();
                            for(var category:found.faceBlendshapes().get().get(0)) {
                                String key=category.categoryName(); double score=category.score();
                                JSONObject range=ranges.optJSONObject(key);
                                if(range==null) { range=new JSONObject().put("min",score).put("max",score); ranges.put(key,range); }
                                range.put("min",Math.min(score,range.getDouble("min"))).put("max",Math.max(score,range.getDouble("max")));
                            }
                        }
                    }
                    if(landmarks==478&&shapes==52) complete++;
                    samples.put(new JSONObject().put("time_ms",time).put("landmarks",landmarks).put("blendshapes",shapes));
                } finally { if(!bitmap.isRecycled()) bitmap.recycle(); }
            }
            result.put("status","success").put("duration_ms",duration).put("decoded_samples",decoded)
                    .put("face_samples",faces).put("complete_face_samples",complete).put("face_fraction",faces/(double)decoded)
                    .put("sample_step_ms",1000).put("samples",samples).put("expression_ranges",ranges)
                    .put("purpose","Decode and complete-face coverage check only; not a throughput benchmark");
        } catch(Throwable error) {
            Log.e("MirrorBench","Video check failed",error);
            try { result.put("status","error").put("error",error.toString()); } catch(Exception ignored) {}
        }
        try {
            result.put("record_id",name);
            Files.write(new File(dir,name+"-face-check.json").toPath(),result.toString(2).getBytes(StandardCharsets.UTF_8));
            Log.i("MirrorBench","VIDEO_CHECK "+result);
            String status=result.optString("status"); runOnUiThread(()->label.setText("Video check: "+status));
        } catch(Exception error) { Log.e("MirrorBench","Save video check failed",error); }
    }
}
