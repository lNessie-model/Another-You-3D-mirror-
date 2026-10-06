package com.mirror.bench;

import android.app.Activity;
import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.view.View;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Deterministic device GL renders of the actual loader/rig/deformer/material path; no face images. */
public final class AvatarPreviewActivity extends Activity implements GLSurfaceView.Renderer {
    private static final int W=480,H=768;
    private GLSurfaceView surface;
    private AvatarGpuScene scene;
    private int width,height;
    private volatile boolean cancelled;
    private final float[] view=new float[16],projection=new float[16],vp=new float[16];
    private String error="";
    private String runId="";
    private long startedNs;
    private int contextGeneration;
    private boolean verifyMultiview,verifyBatch,verifyPersistentFbos,verifyCachedCameraVp,verifyMultiview16;
    private boolean privateHead;
    private boolean verifyPrivateHead;
    private boolean verifyBackgroundCache;
    private boolean verifyOrmRg8,ormVerificationComplete,verifyPbrFastMath,pbrVerificationComplete;
    private int backgroundVerificationViews=20;
    private int verificationViewCount=20;
    private int verificationViewHeight=720;
    private boolean verificationConfigurationError;
    private TextView status;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        privateHead=(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0&&getIntent().getBooleanExtra("test_private_head",false);
        verifyPrivateHead=privateHead&&getIntent().getBooleanExtra("verify_private_head",false);
        verifyMultiview=(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0
                &&getIntent().getBooleanExtra("verify_multiview",false);
        verifyBatch=(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0
                &&getIntent().getBooleanExtra("verify_batch",false);
        verifyPersistentFbos=(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0
                &&getIntent().getBooleanExtra("verify_persistent_fbos",false);
        verifyCachedCameraVp=(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0
                &&getIntent().getBooleanExtra("verify_cached_camera_vp",false);
        verifyMultiview16=(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0
                &&getIntent().getBooleanExtra("verify_multiview_16",false);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        surface=new GLSurfaceView(this);surface.setEGLContextClientVersion(3);surface.setEGLConfigChooser(8,8,8,8,16,0);
        surface.setRenderer(this);surface.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        FrameLayout root=new FrameLayout(this);root.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);controls.setBackgroundColor(0xb0000000);
        status=new TextView(this);status.setText("准备生成角色动作截图；图像与表情效果仍需人工验收");status.setTextColor(0xffffffff);controls.addView(status);
        Button back=new Button(this);back.setText("退出角色预览");back.setOnClickListener(v->finish());controls.addView(back);
        root.addView(controls,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));setContentView(root);
    }
    @Override protected void onResume(){super.onResume();cancelled=false;surface.onResume();}
    @Override protected void onPause(){cancelled=true;surface.onPause();super.onPause();}
    @Override public void onSurfaceCreated(GL10 gl,EGLConfig config) {
        // Old GL names belong to the destroyed context; do not dispose them in this new context.
        scene=null;error="";ormVerificationComplete=false;pbrVerificationComplete=false;runId=UUID.randomUUID().toString();startedNs=SystemClock.elapsedRealtimeNanos();contextGeneration++;
        try {
            readVerificationConfiguration(getIntent().getExtras(),(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0);
            verifyBackgroundCache=readBackgroundVerification(getIntent().getExtras(),(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0,privateHead);
            verifyPbrFastMath=readPbrVerification(getIntent().getExtras(),(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0,
                    privateHead||verifyPrivateHead||verifyBackgroundCache||verifyMultiview||verifyBatch||verifyPersistentFbos||verifyCachedCameraVp||verifyMultiview16);
            verifyOrmRg8=readOrmVerification(getIntent().getExtras(),(getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0,
                    privateHead||verifyPrivateHead||verifyBackgroundCache||verifyMultiview||verifyBatch||verifyPersistentFbos||verifyCachedCameraVp||verifyMultiview16||verifyPbrFastMath);
            if(verifyPbrFastMath){
                writeArtifact("avatar-pbr-fast-math-check.json",new JSONObject().put("running",true).put("passed",false).put("performance_evidence",false));
                show("正在比较完整角色的着色计算；帧率另行实测，可退出取消");
                return;
            }
            if(verifyOrmRg8){
                writeArtifact("avatar-orm-rg8-check.json",new JSONObject().put("running",true).put("passed",false).put("performance_evidence",false));
                show("正在核对原始材质与纹理优化的设备画面；此检查不测帧率");
                return;
            }
            write(new JSONObject().put("running",true).put("passed",false));
            if(verifyBackgroundCache){
                backgroundVerificationViews=RuntimeViewCount.read(getIntent().getExtras(),true);
                AvatarMultiviewCheck.backgroundProfile(backgroundVerificationViews);
                if(verifyPrivateHead||verifyMultiview||verifyBatch||verifyPersistentFbos||verifyCachedCameraVp||verifyMultiview16)
                    throw new IllegalArgumentException("Select only the private background-cache verification");
                writeArtifact(backgroundVerificationFile(),new JSONObject().put("running",true).put("passed",false));
            }
            if((verifyMultiview?1:0)+(verifyBatch?1:0)+(verifyPersistentFbos?1:0)+(verifyCachedCameraVp?1:0)+(verifyMultiview16?1:0)>1)throw new IllegalArgumentException("Select one avatar verification per run");
            if(privateHead&&(verifyMultiview||verifyBatch||verifyPersistentFbos||verifyCachedCameraVp||verifyMultiview16))throw new IllegalArgumentException("Private head requires its own verification flag");
            if(verifyPrivateHead)writeArtifact("tripo-head-multiview-check.json",new JSONObject().put("running",true).put("passed",false));
            if(verifyMultiview||verifyBatch||verifyPersistentFbos||verifyCachedCameraVp||verifyMultiview16)writeArtifact(verificationFile(),new JSONObject().put("running",true).put("passed",false));
            scene=privateHead?AvatarGpuScene.privateHeadCheck(getFilesDir(),false,AvatarGpuScene.DrawMode.INDIVIDUAL):AvatarGpuScene.builtin(getAssets(),false);
        }
        catch(Throwable failure){reportFailure(failure);}
    }
    @Override public void onSurfaceChanged(GL10 gl,int width,int height) {
        this.width=width;this.height=height;
        if(verifyPbrFastMath){
            if(pbrVerificationComplete||!error.isEmpty()||cancelled)return;
            try {
                JSONObject report=AvatarPbrFastMathCheck.run(getAssets(),getFilesDir(),()->cancelled);
                if(cancelled)report.put("passed",false).put("cancelled",true);
                writeArtifact("avatar-pbr-fast-math-check.json",report.put("running",false).put("performance_evidence",false));
                pbrVerificationComplete=true;
                show(report.optBoolean("passed")?"着色差异初筛通过；画面及帧率另行验收":"着色差异超出初筛限制，请查看报告");
            }catch(Throwable failure){reportFailure(failure);}
            return;
        }
        if(verifyOrmRg8){
            if(ormVerificationComplete||!error.isEmpty()||cancelled)return;
            try {
                JSONObject report=AvatarOrmRg8Check.run(getAssets(),()->cancelled);
                if(cancelled)report.put("passed",false).put("cancelled",true);
                writeArtifact("avatar-orm-rg8-check.json",report.put("running",false).put("performance_evidence",false));
                ormVerificationComplete=true;
                show(report.optBoolean("passed")?"纹理画面对比通过；帧率与内存另行实测":"纹理画面对比未通过，请查看检查报告");
            }catch(Throwable failure){reportFailure(failure);}
            return;
        }
        if(scene==null)return;
        try {
            captureStates();
            if(verifyBackgroundCache&&!cancelled){
                JSONObject report=AvatarMultiviewCheck.runPrivateBackground(getFilesDir(),backgroundVerificationViews,()->cancelled);
                writeArtifact(backgroundVerificationFile(),report.put("running",false));
            }
            if(verifyPrivateHead&&!cancelled){
                JSONObject serial=AvatarMultiviewCheck.runPrivateHead(getFilesDir(),()->cancelled,false);
                JSONObject batch=serial.optBoolean("passed")?AvatarMultiviewCheck.runPrivateHead(getFilesDir(),()->cancelled,true):new JSONObject().put("passed",false).put("skipped","serial-vs-OVR gate failed");
                writeArtifact("tripo-head-multiview-check.json",new JSONObject().put("running",false).put("passed",serial.optBoolean("passed")&&batch.optBoolean("passed"))
                        .put("serial_vs_ovr",serial).put("individual_vs_batch",batch).put("performance_evidence",false));
            }
            if((verifyMultiview||verifyBatch||verifyPersistentFbos||verifyCachedCameraVp||verifyMultiview16)&&!cancelled) {
                runVerification();
            }
        }catch(Throwable failure){reportFailure(failure);}
    }
    @Override public void onDrawFrame(GL10 gl) {
        if(scene==null||!error.isEmpty())return;
        try {GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);render(width,height,new float[52],new float[3]);checkGl("visible preview");}
        catch(Throwable failure){reportFailure(failure);}
    }
    private void captureStates()throws Exception {
        String folder=privateHead?"tripo-head-preview":"avatar-preview";
        File directory=new File(getFilesDir(),folder);if(!directory.isDirectory()&&!directory.mkdirs())throw new IllegalStateException("Cannot create avatar preview directory");
        JSONObject report=new JSONObject().put("running",true).put("passed",false);write(report);
        int[] fbo={0},texture={0},depth={0};
        try {
            GLES30.glGenFramebuffers(1,fbo,0);GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo[0]);
            GLES30.glGenTextures(1,texture,0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture[0]);
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D,1,GLES30.GL_RGBA8,W,H);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,texture[0],0);
            GLES30.glGenRenderbuffers(1,depth,0);GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER,depth[0]);
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER,GLES30.GL_DEPTH_COMPONENT16,W,H);
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER,GLES30.GL_DEPTH_ATTACHMENT,GLES30.GL_RENDERBUFFER,depth[0]);
            if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Avatar preview framebuffer incomplete");
            String[] names={"neutral","blink-left","blink-right","jaw-open","smile","gaze-left","head-yaw","head-roll-left","head-roll-right","jaw-close-combination"};
            JSONArray frames=new JSONArray();ByteBuffer pixels=ByteBuffer.allocateDirect(W*H*4).order(ByteOrder.nativeOrder());int[] argb=new int[W*H];
            for(int state=0;state<names.length;state++) {
                if(cancelled)throw new IllegalStateException("Avatar preview cancelled");
                float[] weights=new float[52],angles=new float[3];
                if(state==1)weights[9]=1;if(state==2)weights[10]=1;if(state==3)weights[25]=1;
                if(state==4){weights[44]=1;weights[45]=1;}if(state==5){weights[15]=1;weights[14]=1;}
                if(state==6)angles[1]=45;if(state==7)angles[2]=40;if(state==8)angles[2]=-40;
                if(state==9){weights[25]=1;weights[27]=1;}
                render(W,H,weights,angles);GLES30.glFinish();pixels.clear();
                GLES30.glReadPixels(0,0,W,H,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,pixels);
                int glError=GLES30.glGetError();if(glError!=GLES30.GL_NO_ERROR)throw new IllegalStateException("Avatar preview GL error "+glError);
                int foreground=0;
                for(int y=0;y<H;y++)for(int x=0;x<W;x++) {
                    if(x==0&&cancelled)throw new InterruptedException("Avatar preview cancelled");
                    int p=(y*W+x)*4,r=pixels.get(p)&255,g=pixels.get(p+1)&255,b=pixels.get(p+2)&255;
                    argb[(H-1-y)*W+x]=0xff000000|(r<<16)|(g<<8)|b;
                    if(r>20||g>20||b>25)foreground++;
                }
                if(foreground<1000)throw new IllegalStateException("Avatar frame contains no visible geometry");
                File target=new File(directory,names[state]+".png");String hash=writePng(argb,target);
                frames.put(new JSONObject().put("name",names[state]).put("foreground_pixels",foreground)
                        .put("file",folder+"/"+target.getName()).put("sha256",hash).put("bytes",target.length()));
            }
            if(cancelled)throw new InterruptedException("Avatar preview cancelled before publication");
            checkGl("completed avatar captures");
            write(report.put("running",false).put("passed",true).put("renders",frames).put("avatar",scene.status())
                    .put("artwork_validated",false).put("scope","GL compiled, ten deterministic actual-asset renders saved; geometry visibility only, actions/cross-state differences and visual acceptance separate"));
            show("10张角色截图已生成；passed仅表示GL与可见几何，表情效果需查看图片验收");
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);
            GLES30.glDeleteRenderbuffers(1,depth,0);GLES30.glDeleteTextures(1,texture,0);GLES30.glDeleteFramebuffers(1,fbo,0);
        }
    }
    private void render(int width,int height,float[] weights,float[] angles) {
        GLES30.glViewport(0,0,width,height);GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
        GLES30.glColorMask(true,true,true,true);GLES30.glDepthMask(true);GLES30.glClearColor(.035f,.045f,.06f,1);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);
        float aspect=width/(float)height;Matrix.setLookAtM(view,0,0,0,3,0,0,0,0,1,0);
        Matrix.frustumM(projection,0,-.052f*aspect,.052f*aspect,-.052f,.052f,.1f,10);
        Matrix.multiplyMM(vp,0,projection,0,view,0);scene.prepare(weights,angles);scene.draw(vp,1,aspect);
    }
    private void reportFailure(Throwable failure) {
        error=failure.toString();android.util.Log.e("AvatarPreview",error,failure);
        show((verificationConfigurationError?"验证配置无效：":verifyPersistentFbos||verifyCachedCameraVp?verificationViews()+"视点验证失败：":"预览失败：")+error);
        try {
            write(new JSONObject().put("running",false).put("passed",false).put("error",error));
            if(verifyPrivateHead)writeArtifact("tripo-head-multiview-check.json",new JSONObject().put("running",false).put("passed",false).put("error",error));
            if(verifyBackgroundCache)writeArtifact(backgroundVerificationFile(),new JSONObject().put("running",false).put("passed",false).put("error",error));
            if(getIntent().getExtras()!=null&&getIntent().getExtras().containsKey("verify_pbr_fast_math"))
                writeArtifact("avatar-pbr-fast-math-check.json",new JSONObject().put("running",false).put("passed",false).put("error",error).put("performance_evidence",false));
            if(getIntent().getExtras()!=null&&getIntent().getExtras().containsKey("verify_orm_rg8"))
                writeArtifact("avatar-orm-rg8-check.json",new JSONObject().put("running",false).put("passed",false).put("error",error).put("performance_evidence",false));
            if(verifyMultiview||verifyBatch||verifyPersistentFbos||verifyCachedCameraVp||verifyMultiview16)writeArtifact(verificationFile(),new JSONObject().put("running",false).put("passed",false).put("error",error));
        }
        catch(Exception writeError){android.util.Log.e("AvatarPreview","Could not publish failure; do not use an older report",writeError);show("失败报告写入失败；不可使用旧报告："+writeError);}
    }
    private static void checkGl(String operation){int code=GLES30.glGetError();if(code!=GLES30.GL_NO_ERROR)throw new IllegalStateException(operation+" GL error "+code);}
    private String backgroundVerificationFile(){return "tripo-head-background-cache-"+backgroundVerificationViews+"-check.json";}
    @SuppressWarnings("deprecation") static boolean readPbrVerification(Bundle extras,boolean debug,boolean otherMode){
        String key="verify_pbr_fast_math";
        if(extras==null||!extras.containsKey(key))return false;
        Object value=extras.get(key);
        if(!debug||!(value instanceof Boolean))throw new IllegalArgumentException("PBR verification requires an explicit debug Boolean");
        if(Boolean.TRUE.equals(value)&&otherMode)throw new IllegalArgumentException("Select only the PBR verification");
        return Boolean.TRUE.equals(value);
    }
    @SuppressWarnings("deprecation") static boolean readOrmVerification(Bundle extras,boolean debug,boolean otherMode){
        String key="verify_orm_rg8";
        if(extras==null||!extras.containsKey(key))return false;
        Object value=extras.get(key);
        if(!debug||!(value instanceof Boolean))throw new IllegalArgumentException("ORM verification requires an explicit debug Boolean");
        if(Boolean.TRUE.equals(value)&&otherMode)throw new IllegalArgumentException("Select only the ORM verification");
        return Boolean.TRUE.equals(value);
    }
    @SuppressWarnings("deprecation") static boolean readBackgroundVerification(Bundle extras,boolean debug,boolean privateHead){
        String key="verify_private_background_cache";
        if(extras==null||!extras.containsKey(key))return false;
        Object value=extras.get(key);
        if(!debug||!(value instanceof Boolean))throw new IllegalArgumentException("Background verification requires a non-null debug Boolean");
        if(Boolean.TRUE.equals(value)&&!privateHead)throw new IllegalArgumentException("Background verification requires a private head scene");
        return Boolean.TRUE.equals(value);
    }
    private void readVerificationViewCount(Bundle extras,boolean debug){
        verificationViewCount=(verifyPersistentFbos||verifyCachedCameraVp)?RuntimeViewCount.read(extras,debug):20;
    }
    private void readVerificationConfiguration(Bundle extras,boolean debug){
        verificationConfigurationError=true;
        verificationViewHeight=720;
        if((verifyMultiview16||verifyPersistentFbos||verifyCachedCameraVp)&&extras!=null&&extras.containsKey("test_view_preset")){
            Object raw=extras.get("test_view_preset");
            if(!debug||!(raw instanceof String)||(!raw.equals("400x640")&&!raw.equals("400x720")))
                throw new IllegalArgumentException("Avatar verification preset requires debug String 400x640 or 400x720");
            verificationViewHeight=raw.equals("400x640")?640:720;
        }
        readVerificationViewCount(extras,debug);
        if(verificationViewHeight==640&&verificationViews()!=16)throw new IllegalArgumentException("400x640 diagnostic requires sixteen views");
        verificationConfigurationError=false;
    }
    private int verificationViews(){return verifyMultiview16?16:(verifyPersistentFbos||verifyCachedCameraVp)?verificationViewCount:20;}
    private String verificationFile(){
        if(verificationConfigurationError)return "avatar-verification-config-error.json";
        if(verificationViewHeight==640)return verifyMultiview16?"avatar-multiview16-400640-check.json":verifyCachedCameraVp?"avatar-camera-vp16-400640-check.json":"avatar-persistent-fbo16-400640-check.json";
        return verifyMultiview16?"avatar-multiview16-check.json":verifyCachedCameraVp?(verificationViews()==16?"avatar-camera-vp16-check.json":"avatar-camera-vp-check.json"):verifyPersistentFbos?(verificationViews()==16?"avatar-persistent-fbo16-check.json":"avatar-persistent-fbo-check.json"):verifyBatch?"avatar-batch-check.json":"avatar-multiview-check.json";
    }
    private String verificationProgressMessage(){
        int views=verificationViews();String size=verificationViewHeight==640?" / 400×640":"";
        return verifyMultiview16?"正在严格核对69组动作的16个独立视点"+size+"；可退出取消":verifyCachedCameraVp?"正在核对相机矩阵缓存及69组动作、"+views+"视点"+size+"；可退出取消":verifyPersistentFbos?"正在核对固定帧缓冲的69组动作、"+views+"视点"+size+"；可退出取消":verifyBatch?"正在核对合并绘制的69组动作、20视点；可退出取消":"正在核对69组动作的20个独立视点；可退出取消";
    }
    private String verificationFinishedMessage(boolean passed){
        String size=verificationViewHeight==640?" / 400×640":"";
        return passed?"69组动作、"+verificationViews()+"视点"+size+"像素核对通过；光学与美术体验仍需验收":verificationViews()+"视点"+size+"核对未通过，请查看报告";
    }
    private void runVerification()throws Exception {
        int views=verificationViews();show(verificationProgressMessage());
        JSONObject result=verifyMultiview16?AvatarMultiviewCheck.runSixteen(getAssets(),400,verificationViewHeight,()->cancelled)
                :verifyCachedCameraVp?AvatarCameraProjectionCheck.run(getAssets(),400,verificationViewHeight,views,1200,1920,()->cancelled)
                :verifyPersistentFbos?AvatarPersistentFboCheck.run(getAssets(),400,verificationViewHeight,views,1200f/1920f,()->cancelled)
                :verifyBatch?AvatarBatchCheck.run(getAssets(),400,720,20,1200f/1920f,()->cancelled)
                :AvatarMultiviewCheck.run(getAssets(),400,720,20,1200f/1920f,()->cancelled);
        if(cancelled)result.put("passed",false).put("cancelled",true);
        writeArtifact(verificationFile(),result.put("running",false));
        show(verificationFinishedMessage(result.optBoolean("passed")));
    }
    private void show(String message){runOnUiThread(()->{if(status!=null)status.setText(message);});}
    private static String writePng(int[] argb,File target)throws Exception {
        Bitmap bitmap=Bitmap.createBitmap(argb,W,H,Bitmap.Config.ARGB_8888);
        AtomicFile atomic=new AtomicFile(target);FileOutputStream out=null;MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try {
            out=atomic.startWrite();DigestOutputStream hashing=new DigestOutputStream(out,digest);
            if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,hashing))throw new IllegalStateException("PNG failed");
            hashing.flush();atomic.finishWrite(out);out=null;
            StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            return hex.toString();
        } catch(Exception failure){if(out!=null)atomic.failWrite(out);throw failure;}
        finally {bitmap.recycle();}
    }
    private void write(JSONObject report)throws Exception {
        report.put("capture_width",W).put("capture_height",H).put("expected_state_count",10);
        writeArtifact(privateHead?"tripo-head-preview-check.json":"avatar-preview-check.json",report);
    }
    private void writeArtifact(String filename,JSONObject report)throws Exception {
        report.put("updated_elapsed_ns",SystemClock.elapsedRealtimeNanos()).put("started_elapsed_ns",startedNs)
                .put("run_id",runId).put("context_generation",contextGeneration).put("cancelled",cancelled)
                .put("artwork_validated",false);
        if(filename.equals(verificationFile())){
            if(verificationConfigurationError)report.put("configuration_valid",false);
            else report.put("requested_view_count",verificationViews()).put("requested_view_width",400).put("requested_view_height",verificationViewHeight);
        }
        if(cancelled)report.put("passed",false);
        AtomicFile file=new AtomicFile(new File(getFilesDir(),filename));FileOutputStream out=null;
        try{out=file.startWrite();out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}
        catch(Exception failure){if(out!=null)file.failWrite(out);throw failure;}
    }
}
