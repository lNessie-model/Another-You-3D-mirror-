package com.mirror.bench;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import org.json.JSONObject;

/** Offline import -> exact-package GL preview -> explicit activation. No camera or network use. */
public final class AvatarManagementActivity extends Activity {
    private static final int PICK_ZIP=91;
    private static final float ASPECT=.625f;
    // A blocked document provider cannot multiply workers across HOME/recreated Activity instances.
    private static final ThreadPoolExecutor IO=executor("AvatarManagementIO");
    private static final ThreadPoolExecutor CLOSER=executor("AvatarDocumentClose");
    private final StoreOwner storeOwner=new StoreOwner();
    private final AvatarManagementGate gate=new AvatarManagementGate();
    private final Handler main=new Handler(Looper.getMainLooper());
    private GLSurfaceView surface;
    private PreviewRenderer renderer;
    private TextView status,catalogText,previewText;
    private Button choose,current,previous,candidate,builtin,use,retry,details;
    private Button[] poses;
    private boolean resumed,busy;
    private String detail="";
    private AvatarPackageStore.Catalog catalog;
    private Preview selected;
    private AvatarManagementGate.Token owner;
    private Future<?> work;
    private volatile OwnedStream openedStream;
    private Uri pendingImport;
    private final Runnable deadlineTick=new Runnable(){@Override public void run(){
        if(!resumed)return;
        AvatarManagementGate.Timeout timeout=gate.pollTimeout(nowNs());
        if(timeout!=null)onTimeout(timeout);
        main.postDelayed(this,250);
    }};

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12),dp(8),dp(12),dp(8));root.setBackgroundColor(0xff111923);
        TextView title=text("角色管理",22);root.addView(title);
        root.addView(text("本地导入 · 单视点预览 · 未进行美术验收",13));
        status=text("正在读取角色…",15);root.addView(status);
        surface=new GLSurfaceView(this);surface.setEGLContextClientVersion(3);
        surface.setEGLConfigChooser(8,8,8,8,16,0);surface.setPreserveEGLContextOnPause(false);
        renderer=new PreviewRenderer();surface.setRenderer(renderer);
        surface.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        root.addView(surface,new LinearLayout.LayoutParams(-1,0,1));
        previewText=text("预览区域按屏幕 10:16 比例显示",14);root.addView(previewText);
        LinearLayout poseRow=row(root);poses=new Button[4];
        String[] poseNames={"中性","闭眼","张口","头转"};
        for(int i=0;i<4;i++){final int pose=i;poses[i]=button(poseRow,poseNames[i],()->{renderer.pose=pose;surface.requestRender();});}
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);scroll.addView(controls);
        catalogText=text("当前：读取中\n候选：读取中\n上一版：读取中",14);controls.addView(catalogText);
        LinearLayout first=row(controls);
        choose=button(first,"导入 ZIP",this::chooseZip);builtin=button(first,"预览内置",()->loadSelection(Selection.BUILTIN));
        LinearLayout second=row(controls);
        current=button(second,"预览当前",()->loadSelection(Selection.CURRENT));
        candidate=button(second,"预览候选",()->loadSelection(Selection.CANDIDATE));
        previous=button(second,"预览上一版",()->loadSelection(Selection.PREVIOUS));
        use=new Button(this);use.setText("使用此角色");use.setOnClickListener(v->activate());controls.addView(use);
        controls.addView(text("支持已验证的 GLB 表情、刚性关节及顶点颜色；不支持贴图和蒙皮。预览成功只确认渲染路径，请检查动作。",12));
        LinearLayout last=row(controls);retry=button(last,"重新读取",()->loadSelection(Selection.CURRENT));
        details=button(last,"维护详情",()->new AlertDialog.Builder(this).setTitle("维护详情").setMessage(detail.isEmpty()?"当前无错误。":detail).setPositiveButton("关闭",null).show());
        button(last,"返回",this::finish);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,dp(290)));setContentView(root);refreshButtons();
    }
    @Override protected void onResume() {
        super.onResume();resumed=true;gate.resume();surface.onResume();
        main.removeCallbacks(deadlineTick);main.postDelayed(deadlineTick,250);
        if(Build.VERSION.SDK_INT<AvatarPackageStore.MIN_ANDROID_API){showFailure("此设备暂不支持角色导入；需要 Android 11 或更高版本。","API "+Build.VERSION.SDK_INT);return;}
        if(pendingImport!=null){Uri uri=pendingImport;pendingImport=null;importZip(uri);}
        else loadSelection(Selection.CURRENT);
    }
    @Override protected void onPause() {
        resumed=false;main.removeCallbacks(deadlineTick);gate.pause();cancelWork();selected=null;renderer.request=null;
        renderer.stopCpu();AvatarManagementGate.Context context=renderer.context;
        surface.queueEvent(()->renderer.disposeIfContext(context));surface.onPause();
        refreshButtons();super.onPause();
    }
    @Override protected void onDestroy(){storeOwner.close();resumed=false;main.removeCallbacks(deadlineTick);gate.pause();cancelWork();renderer.stopCpu();super.onDestroy();}
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==PICK_ZIP&&result==RESULT_OK&&data!=null&&data.getData()!=null){
            if(!"content".equals(data.getData().getScheme())){showFailure("请选择系统文件选择器提供的 ZIP 文件。","Unexpected document URI scheme");return;}
            pendingImport=data.getData();
            if(resumed){Uri uri=pendingImport;pendingImport=null;importZip(uri);}
        }
    }
    private void chooseZip() {
        if(!resumed||busy)return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/zip","application/x-zip-compressed","application/octet-stream"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivityForResult(intent,PICK_ZIP);}
        catch(RuntimeException failure){showFailure("无法打开系统文件选择器，请检查文件管理器。",concise(failure));}
    }
    private enum Selection { CURRENT,CANDIDATE,PREVIOUS,BUILTIN }
    private void loadSelection(Selection selection) {
        startSelection("正在验证角色文件…",token->{
            // A broken optional summary must not prevent preview of another independently validated
            // package, or the APK builtin. Corrupt selection state is never silently reset here.
            if(selection==Selection.BUILTIN){
                Preview value=loadBuiltin();CatalogSnapshot list;
                try{list=readCatalog(openStore());}catch(InterruptedIOException cancelled){throw cancelled;}
                catch(IOException failure){list=new CatalogSnapshot(null,concise(failure));}
                deliver(token,new Loaded(value,list.catalog,list.warning));return;
            }
            AvatarPackageStore store=openStore();CatalogSnapshot list=readCatalog(store);
            checkOwner(token);
            AvatarPackageStore.LoadedPackage loaded;
            if(selection==Selection.PREVIOUS)loaded=store.readPrevious();
            else if(selection==Selection.CANDIDATE)loaded=store.readCandidate();
            else loaded=store.readCurrent();
            if(loaded==null&&(selection==Selection.PREVIOUS||selection==Selection.CANDIDATE))throw new IOException("所选角色已不存在，请重新读取列表");
            Preview value=loaded==null?loadBuiltin():Preview.from(loaded,selection==Selection.PREVIOUS);
            deliver(token,new Loaded(value,list.catalog,list.warning));
        });
    }
    private void importZip(Uri uri) {
        startSelection("正在导入并验证 ZIP；当前角色保持可用…",token->{
            AvatarPackageStore store=openStore();store.recover();checkOwner(token);
            InputStream stream=getContentResolver().openInputStream(uri);
            if(stream==null)throw new IOException("Document provider returned no input stream");
            OwnedStream owned=new OwnedStream(stream);openedStream=owned;
            try {
                checkOwner(token);AvatarPackageStore.LoadedPackage loaded=store.importZip(stream);
                checkOwner(token);CatalogSnapshot list=readCatalog(store);
                deliver(token,new Loaded(Preview.from(loaded,false),list.catalog,list.warning));
            } finally {owned.close();if(openedStream==owned)openedStream=null;}
        });
    }
    private interface SelectionWork {void run(AvatarManagementGate.Token token)throws Exception;}
    private void startSelection(String message,SelectionWork operation) {
        if(!resumed||Build.VERSION.SDK_INT<AvatarPackageStore.MIN_ANDROID_API)return;
        cancelWork();owner=gate.beginSelection(nowNs());final AvatarManagementGate.Token token=owner;
        selected=null;renderer.request=null;renderer.pose=0;surface.requestRender();
        busy=true;detail="";status.setText(message);previewText.setText("验证成功后显示真实角色预览");refreshButtons();
        try {work=IO.submit(()->{
            try{checkOwner(token);operation.run(token);}
            catch(Exception|LinkageError|OutOfMemoryError failure){report(token,"角色未加载，请重新读取当前角色后重试。",failure);}
        });}catch(java.util.concurrent.RejectedExecutionException rejected){gate.workFailed(token,nowNs());busy=false;showFailure("上一项文件操作仍在停止，请稍后重新读取。",concise(rejected));}
    }
    private void deliver(AvatarManagementGate.Token token,Loaded result)throws InterruptedIOException {
        checkOwner(token);
        runOnUiThread(()->{
            if(!gate.isCurrent(token)||!resumed)return;
            if(!gate.loaded(token,result.preview,nowNs()))return;
            busy=false;catalog=result.catalog;selected=result.preview;
            catalogText.setText(catalog==null?"角色摘要暂不可读取；可逐项尝试预览。详情见维护信息。":
                    "当前："+summary(catalog.current,"内置角色")+"\n候选："+summary(catalog.candidate,"无")+"\n上一版："+summary(catalog.previous,"无"));
            previewText.setText(shortLabel(selected.name)+" · "+selected.asset.vertexCount()+" 顶点 / "+selected.asset.triangleCount()+" 三角形\n"+selected.coverage);
            status.setText("文件验证通过，正在初始化 GL 预览…");detail=bounded(selected.warning+"\n"+result.warning).trim();
            renderer.request=new PreviewRequest(token,selected);surface.requestRender();refreshButtons();
        });
    }
    private void activate() {
        if(!resumed||busy||selected==null)return;
        final Preview value=selected;final AvatarManagementGate.Token token=owner;
        final AvatarManagementGate.Claim claim=gate.claimActivation(token,value,nowNs());
        if(claim==null)return;
        busy=true;status.setText("正在安全切换角色…");refreshButtons();
        try{work=IO.submit(()->{
            try {
                checkOwner(token);AvatarPackageStore store=openStore();
                if(!gate.isValid(claim,nowNs()))throw new InterruptedIOException("Preview no longer belongs to the active page");
                // Store checks interruption again immediately before the atomic pointer rename.
                if(value.ticket==null) {
                    store.activateBuiltin();
                    BundledAvatarSelection.save(this,"builtin-guide");
                } else {
                    if(value.previous)store.restorePrevious(value.ticket);
                    else store.activate(value.ticket);
                    // A confirmed imported activation takes precedence over an explicit APK role.
                    // Preference failures remain visible uncertain activations; never report success.
                    BundledAvatarSelection.clear(this);
                }
                runOnUiThread(()->{
                    if(!resumed||!gate.isValid(claim,nowNs()))return;
                    busy=false;setResult(RESULT_OK);finish();
                });
            }catch(Exception|LinkageError|OutOfMemoryError failure){gate.activationFailed(claim,nowNs());report(token,"未能确认切换结果，请重新读取当前角色后重试。",failure);}
        });}catch(java.util.concurrent.RejectedExecutionException rejected){gate.activationFailed(claim,nowNs());busy=false;showFailure("文件操作仍在停止，请稍后重试。",concise(rejected));}
    }
    private AvatarPackageStore openStore()throws IOException {
        return storeOwner.get(()->AvatarPackageStore.open(new File(getFilesDir(),"avatars"),Build.VERSION.SDK_INT));
    }
    interface StoreFactory {AvatarPackageStore open()throws IOException;}
    /** One Activity's existing IO worker retains its Store across operations and HOME.
     * Store holds no persistent OS handle. Destroy drops this reference without interrupting
     * a caller's local Store/asset references or waiting for filesystem work.
     */
    static final class StoreOwner {
        private AvatarPackageStore store;
        private boolean closed,opening;
        AvatarPackageStore get(StoreFactory factory)throws IOException {
            synchronized(this){
                if(closed)throw new InterruptedIOException("Avatar page destroyed");
                if(store!=null)return store;
                if(opening)throw new AvatarPackageStore.BusyException();
                opening=true;
            }
            try {
                AvatarPackageStore created=factory.open();
                if(created==null)throw new IOException("Avatar Store creation returned no owner");
                synchronized(this){
                    if(closed)throw new InterruptedIOException("Avatar page destroyed while opening Store");
                    store=created;return created;
                }
            }finally{synchronized(this){opening=false;}}
        }
        synchronized void close(){closed=true;store=null;}
    }
    private static CatalogSnapshot readCatalog(AvatarPackageStore store)throws InterruptedIOException {
        try{return new CatalogSnapshot(store.readCatalog(),"");}
        catch(InterruptedIOException cancelled){throw cancelled;}
        catch(IOException failure){return new CatalogSnapshot(null,"角色摘要读取失败："+concise(failure));}
    }
    private void checkOwner(AvatarManagementGate.Token token)throws InterruptedIOException {
        if(Thread.currentThread().isInterrupted()||!gate.isCurrent(token))throw new InterruptedIOException("Avatar operation cancelled");
    }
    private void cancelWork() {
        Future<?> previousWork=work;work=null;
        if(previousWork!=null){previousWork.cancel(true);if(previousWork instanceof Runnable)IO.remove((Runnable)previousWork);}
        OwnedStream stream=openedStream;
        if(stream!=null)try{CLOSER.execute(stream::close);}catch(java.util.concurrent.RejectedExecutionException ignored){/* Single IO owner retains final close responsibility. */}
        busy=false;
    }
    private void onTimeout(AvatarManagementGate.Timeout timeout) {
        // The gate has already revoked this owner, including any late IO/GL success callback.
        // Keep the same bounded executors: an uncooperative provider is not safe to replace.
        cancelWork();owner=null;selected=null;renderer.request=null;renderer.stopCpu();surface.requestRender();
        String stage=timeout.stage==AvatarManagementGate.Stage.LOADING?"文件读取/验证":
                timeout.stage==AvatarManagementGate.Stage.GL_INITIALIZING?"GL 预览初始化":"角色切换确认";
        String message=timeout.stage==AvatarManagementGate.Stage.ACTIVATING?
                "角色切换确认超时，结果尚不确定。请重新读取当前角色；也可返回主程序确认。":
                stage+"超时，已取消本次预览。可返回，或稍后点击“重新读取”。";
        previewText.setText("本次预览资格已取消；重新读取并预览后才能使用。");
        showFailure(message,"stage="+timeout.stage+", elapsed_ms="+(timeout.elapsedNs/1_000_000L)+
                ", timeout_ms="+(timeout.budgetNs/1_000_000L)+
                "\n已发出取消；系统文件提供方或 GL 调用可能仍在停止。没有启动额外 IO 工作线程。"+
                (timeout.stage==AvatarManagementGate.Stage.ACTIVATING?"\n已进入原子提交的切换不能撤销，需重新读取磁盘选择。":""));
    }
    private static long nowNs(){return SystemClock.elapsedRealtimeNanos();}
    private void report(AvatarManagementGate.Token token,String userMessage,Throwable failure) {
        if(!gate.isCurrent(token))return;
        String description=concise(failure);Log.e("AvatarManagement",description);
        runOnUiThread(()->{if(resumed&&gate.workFailed(token,nowNs())){busy=false;showFailure(userMessage,description);}});
    }
    private void showFailure(String message,String maintenance){detail=maintenance;status.setText(message);refreshButtons();}
    private void refreshButtons() {
        if(choose==null)return;
        boolean available=resumed&&!busy&&Build.VERSION.SDK_INT>=AvatarPackageStore.MIN_ANDROID_API;
        choose.setEnabled(available);builtin.setEnabled(available);current.setEnabled(available);retry.setEnabled(available);
        candidate.setEnabled(available&&(catalog==null||catalog.candidate!=null));
        previous.setEnabled(available&&(catalog==null||catalog.previous!=null));
        use.setEnabled(available&&selected!=null&&gate.canActivate(owner,selected));
        use.setText(selected!=null&&selected.previous?"回退到预览的上一版":"使用此角色");
        for(Button pose:poses)pose.setEnabled(available&&selected!=null);
        details.setEnabled(!detail.isEmpty());
    }
    private Preview loadBuiltin()throws Exception {
        byte[] manifest,glb;String directory="avatars/builtin-guide/";
        try(InputStream input=getAssets().open(directory+"avatar.json")){manifest=readBounded(input,262144);}
        String json=new String(manifest,StandardCharsets.UTF_8);JSONObject metadata=new JSONObject(json);
        String filename=metadata.getString("model");if(!filename.matches("[A-Za-z0-9_-]+\\.glb"))throw new IOException("Invalid builtin model path");
        try(InputStream input=getAssets().open(directory+filename)){glb=readBounded(input,32*1024*1024);}
        String hash=sha256(glb);if(!hash.equalsIgnoreCase(metadata.getString("modelSha256")))throw new IOException("Builtin model digest mismatch");
        AvatarAsset asset=AvatarGlbLoader.load(glb);AvatarRig rig=new AvatarRig(asset,json);AvatarGeometryBounds.fromAsset(asset,rig);
        return new Preview(asset,json,hash,null,false,rig.displayName(),coverage(rig),"");
    }
    private static String coverage(AvatarRig rig){return rig.completeSourceCoverage()?"52项输入已映射；请人工检查动作":"部分输入未映射："+rig.missingSources().size()+" 项";}
    private static byte[] readBounded(InputStream input,int limit)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;
        while((n=input.read(block))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Asset read cancelled");if(n==0)continue;if(out.size()>limit-n)throw new IOException("Asset exceeds byte limit");out.write(block,0,n);}return out.toByteArray();
    }
    private static String sha256(byte[] bytes)throws Exception {StringBuilder text=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))text.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return text.toString();}
    private static String summary(AvatarPackageStore.Summary value,String fallback){return value==null?fallback:shortLabel(value.displayName)+"（"+value.vertices+" 顶点）";}
    private static String shortLabel(String value){String label=value.replace('\n',' ').replace('\r',' ');return label.length()>80?label.substring(0,80)+"…":label;}
    private static String concise(Throwable failure){return bounded(failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage()));}
    private static String bounded(String value){return value.length()>2048?value.substring(0,2048):value;}
    private static ThreadPoolExecutor executor(String name){return new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),task->{Thread thread=new Thread(task,name);thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());}
    private TextView text(String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(Color.WHITE);view.setPadding(0,dp(3),0,dp(3));return view;}
    private LinearLayout row(LinearLayout parent){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);parent.addView(row);return row;}
    private Button button(LinearLayout row,String text,Runnable action){Button button=new Button(this);button.setText(text);button.setTextSize(13);button.setMinHeight(dp(48));button.setOnClickListener(v->action.run());row.addView(button,new LinearLayout.LayoutParams(0,-2,1));return button;}
    private int dp(int value){return Math.round(getResources().getDisplayMetrics().density*value);}
    private record Loaded(Preview preview,AvatarPackageStore.Catalog catalog,String warning){}
    private record CatalogSnapshot(AvatarPackageStore.Catalog catalog,String warning){}
    private record PreviewRequest(AvatarManagementGate.Token token,Preview preview){}
    private static final class Preview {
        final AvatarAsset asset;final String manifest,sha,name,coverage,warning;final AvatarPackageStore.Ticket ticket;final boolean previous;
        Preview(AvatarAsset a,String m,String s,AvatarPackageStore.Ticket t,boolean p,String n,String c,String w){asset=a;manifest=m;sha=s;ticket=t;previous=p;name=n;coverage=c;warning=w;}
        static Preview from(AvatarPackageStore.LoadedPackage loaded,boolean previous){return new Preview(loaded.asset,loaded.manifestJson,loaded.ticket.modelSha256,loaded.ticket,previous,loaded.rig.displayName(),coverage(loaded.rig),String.join("\n",loaded.cleanupWarnings));}
    }
    private static final class OwnedStream {
        final InputStream input;final AtomicBoolean closed=new AtomicBoolean();OwnedStream(InputStream input){this.input=input;}
        void close(){if(closed.compareAndSet(false,true))try{input.close();}catch(IOException failure){Log.w("AvatarManagement","Cannot close document",failure);}}
    }
    private final class PreviewRenderer implements GLSurfaceView.Renderer {
        volatile PreviewRequest request;
        volatile AvatarManagementGate.Context context;
        volatile AvatarGpuScene scene;
        volatile int pose;
        private PreviewRequest showing,failed,notified;
        private EGLContext owningContext;
        private int width,height;
        private final float[] weights=new float[52],angles=new float[3],view=new float[16],projection=new float[16],vp=new float[16];
        @Override public void onSurfaceCreated(GL10 gl,EGLConfig config){
            // Old names may have been reused by this new EGL context. Never delete them here.
            stopCpu();scene=null;showing=null;failed=null;notified=null;
            owningContext=EGL14.eglGetCurrentContext();
            gate.contextLost(context,nowNs());context=gate.openContext(nowNs());
        }
        @Override public void onSurfaceChanged(GL10 gl,int w,int h){width=w;height=h;notified=null;}
        @Override public void onDrawFrame(GL10 gl) {
            if(!gate.isCurrent(context))context=gate.openContext(nowNs());
            PreviewRequest next=request;
            if(next==null||!gate.isCurrent(next.token)||context==null){disposeCurrent();clear();return;}
            if(next==failed){clear();return;}
            try {
                if(showing!=next){disposeCurrent();scene=AvatarGpuScene.fromAsset(next.preview.asset,next.preview.manifest,next.preview.sha,false,false);showing=next;}
                if(!gate.isCurrent(next.token)){disposeCurrent();clear();return;}
                clear();if(width<=0||height<=0)return;
                int h=height,w=Math.round(h*ASPECT);if(w>width){w=width;h=Math.round(w/ASPECT);}
                GLES30.glViewport((width-w)/2,(height-h)/2,w,h);
                java.util.Arrays.fill(weights,0);java.util.Arrays.fill(angles,0);
                if(pose==1){weights[9]=1;weights[10]=1;}else if(pose==2)weights[25]=1;else if(pose==3)angles[1]=30;
                Matrix.setLookAtM(view,0,0,0,3,0,0,0,0,1,0);
                Matrix.frustumM(projection,0,-.052f*ASPECT,.052f*ASPECT,-.052f,.052f,.1f,10);
                Matrix.multiplyMM(vp,0,projection,0,view,0);scene.prepare(weights,angles);scene.draw(vp,1,ASPECT);GLES30.glFlush();
                int error=GLES30.glGetError();if(error!=GLES30.GL_NO_ERROR)throw new IllegalStateException("Avatar preview GL error "+error);
                if(gate.previewSubmitted(next.token,context,next.preview,nowNs())&&notified!=next){
                    notified=next;runOnUiThread(()->{if(gate.canActivate(next.token,next.preview)&&resumed){status.setText("预览已渲染，请检查动作后选择“使用此角色”。");refreshButtons();}});
                }
            }catch(Exception|LinkageError|OutOfMemoryError failure){
                gate.previewFailed(next.token,context);failed=next;disposeCurrent();report(next.token,"角色预览失败，无法使用此角色；可选择其他角色。",failure);
            }
        }
        private void clear(){GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);GLES30.glViewport(0,0,width,height);GLES30.glDisable(GLES30.GL_SCISSOR_TEST);GLES30.glColorMask(true,true,true,true);GLES30.glDepthMask(true);GLES30.glClearColor(.035f,.045f,.06f,1);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);}
        void stopCpu(){AvatarGpuScene value=scene;if(value!=null)value.stopCpu();}
        void disposeIfContext(AvatarManagementGate.Context expected){if(expected==context)disposeCurrent();}
        private void disposeCurrent(){
            AvatarGpuScene old=scene;scene=null;showing=null;
            if(old==null)return;
            EGLContext current=EGL14.eglGetCurrentContext();
            if(owningContext!=null&&!EGL14.EGL_NO_CONTEXT.equals(current)&&owningContext.equals(current))old.dispose();
            else old.stopCpu();
        }
    }
}
