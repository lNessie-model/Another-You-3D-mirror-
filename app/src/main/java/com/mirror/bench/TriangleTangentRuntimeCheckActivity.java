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

/** Finite async-producer/runtime-owner debug pixel gate. No camera, inference or preference mutation. */
public final class TriangleTangentRuntimeCheckActivity extends Activity implements GLSurfaceView.Renderer {
    private GLSurfaceView surface;
    private SceneViewSettings settings;
    private PanelCalibration panel;
    private volatile boolean cancelled;
    private boolean full,finished;
    private String runId=UUID.randomUUID().toString();
    static boolean parseFull(Bundle extras,boolean debug){
        if(!debug)throw new IllegalStateException("Triangle tangent requires debug APK");
        if(extras==null||!extras.containsKey("full"))return false;
        Object value=extras.get("full");if(!(value instanceof Boolean))throw new IllegalArgumentException("full must be a non-null Boolean");return (Boolean)value;
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        try{
            full=parseFull(getIntent().getExtras(),(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0);
            MirrorSettings loaded=MirrorSettings.load(this);var scene=SceneViewPreferences.load(this);
            if(!loaded.warning.isEmpty()||!scene.warning.isEmpty())throw new IllegalStateException("Invalid current panel/scene settings");
            panel=loaded.panel;settings=scene.value;publish(new JSONObject());
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            surface=new GLSurfaceView(this);surface.setEGLContextClientVersion(3);surface.setEGLConfigChooser(8,8,8,8,16,0);
            surface.setRenderer(this);surface.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);setContentView(surface);
        }catch(Throwable failure){fail(failure);finish();}
    }
    @Override protected void onResume(){super.onResume();cancelled=false;if(surface!=null)surface.onResume();}
    @Override protected void onPause(){cancelled=true;if(surface!=null)surface.onPause();super.onPause();}
    @Override protected void onDestroy(){cancelled=true;super.onDestroy();}
    @Override public void onSurfaceCreated(GL10 gl,EGLConfig config){runId=UUID.randomUUID().toString();finished=false;publish(new JSONObject());}
    @Override public void onSurfaceChanged(GL10 gl,int width,int height){
        if(finished||cancelled)return;finished=true;
        try{publish(TriangleTangentRuntimeCheck.run(getAssets(),getFilesDir(),width,height,panel,settings,full,()->cancelled,this::publish));}
        catch(Throwable failure){fail(failure);}
    }
    @Override public void onDrawFrame(GL10 gl){}
    private void fail(Throwable failure){try{publish(new JSONObject().put("running",false).put("completed",false).put("passed",false).put("error",failure.toString()).put("cancelled",cancelled));}catch(Throwable writeFailure){android.util.Log.e("TriangleTangent","Cannot publish failure; discard prior evidence",writeFailure);}}
    private void publish(JSONObject report){
        AtomicFile file=new AtomicFile(new File(getFilesDir(),full?"avatar-triangle-tangent-runtime-full.json":"avatar-triangle-tangent-runtime-smoke.json"));FileOutputStream out=null;
        try{
            report.put("run_id",runId).put("full_requested",full).put("performance_evidence",false);
            if(!report.has("running"))report.put("running",true).put("passed",false);
            if(cancelled)report.put("cancelled",true).put("passed",false);
            out=file.startWrite();out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));file.finishWrite(out);
        }catch(Exception failure){if(out!=null)file.failWrite(out);throw new IllegalStateException("Cannot publish tangent gate",failure);}
    }
}
