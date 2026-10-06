package com.mirror.bench;

import android.app.Activity;
import android.os.Bundle;
import android.opengl.GLSurfaceView;
import android.util.AtomicFile;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Debug-only blocking pixel check; never touches role selection, calibration or runtime preferences. */
public final class EmptyInterlaceCheckActivity extends Activity implements GLSurfaceView.Renderer {
    private GLSurfaceView surface;
    private InterlaceRenderer renderer;
    private volatile boolean cancelled;
    private String runId,roleId;
    private JSONObject latest;
    private boolean finished;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        try{
            if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)==0)
                throw new IllegalStateException("Pixel diagnostics require a debug APK");
            roleId=getIntent().getStringExtra("role_id");
            var entry=BundledAvatarCatalog.find(BundledAvatarCatalog.read(getAssets()),roleId);
            renderer=new InterlaceRenderer(16,1,true);renderer.setRuntimeMode(true);
            renderer.setBundledRuntimeAvatar(getAssets(),entry.directory,entry.id,entry.modelSha256,entry.manifestSha256,true);
            renderer.setAvatarAsynchronous(false);renderer.setAvatarBatched(true);renderer.setMultiview(true,false);
            renderer.setPersistentMultiviewFbos(true);renderer.setCachedCameraVp(true);renderer.setEmptyInterlace(true);
            renderer.setViewSize(400,640);renderer.setPanelCalibration(new PanelCalibration(10,.2777777f,
                PanelCalibration.PitchUnits.SUBPIXELS,0,PanelCalibration.SubpixelOrder.RGB,false,PanelCalibration.YOrigin.BOTTOM));
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            surface=new GLSurfaceView(this);surface.setEGLContextClientVersion(3);surface.setEGLConfigChooser(8,8,8,8,16,0);
            surface.setRenderer(this);surface.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);setContentView(surface);
        }catch(Exception failure){runId=UUID.randomUUID().toString();publishFailure(failure);finish();}
    }
    @Override protected void onResume(){super.onResume();cancelled=false;if(surface!=null)surface.onResume();}
    @Override protected void onPause(){cancelled=true;if(surface!=null)surface.onPause();super.onPause();}
    @Override protected void onDestroy(){if(renderer!=null)renderer.closeRuntimeAvatar();super.onDestroy();}
    @Override public void onSurfaceCreated(GL10 gl,EGLConfig config){
        runId=UUID.randomUUID().toString();finished=false;
        publish(new JSONObject());renderer.onSurfaceCreated(gl,config);
    }
    @Override public void onSurfaceChanged(GL10 gl,int width,int height){
        renderer.onSurfaceChanged(gl,width,height);if(finished||cancelled)return;
        try{latest=renderer.verifyEmptyInterlace(()->cancelled,this::publish);finished=true;publish(latest);}
        catch(Exception failure){finished=true;publishFailure(failure);}
    }
    @Override public void onDrawFrame(GL10 gl){} // verification already drew/read the actual window framebuffer
    private void publishFailure(Exception failure){
        try{JSONObject value=latest==null?new JSONObject():latest;
            value.put("running",false).put("passed",false).put("cancelled",cancelled).put("error",failure.toString());publish(value);
        }catch(Exception writeFailure){android.util.Log.e("EmptyInterlace","Cannot publish failure; discard older evidence",writeFailure);}
    }
    private void publish(JSONObject value){
        FileOutputStream output=null;AtomicFile file=new AtomicFile(new File(getFilesDir(),"empty-interlace-check.json"));
        try{
            latest=value;value.put("run_id",runId).put("role_id",roleId).put("performance_evidence",false);
            if(!value.has("running"))value.put("running",true).put("passed",false);
            output=file.startWrite();output.write(value.toString(2).getBytes(StandardCharsets.UTF_8));file.finishWrite(output);
        }catch(Exception failure){if(output!=null)file.failWrite(output);throw new IllegalStateException("Cannot publish current pixel evidence",failure);}
    }
}
