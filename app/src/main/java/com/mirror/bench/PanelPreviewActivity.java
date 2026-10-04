package com.mirror.bench;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Full physical viewport preview and explicit device-only CPU/GPU pixel check. No camera/NPU ownership. */
public final class PanelPreviewActivity extends Activity {
    private GLSurfaceView surface;
    private TextView status;
    private volatile boolean cancelled;
    private boolean verify, verified;
    private String runId=UUID.randomUUID().toString();
    private long startedNs;
    private int contextGeneration,verifiedWidth,verifiedHeight;
    private int viewCount=20;
    static Intent intent(Context context,PanelCalibration panel) {
        return intent(context,panel,20,false);
    }
    static Intent intent(Context context,PanelCalibration panel,int views,boolean debug) {
        return RuntimeViewCount.forwardConfigured(new Intent(context,PanelPreviewActivity.class).putExtra("pitch",panel.pitch()).putExtra("tan",panel.tan())
                .putExtra("units",panel.pitchUnits().name()).putExtra("phase",panel.phaseCycles())
                .putExtra("order",panel.subpixelOrder().name()).putExtra("reverse",panel.reverseViews()).putExtra("origin",panel.yOrigin().name()),views,debug);
    }
    static String previewLabel(int views) {return views+"视点：01–"+views+"独立数字/颜色；光学未确认";}
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        startedNs=SystemClock.elapsedRealtimeNanos();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                |View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        try {
            Intent i=getIntent();verify=i.getBooleanExtra("verify",false);
            MirrorSettings settings=MirrorSettings.load(this);
            viewCount=RuntimeViewCount.readConfigured(i.getExtras(),
                    (getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0,
                    verify?20:settings.viewCount);
            if(verify)write(new JSONObject().put("running",true).put("passed",false).put("stage","activity_start"));
            PanelCalibration fallback=settings.panel;
            PanelCalibration panel=new PanelCalibration(i.getFloatExtra("pitch",fallback.pitch()),i.getFloatExtra("tan",fallback.tan()),
                    PanelCalibration.PitchUnits.valueOf(i.hasExtra("units")?i.getStringExtra("units"):fallback.pitchUnits().name()),
                    i.getFloatExtra("phase",fallback.phaseCycles()),
                    PanelCalibration.SubpixelOrder.valueOf(i.hasExtra("order")?i.getStringExtra("order"):fallback.subpixelOrder().name()),
                    i.getBooleanExtra("reverse",fallback.reverseViews()),
                    PanelCalibration.YOrigin.valueOf(i.hasExtra("origin")?i.getStringExtra("origin"):fallback.yOrigin().name()));
            FrameLayout root=new FrameLayout(this);
            surface=new GLSurfaceView(this);surface.setEGLContextClientVersion(3);surface.setEGLConfigChooser(8,8,8,8,0,0);
            surface.setRenderer(new GLSurfaceView.Renderer() {
                private InterlaceRenderer renderer;
                public void onSurfaceCreated(GL10 gl,EGLConfig config) {
                    runId=UUID.randomUUID().toString();startedNs=SystemClock.elapsedRealtimeNanos();contextGeneration++;
                    verified=false;verifiedWidth=verifiedHeight=0;
                    // A previous context's successful framebuffer is not evidence for this new context.
                    if(verify)try {write(new JSONObject().put("running",true).put("passed",false).put("stage","context_start"));}
                    catch(Exception failure){report(failure);verified=true;}
                    // Preview owns no camera/native worker. A fresh renderer avoids old latched GL faults/handles.
                    renderer=createRenderer(panel);
                    renderer.onSurfaceCreated(gl,config);
                }
                public void onSurfaceChanged(GL10 gl,int width,int height) {
                    renderer.onSurfaceChanged(gl,width,height);
                    if(verify&&(!verified||verifiedWidth!=width||verifiedHeight!=height)) {
                        verified=true;verifiedWidth=width;verifiedHeight=height;
                        try {
                            write(new JSONObject().put("running",true).put("passed",false).put("stage","pixel_check")
                                    .put("width",width).put("height",height));
                            JSONObject result=renderer.verifyPanelPixels(()->cancelled).put("running",false);
                            if(cancelled)throw new InterruptedException("Pixel check cancelled before publication");
                            write(result); show(result.optBoolean("passed")?viewCount+"视点软件逐像素核对通过；实屏光学仍未确认":"像素核对未通过，详见 "+verificationFile());
                        } catch(Throwable bad) {report(bad);}
                    } else try {
                        String error=renderer.runtimeStatus().optString("error");if(!error.isEmpty())show("预览失败："+error);
                    } catch(Exception bad) {show("预览状态失败："+bad);}
                }
                public void onDrawFrame(GL10 gl) {renderer.onDrawFrame(gl);}
            });
            root.addView(surface,new FrameLayout.LayoutParams(-1,-1));
            LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);controls.setBackgroundColor(0xb0000000);
            status=new TextView(this);status.setText(verify?"正在核对"+viewCount+"视点 × 6组参数 × 4条GPU路径的全部RGB像素…":previewLabel(viewCount));
            status.setTextColor(0xffffffff);controls.addView(status);
            Button back=new Button(this);back.setText("返回校准页");back.setOnClickListener(v->finish());controls.addView(back);
            FrameLayout.LayoutParams controlsLayout=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);root.addView(controls,controlsLayout);setContentView(root);
        } catch(Throwable bad) {status=new TextView(this);setContentView(status);report(bad);}
    }
    @Override protected void onResume() {super.onResume();cancelled=false;if(surface!=null)surface.onResume();}
    @Override protected void onPause() {cancelled=true;if(surface!=null)surface.onPause();super.onPause();}
    private InterlaceRenderer createRenderer(PanelCalibration panel) {
        InterlaceRenderer renderer=new InterlaceRenderer(viewCount,.3f,false);
        renderer.setRuntimeMode(true);renderer.setTargetFps(10);renderer.setViewSize(320,576);
        renderer.setPanelCalibration(panel);renderer.setCalibrationPattern(true);renderer.setExplicitLod(true);
        if(verify){renderer.setAtlas(false,true);renderer.setLookup(false,true);}
        return renderer;
    }
    private void show(String message) {runOnUiThread(()-> {if(status!=null)status.setText(message);});}
    private void report(Throwable bad) {
        show("校验 / 预览错误："+bad);
        if(verify)try {write(new JSONObject().put("running",false).put("passed",false).put("cancelled",cancelled).put("error",bad.toString()));}
        catch(Exception writeError) {show("结果写入失败："+writeError);}
    }
    private void write(JSONObject value) throws Exception {
        value.put("updated_elapsed_ns",SystemClock.elapsedRealtimeNanos()).put("started_elapsed_ns",startedNs)
                .put("run_id",runId).put("context_generation",contextGeneration)
                .put("view_count",viewCount)
                .put("cancelled",cancelled).put("optical_alignment_verified",false);
        if(cancelled&&value.optBoolean("passed"))value.put("passed",false).put("error","Pixel check cancelled before publication");
        AtomicFile file=new AtomicFile(new File(getFilesDir(),verificationFile()));FileOutputStream out=null;
        try {out=file.startWrite();out.write(value.toString(2).getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
        catch(Exception bad) {if(out!=null)file.failWrite(out);throw bad;}
    }
    private String verificationFile() {return viewCount==16?"panel-pixel16-check.json":"panel-pixel-check.json";}
}
