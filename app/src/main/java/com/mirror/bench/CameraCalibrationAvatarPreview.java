package com.mirror.bench;

import android.content.Context;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Calibration-only live single-view of the real selected avatar. The host pauses main multi-view
 * drawing, keeps camera/controller ticks, and supplies their already-calibrated Snapshot here.
 * All lifecycle calls are UI-owned; submit/status are thread-safe. No extra AvatarPoseWorker.
 * No custom join occurs; GLSurfaceView onPause/detach retain Android's normal GL wait semantics.
 */
public final class CameraCalibrationAvatarPreview extends GLSurfaceView implements AutoCloseable {
    public interface Listener {void onStatus(Status status);}
    public record Status(String state,String source,String modelSha256,String displayName,String error,
                         long submittedFrames,long lastFrameElapsedNs,int viewportWidth,int viewportHeight,
                         int maximumFps,int additionalPoseWorkers,long decodedAssetBytes){}
    private static final int MAX_FPS=10;
    private static final long PERIOD_NS=1_000_000_000L/MAX_FPS;
    // Process-wide caps survive rapid dialog/HOME/recreation cycles. Rejected work stays visible.
    private static final AtomicReference<CameraCalibrationAvatarPreview> ACTIVE=new AtomicReference<>();
    private static final ThreadPoolExecutor LOADER=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"CameraAvatarLoad");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final Handler main=new Handler(Looper.getMainLooper());
    private final CameraPreviewSession gate=new CameraPreviewSession();
    private final CameraPreviewPose pose=new CameraPreviewPose();
    private final PreviewRenderer renderer=new PreviewRenderer();
    private final File storeRoot;
    private final int androidApi;
    private final Listener listener;
    private final android.content.res.AssetManager assets;
    private volatile CameraPreviewSession.Token owner;
    private volatile Request requested;
    private volatile FrameState frameState=new FrameState(0,0,0,0);
    private Future<?> load;
    private boolean resumed,closed;
    private record Request(CameraPreviewSession.Token token,CameraPreviewAsset asset){}
    private record FrameState(long count,long at,int width,int height){}
    private final Runnable tick=new Runnable(){@Override public void run(){
        if(!resumed||closed)return;
        gate.poll(now());
        if(gate.accepts(owner))requestRender();
        else {cancelLoad();requested=null;queueDisposal();}
        publishStatus();
        // One UI callback and one coalesced GL request, never one queued Runnable per face frame.
        if(resumed&&!closed&&gate.accepts(owner))main.postDelayed(this,1000/MAX_FPS);
    }};

    public CameraCalibrationAvatarPreview(Context context,File avatarStoreRoot,int androidApi,Listener listener){
        super(context);requireUi();
        if(avatarStoreRoot==null||listener==null)throw new IllegalArgumentException("Preview root and listener required");
        if(!ACTIVE.compareAndSet(null,this))throw new IllegalStateException("Another calibration avatar preview is still open");
        this.storeRoot=avatarStoreRoot;this.androidApi=androidApi;this.listener=listener;
        assets=context.getApplicationContext().getAssets();
        try {
            setEGLContextClientVersion(3);setEGLConfigChooser(8,8,8,8,16,0);
            setPreserveEGLContextOnPause(false);setRenderer(renderer);setRenderMode(RENDERMODE_WHEN_DIRTY);
        } catch(RuntimeException|Error failure){ACTIVE.compareAndSet(this,null);throw failure;}
    }
    /** Start a fresh load/GL ownership generation. Current selection corruption is an explicit error. */
    public void resumePreview(){
        requireUi();if(closed)throw new IllegalStateException("Preview is closed");if(resumed)return;
        resumed=true;
        synchronized(gate){pose.reset();requested=null;frameState=new FrameState(0,0,0,0);owner=gate.begin(now());}
        CameraPreviewSession.Token token=owner;
        super.onResume();
        try {
            load=LOADER.submit(()->{
                try {
                    CameraPreviewAsset value=CameraPreviewAsset.load(assets,storeRoot,androidApi);
                    // Publish no GL resource from the loader. Late generations drop their only asset ref.
                    synchronized(gate){if(gate.loaded(token,now()))requested=new Request(token,value);}
                } catch(Exception|LinkageError failure){gate.fail(token,concise(failure));}
            });
        } catch(RuntimeException busy){gate.fail(token,"Avatar loader is busy; close and reopen calibration");}
        main.removeCallbacks(tick);main.post(tick);
    }
    /** Revokes control/load ownership first; never waits for the loader or joins an application worker. */
    public void pausePreview(){
        requireUi();if(!resumed)return;resumed=false;
        synchronized(gate){gate.pause();owner=null;requested=null;pose.reset();}
        main.removeCallbacks(tick);cancelLoad();queueDisposal();
        super.onPause(); // Platform may wait for an in-flight GL call. This is not a custom CPU join.
        renderer.forgetAfterPlatformPause();publishStatus();
    }
    @Override public void close(){
        requireUi();if(closed)return;
        boolean wasResumed=resumed;pausePreview();closed=true;
        synchronized(gate){gate.close();owner=null;requested=null;pose.reset();}
        main.removeCallbacks(tick);cancelLoad();
        // Also handles a view constructed/attached but never explicitly resumed.
        if(!wasResumed){queueDisposal();super.onPause();renderer.forgetAfterPlatformPause();}
        ACTIVE.compareAndSet(this,null);publishStatus();
    }
    @Override protected void onDetachedFromWindow(){close();super.onDetachedFromWindow();}
    /** Immutable Snapshot is copied into the latest-only pose mailbox, not queued on the GL thread. */
    public void submit(InteractionController.Snapshot snapshot,boolean active){
        synchronized(gate){
            CameraPreviewSession.Token token=owner;if(snapshot==null||!gate.accepts(token))return;
            try {pose.offer(snapshot.blendshapes52(),snapshot.pose(),active&&snapshot.facePresent()
                        &&snapshot.state()==InteractionController.State.INTERACTIVE,snapshot.receivedNs(),now());}
            catch(IllegalArgumentException invalid){gate.fail(token,concise(invalid));}
        }
    }
    public Status status(){
        Request value=requested;FrameState frames=frameState;
        return new Status(gate.state().name(),value==null?"":value.asset.source,value==null?"":value.asset.sha256,
                value==null?"":value.asset.name,gate.error(),frames.count,frames.at,frames.width,frames.height,
                MAX_FPS,0,value==null?0:value.asset.asset.decodedBytes());
    }
    private void publishStatus(){listener.onStatus(status());}
    private void cancelLoad(){Future<?> previous=load;load=null;if(previous!=null){previous.cancel(true);if(previous instanceof Runnable)LOADER.remove((Runnable)previous);}}
    private void queueDisposal(){long context=renderer.contextGeneration;queueEvent(()->renderer.disposeIfGeneration(context));}
    private static void requireUi(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Preview lifecycle is UI-owned");}
    private static long now(){return SystemClock.elapsedRealtimeNanos();}
    private static String concise(Throwable error){String value=error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage());return value.substring(0,Math.min(value.length(),512));}

    private final class PreviewRenderer implements GLSurfaceView.Renderer {
        private AvatarGpuScene scene;
        private Request showing;
        private EGLContext owningContext;
        private volatile long contextGeneration;
        private int width,height;
        private long drawnAt;
        private final float[] full52=new float[52],angles=new float[3],view=new float[16],projection=new float[16],vp=new float[16];
        @Override public void onSurfaceCreated(GL10 gl,EGLConfig config){
            // Previous IDs belong to a destroyed context. Only stop its CPU owner, never glDelete here.
            if(scene!=null)scene.stopCpu();scene=null;showing=null;drawnAt=0;
            owningContext=EGL14.eglGetCurrentContext();contextGeneration++;
            gate.contextRecreated(owner,now());
        }
        @Override public void onSurfaceChanged(GL10 gl,int width,int height){this.width=width;this.height=height;}
        @Override public void onDrawFrame(GL10 gl){
            Request next=requested;
            if(width<=0||height<=0)return;
            if(next==null||!gate.accepts(next.token)){disposeCurrent();clear();return;}
            long start=now();if(showing==next&&start-drawnAt<PERIOD_NS)return;
            try {
                if(showing!=next){disposeCurrent();scene=AvatarGpuScene.fromAsset(next.asset.asset,next.asset.manifest,next.asset.sha256,false,false);showing=next;}
                if(!gate.accepts(next.token)){disposeCurrent();clear();return;}
                clear();int[] viewport=CameraPreviewPose.viewport(width,height);
                GLES30.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
                Matrix.setLookAtM(view,0,0,0,3,0,0,0,0,1,0);
                Matrix.frustumM(projection,0,-.052f*.625f,.052f*.625f,-.052f,.052f,.1f,10);
                Matrix.multiplyMM(vp,0,projection,0,view,0);
                pose.sample(now(),full52,angles);scene.prepare(full52,angles);scene.draw(vp,1,.625f);
                int code=GLES30.glGetError();if(code!=GLES30.GL_NO_ERROR)throw new IllegalStateException("Avatar preview GL error "+code);
                long done=now();drawnAt=start;
                if(gate.rendered(next.token,done))frameState=new FrameState(frameState.count+1,done,viewport[2],viewport[3]);
                else {disposeCurrent();clear();}
            } catch(Exception|LinkageError failure){gate.fail(next.token,concise(failure));disposeCurrent();clear();}
        }
        private void clear(){
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
            GLES30.glColorMask(true,true,true,true);GLES30.glDepthMask(true);GLES30.glViewport(0,0,width,height);
            GLES30.glClearColor(.035f,.045f,.06f,1);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);
        }
        private void disposeCurrent(){
            AvatarGpuScene old=scene;scene=null;showing=null;
            if(old==null)return;old.stopCpu();EGLContext current=EGL14.eglGetCurrentContext();
            if(owningContext!=null&&!EGL14.EGL_NO_CONTEXT.equals(current)&&owningContext.equals(current))old.dispose();
        }
        void disposeIfGeneration(long expected){if(contextGeneration==expected)disposeCurrent();}
        // onPause has returned, so no GL callback is using these Java refs and context is not preserved.
        void forgetAfterPlatformPause(){if(scene!=null)scene.stopCpu();scene=null;showing=null;owningContext=null;}
    }
}
