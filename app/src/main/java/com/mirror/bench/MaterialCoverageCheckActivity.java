package com.mirror.bench;

import android.app.Activity;
import android.os.Bundle;
import android.opengl.GLSurfaceView;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Explicit debug diagnostic; reads scene preferences, never starts the camera or changes settings. */
public final class MaterialCoverageCheckActivity extends Activity implements GLSurfaceView.Renderer {
    private GLSurfaceView surface;
    private SceneViewSettings saved;
    private volatile boolean cancelled;
    private String runId;
    private JSONObject latest;
    private boolean finished;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);runId=UUID.randomUUID().toString();
        try {
            if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)==0)
                throw new IllegalStateException("Material coverage requires a debug APK");
            var loaded=SceneViewPreferences.load(this);
            if(!loaded.warning.isEmpty())throw new IllegalStateException("Cannot diagnose an invalid saved scene: "+loaded.warning);
            saved=loaded.value;
            publish(new JSONObject());
            surface=new GLSurfaceView(this);surface.setEGLContextClientVersion(3);
            surface.setEGLConfigChooser(8,8,8,8,16,0);surface.setRenderer(this);
            surface.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);setContentView(surface);
        }catch(Exception failure){publishFailure(failure);finish();}
    }
    @Override protected void onResume(){super.onResume();cancelled=false;if(surface!=null)surface.onResume();}
    @Override protected void onPause(){cancelled=true;if(surface!=null)surface.onPause();super.onPause();}
    @Override public void onSurfaceCreated(GL10 gl,EGLConfig config) {
        runId=UUID.randomUUID().toString();finished=false;publish(new JSONObject());
    }
    @Override public void onSurfaceChanged(GL10 gl,int width,int height) {
        if(finished||cancelled)return;finished=true;
        try{publish(AvatarMaterialCoverageCheck.run(getAssets(),getFilesDir(),saved,()->cancelled,this::publish));}
        catch(Exception failure){publishFailure(failure);}
    }
    @Override public void onDrawFrame(GL10 gl){}
    private void publishFailure(Exception failure) {
        try {
            JSONObject value=latest==null?new JSONObject():latest;
            value.put("running",false).put("passed",false).put("cancelled",cancelled).put("error",failure.toString());publish(value);
        }catch(Exception writeFailure){android.util.Log.e("MaterialCoverage","Cannot publish failure; discard older evidence",writeFailure);}
    }
    private void publish(JSONObject value) {
        FileOutputStream output=null;AtomicFile file=new AtomicFile(new File(getFilesDir(),"material-coverage-check.json"));
        try {
            latest=value;value.put("run_id",runId).put("performance_evidence",false).put("material_cache_qualified",false);
            if(!value.has("running"))value.put("running",true).put("passed",false);
            if(cancelled)value.put("cancelled",true).put("passed",false);
            output=file.startWrite();output.write(value.toString(2).getBytes(StandardCharsets.UTF_8));file.finishWrite(output);
        }catch(Exception failure){if(output!=null)file.failWrite(output);throw new IllegalStateException("Cannot publish current coverage evidence",failure);}
    }
}
