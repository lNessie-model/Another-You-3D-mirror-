package com.mirror.bench;

import android.opengl.GLES30;
import android.opengl.GLES32;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Generic RGB subpixel interlacing. Diagnostic constants are NOT panel calibration. */
final class InterlaceRenderer implements GLSurfaceView.Renderer {
    private final int views;
    private final float scale;
    private final boolean scene;
    private int width,height,viewWidth,viewHeight,texture,fbo,depth,sceneProgram,interlaceProgram;
    private int requestedViewWidth,requestedViewHeight;
    private int vertexBuffer,indexBuffer,indexCount;
    private String gpu="";
    private String extensions="";
    private int maxArrayLayers,maxMultiviewViews,windowDepthBits;
    private volatile String error="";
    private volatile float[] expressions={0,0,0,0};
    private boolean runtimeMode,surfaceInitialized;
    private volatile SceneViewSettings sceneView=SceneViewSettings.DEFAULT;
    private volatile SceneViewSettings frameSceneView=SceneViewSettings.DEFAULT;
    private int runtimeBackgroundProgram;
    private volatile SceneBackgroundTexture screenBackground;
    void setSceneView(SceneViewSettings value){if(value==null)throw new IllegalArgumentException("Scene view required");sceneView=value;}
    private android.content.res.AssetManager avatarAssets;
    private java.io.File avatarStoreRoot;
    private java.io.File privateHeadFiles;
    private int avatarStoreApi;
    private boolean avatarBatched;
    private volatile String avatarLoadWarning="";
    private volatile String avatarPackageId="",avatarSourceKind="pending";
    private volatile AvatarGpuScene avatarScene;
    private boolean avatarAsynchronous=true;
    private volatile boolean avatarShutdown;
    private volatile long avatarResumeGeneration;
    private long consumedAvatarResumeGeneration;
    private final RuntimeGlLifecycle runtimeGlLifecycle=new RuntimeGlLifecycle();
    private final float[] runtimeBlendshapes=new float[52];
    private final float[] playbackBlendshapes=new float[52],playbackAngles=new float[3];
    private volatile JSONObject facePlaybackStatus=new JSONObject();
    private volatile InteractiveFace interactiveFace=InteractiveFace.NEUTRAL;
    private final float[] runtimeWeights=new float[4],runtimeAngles=new float[3];
    private long runtimePoseTime,runtimeFrames,runtimeWindowStart;
    private int runtimeWindowFrames;
    private double runtimeWindowWorkMs,runtimeFps,runtimeWorkMeanMs;
    private volatile boolean runtimeFaceActive;
    private boolean measuring;
    private long firstFrame,lastFrame;
    private final ArrayList<Double> workMs=new ArrayList<>(),intervalMs=new ArrayList<>();
    private final ArrayList<Double> sceneStageMs=new ArrayList<>(),interlaceStageMs=new ArrayList<>();
    private volatile boolean profileStages;
    private boolean pipeline;
    private final long[] fences=new long[2];
    private int fenceSlot;
    private boolean lookup,verifyLookup;
    private boolean cull,verifyCull;
    private JSONObject cullVerification;
    private float fixedAngle=Float.NaN;
    private boolean atlas,verifyAtlas,atlasCopy;
    private int atlasTexture,atlasFbo,atlasDepth,atlasProgram,atlasColumns,atlasRows;
    private JSONObject atlasVerification;
    private boolean multiview,verifyMultiview;
    private int multiviewProgram,multiviewDepth,multiviewFbo;
    private boolean persistentFbosRequested;
    private volatile String multiviewFboActual="uninitialized";
    private volatile int persistentFboCount;
    private PersistentMultiviewFbos persistentFbos;
    private long glContextGeneration;
    private boolean gpuProfileRequested;
    private boolean ormRg8Requested,pbrFastMathRequested;
    private volatile RuntimeGpuProfile gpuProfile;
    private long gpuCallbackId,gpuFramePacingEpoch;
    private int gpuFrameTarget;
    private boolean gpuCallbackActive;
    private boolean cachedCameraVpRequested;
    private volatile String cameraVpActual="uninitialized";
    private final AvatarCameraProjectionCache cameraVpCache=new AvatarCameraProjectionCache();
    private boolean staticBackgroundCacheRequested;
    private AvatarBackgroundCache backgroundCache;
    private AvatarGpuScene backgroundCacheScene;
    private float[] backgroundCacheState,backgroundCacheVp;
    private volatile String backgroundCacheActual="uninitialized",backgroundCacheWarning="";
    private volatile long backgroundCacheBytes,backgroundCacheBuilds;
    private JSONObject multiviewVerification;
    private final float[] multiviewMatrices=new float[64];
    private boolean preblend,verifyPreblend;
    private int preblendProgram,preblendSceneProgram,preblendMultiviewProgram,preblendBuffer,vertexCount;
    private float[] lastBlendedWeights;
    private JSONObject preblendVerification;
    private long preblendUpdates;
    private boolean explicitLod;
    private int explicitLodProgram;
    private float widthScale;
    private boolean verifyCombined;
    private JSONObject combinedVerification;
    private boolean discardDepth,verifyDiscardDepth;
    private JSONObject discardDepthVerification;
    private boolean sharedPhase,verifySharedPhase;
    private int sharedPhaseProgram;
    private JSONObject sharedPhaseVerification;
    private static final int[] DEPTH_ATTACHMENT={GLES30.GL_DEPTH_ATTACHMENT};
    private int lookupTexture,lookupProgram,lookupGenerator;
    private int lookupMismatch=-1;
    private float pitch=9.69f,tilt=.28f;
    private PanelCalibration panelCalibration;
    private boolean calibrationPattern;
    private volatile int targetFps;
    private volatile Thread renderThread;
    private long renderDeadline;
    private final FramePacingStats framePacing=new FramePacingStats();
    private volatile long pacingEpoch;
    private long pacingMeasurementGeneration;
    // GL-thread scratch timestamps only; reset at every callback before drawViews.
    private long runtimePrepareStart,runtimePrepareEnd;
    private final ViewSubmissionTiming runtimeViewTiming=new ViewSubmissionTiming();
    private String calibration="diagnostic constants; not a verified optical panel calibration";
    private final float[] projection=new float[16],view=new float[16],model=new float[16],mv=new float[16],mvp=new float[16];

    InterlaceRenderer(int views,float scale,boolean scene) {
        if(views<1||views>32||!Float.isFinite(scale)||scale<=0||scale>1)
            throw new IllegalArgumentException("Invalid view configuration");
        this.views=views; this.scale=scale; this.widthScale=scale; this.scene=scene;
    }
    void setExpressions(float[] values) { expressions=values; }
    /** Configure before attaching the renderer; an avatar is selected separately. */
    synchronized void setRuntimeMode(boolean enabled) {
        if(surfaceInitialized) throw new IllegalStateException("Runtime mode must be set before GL initialization");
        runtimeMode=enabled;
    }
    synchronized void setRuntimeAvatar(android.content.res.AssetManager assets) {
        if(surfaceInitialized||!runtimeMode) throw new IllegalStateException("Avatar must be selected before runtime GL initialization");
        avatarAssets=java.util.Objects.requireNonNull(assets);
        preblend=false;
    }
    synchronized void setRuntimeAvatar(android.content.res.AssetManager assets,java.io.File privateRoot,int androidApi) {
        setRuntimeAvatar(assets);
        avatarStoreRoot=java.util.Objects.requireNonNull(privateRoot);avatarStoreApi=androidApi;
    }
    private String bundledAvatarDirectory,bundledAvatarId,bundledModelDigest,bundledManifestDigest;
    private boolean bundledSelectionExplicit;
    /** Catalog metadata only; GLB decoding remains on the GL thread. */
    synchronized void setBundledRuntimeAvatar(android.content.res.AssetManager assets,String directory,String catalogId,
                                              String modelDigest,String manifestDigest,boolean explicitSelection) {
        if(surfaceInitialized||!runtimeMode)throw new IllegalStateException("Bundled avatar must be selected before GL initialization");
        if(directory==null||!directory.matches("avatars/(?:catalog/[a-z0-9][a-z0-9-]{0,63}|builtin-guide)"))
            throw new IllegalArgumentException("Invalid bundled avatar directory");
        if(catalogId==null||!catalogId.matches("[a-z0-9][a-z0-9-]{0,63}"))throw new IllegalArgumentException("Invalid bundled avatar id");
        String expectedDirectory="builtin-guide".equals(catalogId)?"avatars/builtin-guide":"avatars/catalog/"+catalogId;
        if(!directory.equals(expectedDirectory))throw new IllegalArgumentException("Bundled avatar id and directory differ");
        if(modelDigest==null||manifestDigest==null||!modelDigest.matches("[0-9a-f]{64}")||!manifestDigest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Bundled avatar digests required");
        avatarAssets=java.util.Objects.requireNonNull(assets);bundledAvatarDirectory=directory;bundledAvatarId=catalogId;
        bundledModelDigest=modelDigest;bundledManifestDigest=manifestDigest;bundledSelectionExplicit=explicitSelection;
    }
    private AvatarGpuScene loadBundledRuntimeAvatar()throws Exception {
        AvatarGpuScene.DrawMode mode=avatarBatched?AvatarGpuScene.DrawMode.BATCHED:AvatarGpuScene.DrawMode.INDIVIDUAL;
        try {
            AvatarGpuScene loaded=AvatarGpuScene.bundled(avatarAssets,bundledAvatarDirectory,bundledModelDigest,
                    bundledManifestDigest,multiview||verifyMultiview,avatarAsynchronous,mode,ormRg8Requested,pbrFastMathRequested);
            avatarSourceKind="bundled";avatarPackageId=bundledAvatarId;return loaded;
        } catch(Exception failure) {
            avatarLoadWarning="所选角色无法读取，请返回角色库重新选择。";
            avatarSourceKind="bundled_load_failed";avatarPackageId=bundledAvatarId;throw failure;
        }
    }
    synchronized void setAvatarBatched(boolean value) {
        if(surfaceInitialized)throw new IllegalStateException("Avatar drawing backend must be configured before GL initialization");
        avatarBatched=value;
    }
    synchronized void setPrivateHeadDiagnostic(java.io.File files) {
        if(surfaceInitialized||!runtimeMode)throw new IllegalStateException("Private head must be configured before runtime GL initialization");
        privateHeadFiles=java.util.Objects.requireNonNull(files);
    }
    String avatarWarning(){return avatarLoadWarning;}
    /** Called only before the first GL frame; disk parsing never runs on the UI thread. */
    private AvatarGpuScene loadRuntimeAvatar() throws Exception {
        avatarLoadWarning="";
        avatarPackageId="";avatarSourceKind="pending";
        if(privateHeadFiles!=null) {
            AvatarGpuScene.DrawMode mode=avatarBatched?AvatarGpuScene.DrawMode.BATCHED:AvatarGpuScene.DrawMode.INDIVIDUAL;
            AvatarGpuScene scene=AvatarGpuScene.privateHeadCheck(privateHeadFiles,multiview||verifyMultiview,avatarAsynchronous,mode,ormRg8Requested,pbrFastMathRequested);
            avatarSourceKind="private_head_test";return scene;
        }
        if(bundledAvatarDirectory!=null&&bundledSelectionExplicit)return loadBundledRuntimeAvatar();
        AvatarPackageStore.LoadedPackage selected=null;
        if(avatarStoreRoot!=null&&AvatarPackageStore.supportsAndroidApi(avatarStoreApi)) {
            try {
                AvatarPackageStore store=AvatarPackageStore.open(avatarStoreRoot,avatarStoreApi);
                store.recover();selected=store.readCurrent();
            }
            catch(Exception failure) {
                String detail=failure.getClass().getSimpleName()+": "+failure.getMessage();
                if(detail.length()>240)detail=detail.substring(0,240);
                avatarLoadWarning="已选角色读取失败，暂用内置角色；原角色包保留。"+detail;
                Log.e("MirrorRuntime","Selected avatar could not be loaded; preserving package",failure);
            }
        }
        AvatarGpuScene.DrawMode mode=avatarBatched?AvatarGpuScene.DrawMode.BATCHED:AvatarGpuScene.DrawMode.INDIVIDUAL;
        // GPU failures, including an unsupported debug batch, must remain visible failures.
        // Never turn a failed experimental renderer into an apparently successful control.
        if(selected!=null) {
            AvatarGpuScene scene=AvatarGpuScene.fromAsset(selected.asset,selected.manifestJson,
                    selected.ticket.modelSha256,multiview||verifyMultiview,avatarAsynchronous,mode,ormRg8Requested,pbrFastMathRequested);
            avatarPackageId=selected.ticket.packageId;avatarSourceKind="imported";return scene;
        }
        if(bundledAvatarDirectory!=null)return loadBundledRuntimeAvatar();
        AvatarGpuScene scene=AvatarGpuScene.builtin(avatarAssets,multiview||verifyMultiview,avatarAsynchronous,mode,ormRg8Requested,pbrFastMathRequested);
        avatarSourceKind="builtin";return scene;
    }
    synchronized void setAvatarAsynchronous(boolean value) {
        if(surfaceInitialized)throw new IllegalStateException("Avatar backend must be configured before GL initialization");
        avatarAsynchronous=value;
    }
    void closeRuntimeAvatar(){avatarShutdown=true;AvatarGpuScene previous=avatarScene;if(previous!=null)previous.stopCpu();}
    /** UI thread signals lifecycle explicitly; only the GL thread resets pose progress. */
    synchronized void resumeRuntimeAvatar(){runtimeGlLifecycle.invalidate();avatarResumeGeneration++;pacingEpoch++;}
    void pauseRuntimeAvatar(){runtimeGlLifecycle.invalidate();}
    /** Successful GL submission, not proof of a physical presentation timestamp. */
    boolean hasRuntimeFrame(){return runtimeGlLifecycle.snapshot().ready()&&error.isEmpty();}
    void setInteractiveSnapshot(InteractionController.Snapshot snapshot,boolean active) {
        interactiveFace=active?new InteractiveFace(snapshot.renderWeights(),snapshot.blendshapes52(),snapshot.pose(),SystemClock.elapsedRealtimeNanos())
                :InteractiveFace.NEUTRAL;
    }
    /** Safe to call from inference/UI threads: caller-owned arrays never reach the GL thread. */
    void setInteractiveFace(float[] fourWeights,float[] columnMajorPose,boolean active) {
        interactiveFace=active?new InteractiveFace(fourWeights,columnMajorPose,SystemClock.elapsedRealtimeNanos())
                :InteractiveFace.NEUTRAL;
    }
    void setProfileStages(boolean enabled) { profileStages=enabled; }
    synchronized void setTargetFps(int value) {
        if(value<0||value>60) throw new IllegalArgumentException("Invalid render target FPS");
        if(targetFps!=value) {targetFps=value;pacingEpoch++;}
        Thread thread=renderThread;
        if(thread!=null) java.util.concurrent.locks.LockSupport.unpark(thread);
    }
    void setPipeline(boolean enabled) { pipeline=enabled; }
    void setLookup(boolean enabled,boolean verify) { lookup=enabled; verifyLookup=verify; }
    void setCull(boolean enabled,boolean verify) { cull=enabled; verifyCull=verify; }
    void setAtlas(boolean enabled,boolean verify) { atlas=enabled; verifyAtlas=verify; }
    void setAtlasCopy(boolean enabled) { atlasCopy=enabled; if(enabled) atlas=true; }
    void setMultiview(boolean enabled,boolean verify) { multiview=enabled; verifyMultiview=verify; }
    synchronized void setPersistentMultiviewFbos(boolean enabled) {
        if(surfaceInitialized)throw new IllegalStateException("Framebuffer backend must be configured before GL initialization");
        persistentFbosRequested=enabled;
    }
    synchronized void setCachedCameraVp(boolean enabled) {
        if(surfaceInitialized)throw new IllegalStateException("Camera matrix mode must be configured before GL initialization");
        cachedCameraVpRequested=enabled;
    }
    synchronized void setGpuProfile(boolean enabled) {
        if(surfaceInitialized)throw new IllegalStateException("GPU sampling must be configured before GL initialization");
        gpuProfileRequested=enabled;
    }
    synchronized void setOrmRg8(boolean enabled) {
        if(surfaceInitialized)throw new IllegalStateException("ORM upload mode must be configured before GL initialization");
        ormRg8Requested=enabled;
    }
    synchronized void setPbrFastMath(boolean enabled) {
        if(surfaceInitialized)throw new IllegalStateException("PBR shader mode must be configured before GL initialization");
        pbrFastMathRequested=enabled;
    }
    synchronized void setStaticBackgroundCache(boolean enabled) {
        if(surfaceInitialized)throw new IllegalStateException("Background cache must be configured before GL initialization");
        staticBackgroundCacheRequested=enabled;
    }
    void setPreblend(boolean enabled,boolean verify) { preblend=enabled; verifyPreblend=verify; }
    void setExplicitLod(boolean enabled) { explicitLod=enabled; }
    void setVerifyCombined(boolean enabled) { verifyCombined=enabled; }
    void setDiscardDepth(boolean enabled,boolean verify) { discardDepth=enabled; verifyDiscardDepth=verify; }
    void setSharedPhase(boolean enabled,boolean verify) { sharedPhase=enabled; verifySharedPhase=verify; }
    void setViewSize(int suppliedWidth,int suppliedHeight) {
        if(suppliedWidth<1||suppliedHeight<1||suppliedWidth>4096||suppliedHeight>4096)
            throw new IllegalArgumentException("Invalid explicit view dimensions");
        requestedViewWidth=suppliedWidth; requestedViewHeight=suppliedHeight;
    }
    void setWidthScale(float value) {
        if(!Float.isFinite(value)||value<=0||value>1) throw new IllegalArgumentException("Invalid horizontal view scale");
        widthScale=value;
    }
    void setPanelParameters(float suppliedPitch,float suppliedTan,String pitchUnits) {
        if(!Float.isFinite(suppliedPitch)||!Float.isFinite(suppliedTan)||suppliedPitch<=0||
                !("pixels".equals(pitchUnits)||"subpixels".equals(pitchUnits)))
            throw new IllegalArgumentException("Invalid panel pitch/tan/units");
        // Shader x is measured in RGB subpixels, while gl_FragCoord.y is pixels.
        pitch=suppliedPitch*("pixels".equals(pitchUnits)?3:1);
        tilt=suppliedTan*3;
        panelCalibration=null; // Legacy benchmark shader and defaults remain unchanged.
        calibration="user pitch="+suppliedPitch+" "+pitchUnits+", tan="+suppliedTan+
                " horizontal pixels/vertical pixel; optical alignment unverified";
    }
    synchronized void setPanelCalibration(PanelCalibration value) {
        if(surfaceInitialized) throw new IllegalStateException("Panel changes require a new GL surface");
        if(value==null) throw new IllegalArgumentException("Panel calibration required");
        applyPanel(value);
    }
    private void applyPanel(PanelCalibration value) {
        panelCalibration=value; pitch=value.pitchSubpixels(); tilt=value.tiltSubpixelsPerPixel();
        calibration="pitch="+value.pitch()+" "+value.pitchUnits()+", tan="+value.tan()+", phase="+value.phaseCycles()
                +", "+value.subpixelOrder()+", reverse="+value.reverseViews()+", origin="+value.yOrigin()+"; optical alignment unverified";
    }
    void setCalibrationPattern(boolean enabled) {
        if(surfaceInitialized||scene) throw new IllegalStateException("Test pattern requires a new scene-free renderer");
        calibrationPattern=enabled;
    }
    private String calibrationShader(String source) {
        // ES 3.20 precise is required to prevent reciprocal-multiply + phase from becoming one FMA.
        // Keep both stages at the same language version. The legacy bench remains byte-for-byte ES 3.00.
        return panelCalibration==null?source:source.replace("#version 300 es","#version 320 es")
                .replace(LEGACY_VIEW_FUNCTION,PANEL_VIEW_FUNCTION);
    }
    boolean supportsSharedPhase() {
        return views==20&&Math.abs(pitch-10)<=1e-6f&&(panelCalibration==null
                ||(panelCalibration.phaseCycles()==0&&panelCalibration.subpixelOrder()==PanelCalibration.SubpixelOrder.RGB
                &&!panelCalibration.reverseViews()&&panelCalibration.yOrigin()==PanelCalibration.YOrigin.BOTTOM));
    }
    synchronized void beginMeasurement() {
        measuring=!runtimeMode; firstFrame=0; lastFrame=0; workMs.clear(); intervalMs.clear();
        sceneStageMs.clear(); interlaceStageMs.clear();
        runtimeFrames=0; runtimeWindowStart=0; runtimeWindowFrames=0;
        runtimeWindowWorkMs=0; runtimeFps=0; runtimeWorkMeanMs=0;
        preblendUpdates=0;
        renderDeadline=0;
        framePacing.reset();pacingMeasurementGeneration++;pacingEpoch++;
    }

    @Override public void onSurfaceCreated(GL10 ignored,EGLConfig config) {
        glContextGeneration=runtimeGlLifecycle.contextCreated();
        RuntimeGpuProfile previousGpuProfile=gpuProfile;
        gpuProfile=null;
        // EGL owns destruction of the lost context: never delete/reuse its numeric framebuffer names.
        persistentFbos=null;persistentFboCount=0;multiviewFboActual="uninitialized";
        cameraVpCache.invalidate();cameraVpActual="uninitialized";
        // A new EGL context destroys its predecessor's objects; numeric names must never be deleted here.
        backgroundCache=null;backgroundCacheScene=null;backgroundCacheState=null;backgroundCacheVp=null;
        backgroundCacheActual=staticBackgroundCacheRequested?"uninitialized":"disabled";backgroundCacheWarning="";backgroundCacheBytes=0;backgroundCacheBuilds=0;
        // The predecessor context owns destruction of its numeric texture name.
        screenBackground=runtimeMode?new SceneBackgroundTexture(avatarAssets):null;
        synchronized(this) {
            surfaceInitialized=true;
            framePacing.reset();pacingMeasurementGeneration++;pacingEpoch++;
        }
        renderThread=Thread.currentThread();
        runtimePoseTime=0;
        try {
            if(staticBackgroundCacheRequested&&(!runtimeMode||privateHeadFiles==null||avatarBatched||atlas||verifyMultiview||verifyCombined))
                throw new IllegalArgumentException("Background cache experiment requires private-head runtime individual array rendering");
            if(cachedCameraVpRequested&&(!runtimeMode||avatarAssets==null))
                throw new IllegalArgumentException("Cached camera matrices require runtime avatar rendering");
            if(persistentFbosRequested&&(!runtimeMode||avatarAssets==null||!multiview||verifyMultiview||atlas))
                throw new IllegalArgumentException("Persistent FBO experiment requires runtime avatar OVR array rendering");
            for(int i=0;i<fences.length;i++) fences[i]=0;
            fenceSlot=0;
            texture=0; fbo=0; depth=0; lookupTexture=0; lookupMismatch=-1;
            atlasTexture=0; atlasFbo=0; atlasDepth=0;
            multiviewDepth=0; multiviewFbo=0;
            lastBlendedWeights=null; preblendUpdates=0;
            // onSurfaceCreated means a new EGL context: old names must not be deleted in this context.
            AvatarGpuScene previous=avatarScene;if(previous!=null)previous.stopCpu();avatarScene=null;
            gpu=GLES30.glGetString(GLES30.GL_RENDERER)+" / "+GLES30.glGetString(GLES30.GL_VERSION);
            extensions=GLES30.glGetString(GLES30.GL_EXTENSIONS);
            int[] limit=new int[1];
            GLES30.glGetIntegerv(GLES30.GL_DEPTH_BITS,limit,0); windowDepthBits=limit[0];
            GLES30.glGetIntegerv(GLES30.GL_MAX_ARRAY_TEXTURE_LAYERS,limit,0); maxArrayLayers=limit[0];
            if(extensions.contains("GL_OVR_multiview")) {
                GLES30.glGetIntegerv(0x9631,limit,0); maxMultiviewViews=limit[0];
            }
            if(views>maxArrayLayers) throw new IllegalArgumentException("Views exceed GPU array layer limit");
            if(multiview||verifyMultiview) {
                if(maxMultiviewViews<4||!extensions.contains("GL_OVR_multiview2")||views%4!=0||(atlas&&!atlasCopy))
                    throw new IllegalArgumentException("Four-view batches require OVR_multiview2, divisible-by-four views, and array storage");
                multiviewProgram=program(multiviewVertex(SCENE_VERTEX),SCENE_FRAGMENT);
            }
            sceneProgram=program(SCENE_VERTEX,SCENE_FRAGMENT);
            interlaceProgram=program(calibrationShader(SCREEN_VERTEX),calibrationShader(INTERLACE_FRAGMENT));
            if(runtimeMode)runtimeBackgroundProgram=program(calibrationShader(SCREEN_VERTEX),calibrationShader(SceneBackground.fragment(INTERLACE_FRAGMENT)));
            if(sharedPhase||verifySharedPhase) {
                if(!supportsSharedPhase())
                    throw new IllegalArgumentException("Shared RGB phase requires 20 views, pitch=10 subpixels, phase=0, RGB, forward views and bottom origin");
                // Adjacent RGB subpixels advance exactly two views at this pitch/count.
                sharedPhaseProgram=program(calibrationShader(SCREEN_VERTEX),calibrationShader(INTERLACE_FRAGMENT).replace(
                        "void main(){color=vec4(texture(uViews,vec3(vUv,viewAt(0.0))).r,texture(uViews,vec3(vUv,viewAt(1.0))).g,texture(uViews,vec3(vUv,viewAt(2.0))).b,1);}",
                        "void main(){float r=viewAt(0.0),g=r<18.0?r+2.0:r-18.0,b=r<16.0?r+4.0:r-16.0;"
                        +"color=vec4(textureLod(uViews,vec3(vUv,r),0.0).r,textureLod(uViews,vec3(vUv,g),0.0).g,textureLod(uViews,vec3(vUv,b),0.0).b,1);}"));
            }
            if(explicitLod) {
                String shader=calibrationShader(INTERLACE_FRAGMENT);
                for(String channel:new String[]{"0.0","1.0","2.0"}) shader=shader.replace(
                        "texture(uViews,vec3(vUv,viewAt("+channel+")))",
                        "textureLod(uViews,vec3(vUv,viewAt("+channel+")),0.0)");
                explicitLodProgram=program(calibrationShader(SCREEN_VERTEX),shader);
            }
            if(preblend||verifyPreblend) {
                preblendProgram=program(PREBLEND_VERTEX,SCENE_FRAGMENT,new String[]{"tfPosition","tfNormal","tfUv"});
                preblendSceneProgram=program(PREBLEND_SCENE_VERTEX,SCENE_FRAGMENT);
                if(multiview||verifyMultiview) preblendMultiviewProgram=program(multiviewVertex(PREBLEND_SCENE_VERTEX),SCENE_FRAGMENT);
            }
            if(atlas||verifyAtlas) atlasProgram=program(calibrationShader(SCREEN_VERTEX),calibrationShader(ATLAS_FRAGMENT));
            if(lookup||verifyLookup) {
                lookupProgram=program(SCREEN_VERTEX,LOOKUP_FRAGMENT);
                lookupGenerator=program(calibrationShader(SCREEN_VERTEX),calibrationShader(LOOKUP_GENERATOR));
            }
            if(avatarAssets!=null) {
                avatarScene=loadRuntimeAvatar();
                if(avatarShutdown)avatarScene.stopCpu();
            }
            else makeMesh();
            if(gpuProfileRequested) {
                if(!runtimeMode||avatarAssets==null)throw new IllegalArgumentException("GPU sampling requires a runtime avatar");
                gpuProfile=new RuntimeGpuProfile(true,glContextGeneration,previousGpuProfile);
            }
        } catch(Throwable problem) { fail(problem); }
    }
    @Override public void onSurfaceChanged(GL10 ignored,int width,int height) {
        runtimeGlLifecycle.invalidate();
        cameraVpCache.invalidate();cameraVpActual="uninitialized";
        synchronized(this) {pacingEpoch++;}
        this.width=width; this.height=height;
        viewWidth=requestedViewWidth>0?requestedViewWidth:Math.max(1,Math.round(width*widthScale));
        viewHeight=requestedViewHeight>0?requestedViewHeight:Math.max(1,Math.round(height*scale));
        try {
            if(cachedCameraVpRequested)cameraVpCache.prepare(views,width,height);
            cameraVpActual=cachedCameraVpRequested?"cached":"per_frame";
            releaseBackgroundCache();backgroundCacheActual=staticBackgroundCacheRequested?"uninitialized":"disabled";backgroundCacheWarning="";
            releasePersistentFbos();multiviewFboActual="uninitialized";
            int[] ids=new int[1];
            if(texture!=0) GLES30.glDeleteTextures(1,new int[]{texture},0);
            GLES30.glGenTextures(1,ids,0); texture=ids[0];
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,texture);
            // https://developer.android.com/reference/android/opengl/GLES30#glTexStorage3D(int,int,int,int,int,int)
            GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY,1,GLES30.GL_RGBA8,viewWidth,viewHeight,views);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
            if(fbo==0) { GLES30.glGenFramebuffers(1,ids,0); fbo=ids[0]; }
            if(depth!=0) GLES30.glDeleteRenderbuffers(1,new int[]{depth},0);
            GLES30.glGenRenderbuffers(1,ids,0); depth=ids[0];
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER,depth);
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER,GLES30.GL_DEPTH_COMPONENT16,viewWidth,viewHeight);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo);
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER,GLES30.GL_DEPTH_ATTACHMENT,GLES30.GL_RENDERBUFFER,depth);
            for(int v=0;v<views;v++) {
                GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,texture,0,v);
                if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)
                    throw new IllegalStateException("Framebuffer incomplete");
                GLES30.glViewport(0,0,viewWidth,viewHeight);
                GLES30.glClearColor((v%3+1)/4f,((v+1)%3+1)/4f,((v+2)%3+1)/4f,1);
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);
            if(atlas||verifyAtlas) makeAtlas();
            if(multiview||verifyMultiview) makeMultiviewDepth();
            if(verifyCull&&scene) verifyCullingPixels();
            if(verifyAtlas&&scene) verifyAtlasPixels();
            if(verifyMultiview&&scene) verifyMultiviewPixels();
            if(verifyPreblend&&scene) verifyPreblendPixels();
            if(verifyCombined&&scene) verifyCombinedPixels();
            if(verifyDiscardDepth&&scene) {
                boolean saved=discardDepth;
                try { discardDepthVerification=compareScenePixels("Store versus discard completed depth attachments",value->discardDepth=value); }
                finally { discardDepth=saved; }
                validateScenePixels(discardDepthVerification);
            }
            if(sharedPhase||verifySharedPhase) verifySharedPhasePixels();
            if(lookup||verifyLookup) {
                makeLookup();
                if(verifyLookup) verifyLookupPixels();
            }
            if(calibrationPattern) {
                fillCalibrationPattern();
                if(atlas||verifyAtlas) copyViewsToAtlas();
            }
            GLES30.glFinish(); checkGl();
        } catch(Throwable problem) { fail(problem); }
    }
    @Override public void onDrawFrame(GL10 ignored) {
        if(!error.isEmpty()||texture==0) return;
        long enteredGlEpoch=runtimeGlLifecycle.frameEpoch();
        try {
            long entered=runtimeMode?SystemClock.elapsedRealtimeNanos():0;
            long frameEpoch=pacingEpoch;
            int frameTargetFps=targetFps;
            long pacerWait=0,fenceWait=0,entryLate=0,scheduledStart=0;
            int parkCalls=0;
            boolean deadlineRebased=false,fenceCalled=false;
            if(frameTargetFps>0) {
                long now=SystemClock.elapsedRealtimeNanos(),period=1_000_000_000L/frameTargetFps;
                if(runtimeMode&&renderDeadline!=0) {
                    entryLate=Math.max(0,now-renderDeadline);
                    deadlineRebased=now-renderDeadline>period;
                }
                if(renderDeadline==0||now-renderDeadline>period) renderDeadline=now;
                scheduledStart=renderDeadline;
                while(now<renderDeadline&&targetFps==frameTargetFps) {
                    long parkStart=runtimeMode?SystemClock.elapsedRealtimeNanos():0;
                    java.util.concurrent.locks.LockSupport.parkNanos(renderDeadline-now);
                    if(runtimeMode) {pacerWait+=SystemClock.elapsedRealtimeNanos()-parkStart;parkCalls++;}
                    now=SystemClock.elapsedRealtimeNanos();
                }
                renderDeadline=targetFps==frameTargetFps?renderDeadline+period:0;
            } else renderDeadline=0;
            long before=SystemClock.elapsedRealtimeNanos();
            if(runtimeMode) updateInteractiveFace(before);
            if(pipeline&&fences[fenceSlot]!=0) {
                long fenceStart=runtimeMode?SystemClock.elapsedRealtimeNanos():0;
                int wait=GLES30.glClientWaitSync(fences[fenceSlot],GLES30.GL_SYNC_FLUSH_COMMANDS_BIT,1_000_000_000L);
                if(runtimeMode) {fenceWait=SystemClock.elapsedRealtimeNanos()-fenceStart;fenceCalled=true;}
                if(wait==GLES30.GL_WAIT_FAILED||wait==GLES30.GL_TIMEOUT_EXPIRED)
                    throw new IllegalStateException("GPU fence did not complete: "+wait);
                GLES30.glDeleteSync(fences[fenceSlot]); fences[fenceSlot]=0;
            }
            boolean split=!runtimeMode&&profileStages&&!pipeline&&measuring&&workMs.size()%30==0;
            if(gpuProfile!=null) {
                gpuFramePacingEpoch=frameEpoch;gpuFrameTarget=frameTargetFps;
                gpuCallbackId=gpuProfile.nextCallback();
                gpuCallbackActive=true;
            }
            if(runtimeMode) {
                runtimePrepareStart=runtimePrepareEnd=SystemClock.elapsedRealtimeNanos();
                runtimeViewTiming.reset();
            }
            if(scene) drawViews();
            long viewsEnd=runtimeMode?SystemClock.elapsedRealtimeNanos():0;
            if(runtimeMode&&runtimeViewTiming.isStarted())runtimeViewTiming.finish(viewsEnd);
            long sceneEnd=before;
            if(split) { GLES30.glFinish(); sceneEnd=SystemClock.elapsedRealtimeNanos(); }
            drawInterlace(lookup);
            long interlaceEnd=runtimeMode?SystemClock.elapsedRealtimeNanos():0;
            // Completion wall time includes CPU submission + GPU execution, not a GPU-only timer.
            if(pipeline) {
                fences[fenceSlot]=GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
                if(fences[fenceSlot]==0) throw new IllegalStateException("Cannot create GPU fence");
                GLES30.glFlush(); fenceSlot=(fenceSlot+1)%fences.length;
            } else GLES30.glFinish();
            checkGl();
            long after=SystemClock.elapsedRealtimeNanos();
            if(runtimeMode)runtimeGlLifecycle.successfulFrame(enteredGlEpoch);
            if(runtimeMode) recordPacedRuntimeFrame(frameTargetFps,frameEpoch,entered,before,after,pacerWait,
                    parkCalls,fenceWait,fenceCalled,entryLate,
                    frameTargetFps>0?Math.max(0,before-scheduledStart):0,deadlineRebased,
                    runtimePrepareStart-before,runtimePrepareEnd-runtimePrepareStart,
                    viewsEnd-runtimePrepareEnd,interlaceEnd-viewsEnd,after-interlaceEnd);
            else recordFrame(before,after,split,sceneEnd);
            if(gpuProfile!=null)gpuProfile.finishCallback();
        } catch(Throwable problem) {
            if(gpuProfile!=null)try{gpuProfile.abort(problem);}catch(Throwable cleanup){if(cleanup!=problem)problem.addSuppressed(cleanup);}
            fail(problem);
        } finally {gpuCallbackActive=false;}
    }
    private synchronized void recordPacedRuntimeFrame(int frameTarget,long frameEpoch,long entered,
            long before,long after,long pacerWait,int parkCalls,long fenceWait,boolean fenceCalled,
            long entryLate,long startLate,boolean deadlineRebased,long preViews,long avatarPrepare,
            long viewSubmission,long interlaceSubmission,long submitTail) {
        framePacing.recordDetailedStages(frameTarget,frameEpoch,entered,before,after,pacerWait,parkCalls,fenceWait,
                fenceCalled,entryLate,startLate,deadlineRebased,frameEpoch!=pacingEpoch,
                preViews,avatarPrepare,viewSubmission,interlaceSubmission,submitTail,
                runtimeViewTiming.isComplete()?runtimeViewTiming:null);
        recordRuntimeFrame(before,after);
    }
    private synchronized void recordFrame(long before,long after,boolean split,long sceneEnd) {
        if(runtimeMode) recordRuntimeFrame(before,after);
        else if(measuring) {
            if(firstFrame==0) firstFrame=before;
            if(lastFrame!=0) intervalMs.add((before-lastFrame)/1e6);
            lastFrame=before; workMs.add((after-before)/1e6);
            if(split) { sceneStageMs.add((sceneEnd-before)/1e6); interlaceStageMs.add((after-sceneEnd)/1e6); }
        }
    }
    private void recordRuntimeFrame(long before,long after) {
        // Constant-space aggregates replace the benchmark's per-frame arrays for an unlimited session.
        runtimeFrames++;
        if(runtimeWindowStart==0) runtimeWindowStart=before;
        runtimeWindowFrames++; runtimeWindowWorkMs+=(after-before)/1e6;
        long elapsed=after-runtimeWindowStart;
        if(elapsed>=1_000_000_000L) {
            runtimeFps=runtimeWindowFrames*1e9/elapsed;
            runtimeWorkMeanMs=runtimeWindowWorkMs/runtimeWindowFrames;
            runtimeWindowStart=after; runtimeWindowFrames=0; runtimeWindowWorkMs=0;
        }
    }
    synchronized JSONObject runtimeStatus() throws Exception {
        RuntimeGlLifecycle.Snapshot glState=runtimeGlLifecycle.snapshot();
        RuntimeGpuProfile currentGpuProfile=gpuProfile;
        return new JSONObject().put("runtime_mode",runtimeMode).put("diagnostic_scene",avatarAssets==null)
                .put("orm_rg8_requested",ormRg8Requested).put("pbr_fast_math_requested",pbrFastMathRequested)
                .put("multiview_fbo_requested",persistentFbosRequested?"persistent_groups":"legacy")
                .put("multiview_fbo_actual",multiviewFboActual).put("persistent_fbo_count",persistentFboCount)
                .put("camera_vp_requested",cachedCameraVpRequested?"cached":"per_frame").put("camera_vp_actual",cameraVpActual)
                .put("static_background_cache_requested",staticBackgroundCacheRequested)
                .put("static_background_cache_actual",backgroundCacheActual).put("static_background_cache_warning",backgroundCacheWarning)
                .put("static_background_cache_bytes",backgroundCacheBytes).put("static_background_cache_builds",backgroundCacheBuilds)
                .put("static_background_cache_qualified",false)
                .put("gpu_profile",currentGpuProfile==null?new JSONObject().put("requested",gpuProfileRequested)
                        .put("state",gpuProfileRequested?"uninitialized":"disabled")
                        .put("query_pool_capacity",0):new JSONObject(currentGpuProfile.statusJson()))
                .put("scene_view",new JSONObject(frameSceneView.toMap()))
                .put("scene_view_requested",new JSONObject(sceneView.toMap()))
                .put("face_playback",facePlaybackStatus)
                .put("background_rendering","screen-plane composition in existing interlace pass; no extra geometry")
                .put("background_image_rgba_bytes",screenBackground==null?0:screenBackground.bytes())
                .put("background_image_uploads",screenBackground==null?0:screenBackground.uploads())
                .put("avatar_load_warning",avatarLoadWarning).put("avatar_batched_requested",avatarBatched)
                .put("avatar_source",avatarSourceKind).put("avatar_package_id",avatarPackageId)
                .put("avatar",avatarScene==null?JSONObject.NULL:avatarScene.status())
                .put("output_width",width).put("output_height",height).put("view_count",views)
                .put("view_width",viewWidth).put("view_height",viewHeight)
                .put("projection_aspect",avatarAssets==null?(float)viewWidth/Math.max(1,viewHeight):(float)width/Math.max(1,height))
                .put("frames",runtimeFrames).put("fps",runtimeFps).put("frame_work_mean_ms",runtimeWorkMeanMs)
                .put("runtime_gl_frame_ready",glState.ready()&&error.isEmpty())
                .put("context_generation",glState.contextGeneration()).put("frame_generation",glState.frameGeneration())
                .put("frame_pacing",framePacingStatus())
                .put("render_target_fps",targetFps).put("face_active",runtimeFaceActive).put("error",error)
                .put("panel_calibration",calibration).put("optical_alignment_verified",false)
                .put("retained_frame_samples",workMs.size()+intervalMs.size()+sceneStageMs.size()+interlaceStageMs.size())
                .put("timing_window","approximately one second; callback cadence includes EGL swap backpressure");
    }
    private JSONObject framePacingStatus() throws Exception {
        FramePacingStats.Snapshot stats=framePacing.snapshot();
        JSONArray buckets=new JSONArray();
        for(int i=0;i<stats.bucketCapacity();i++) {
            FramePacingStats.BucketSnapshot b=stats.bucket(i);
            if(b.frames==0)continue;
            JSONObject viewDetails=new JSONObject().put("frames",b.viewDetailFrames)
                    .put("missing_frames",b.frames-b.viewDetailFrames).put("groups",b.viewGroups)
                    .put("covered_view_submission",pacingMetric(b.viewDetailWork));
            for(int stage=0;stage<ViewSubmissionTiming.STAGES;stage++)
                viewDetails.put(ViewSubmissionTiming.stageName(stage),pacingMetric(b.viewDetail(stage)));
            buckets.put(new JSONObject().put("target_fps",b.targetFps).put("frames",b.frames)
                    .put("boundary_frames",b.boundaryFrames).put("park_calls",b.parkCalls)
                    .put("fence_calls",b.fenceCalls).put("deadline_rebases",b.deadlineRebases)
                    .put("pacer_wait",pacingMetric(b.pacerWait)).put("fence_wait",pacingMetric(b.fenceWait))
                    .put("callback_work",pacingMetric(b.work)).put("callback_gap",pacingMetric(b.gap))
                    .put("callback_interval",pacingMetric(b.interval))
                    .put("entry_deadline_lateness",pacingMetric(b.entryLateness))
                    .put("start_deadline_lateness",pacingMetric(b.startLateness))
                    .put("stages",new JSONObject().put("frames",b.stageFrames).put("missing_frames",b.frames-b.stageFrames)
                            .put("pre_views",pacingMetric(b.preViews)).put("avatar_prepare",pacingMetric(b.avatarPrepare))
                            .put("view_submission",pacingMetric(b.viewSubmission))
                            .put("view_submission_detail",viewDetails)
                            .put("interlace_submission",pacingMetric(b.interlaceSubmission))
                            .put("submit_tail",pacingMetric(b.submitTail)).put("covered_callback_work",pacingMetric(b.stageWork))));
        }
        return new JSONObject().put("measurement_generation",pacingMeasurementGeneration)
                .put("continuity_epoch",pacingEpoch).put("frames",stats.frames)
                .put("transition_frames",stats.transitionFrames).put("target_buckets",buckets)
                .put("retained_frame_samples",stats.retainedFrameSamples).put("bucket_capacity",stats.bucketCapacity())
                .put("scope","successful callbacks since GL context creation or beginMeasurement; grouped by captured target, not interaction state; transitions excluded from buckets")
                .put("wait_denominator","per successful stable-target callback, including zero wait; API call counts reported separately")
                .put("stage_scope","five contiguous wall-time stages sum exactly to covered callback work per measured callback; CPU, scheduling and implicit driver stalls, not GPU-exclusive times")
                .put("stage_definitions","pre_views: face update and previous fence; avatar_prepare: current callback pose submit/acquire/upload; view_submission: framebuffer/camera/multiview draw/invalidate; interlace_submission: output draw; submit_tail: new fence/flush/error check")
                .put("stage_overlap","fence_wait is CPU wall time inside glClientWaitSync, already included in pre_views and callback_work; background worker rig/morph/copy are separate concurrent applied-pose metrics, not callback stages")
                .put("stage_no_pose_policy","callbacks with no new worker result still contribute prepare wall time; no avatar means zero prepare")
                .put("view_detail_scope","runtime avatar callbacks only; setup + all groups' attach_clear/camera_matrices/scene_draw/invalidate + tail exactly cover view_submission; means use callback count, not group count; unavailable/legacy/non-avatar callbacks are missing, not zero samples")
                .put("view_detail_boundaries","setup: after pose prepare through framebuffer/viewport setup, including experimental background cache key/capture; attach_clear: attachment rebinding (legacy) or fixed-FBO bind (persistent), then clear per group; camera_matrices: original lookAt/frustum/multiply/copy or cached group copy; scene_draw: ordinary avatar draw or dynamic avatar draw plus cached background restore; invalidate: depth invalidation per group; tail: loop exit, optional atlas work and return; control/timer overhead belongs to its enclosing segment")
                .put("gap_scope","previous measured work end to next callback entry: publication, EGL swap, framework and OS scheduling; not isolated swap or GPU time")
                .put("boundary_policy","first/context/resume/resize/target-change/transition boundaries exclude gap, interval and deadline debt")
                .put("physical_presentation_fps",false);
    }
    private static JSONObject pacingMetric(FramePacingStats.MetricSnapshot m) throws Exception {
        return new JSONObject().put("count",m.count).put("total_ms",m.totalMs)
                .put("mean_ms",m.meanMs).put("max_ms",m.maxMs);
    }
    private void updateInteractiveFace(long now) {
        InteractiveFace face=interactiveFace;
        runtimeFaceActive=face!=InteractiveFace.NEUTRAL&&now-face.timestampNanos<1_000_000_000L;
        if(!runtimeFaceActive) face=InteractiveFace.NEUTRAL;
        double seconds=runtimePoseTime==0?1d/30:Math.max(0,(now-runtimePoseTime)/1e9);
        runtimePoseTime=now;
        float alpha=(float)(1-Math.exp(-seconds/(runtimeFaceActive?.10:.25)));
        boolean changed=false;
        for(int i=0;i<4;i++) {
            // The interaction controller already smooths active expression weights.
            float next=runtimeFaceActive?face.weights[i]:runtimeWeights[i]+alpha*(face.weights[i]-runtimeWeights[i]);
            if(Math.abs(next-face.weights[i])<.0001f) next=face.weights[i];
            changed|=next!=runtimeWeights[i]; runtimeWeights[i]=next;
        }
        // Preserve the preblend cache's reference-based change detection, including a settled idle pose.
        if(changed) expressions=runtimeWeights.clone();
        for(int i=0;i<3;i++) runtimeAngles[i]+=alpha*(face.angles[i]-runtimeAngles[i]);
        for(int i=0;i<52;i++) {
            float next=runtimeFaceActive?face.blendshapes[i]:runtimeBlendshapes[i]+alpha*(face.blendshapes[i]-runtimeBlendshapes[i]);
            if(Math.abs(next-face.blendshapes[i])<.0001f)next=face.blendshapes[i];
            runtimeBlendshapes[i]=next;
        }
    }
    private static final class InteractiveFace {
        static final InteractiveFace NEUTRAL=new InteractiveFace();
        final float[] weights=new float[4],angles=new float[3];
        final float[] blendshapes=new float[52];
        final long timestampNanos;
        private InteractiveFace() { timestampNanos=0; }
        InteractiveFace(float[] values,float[] pose,long timestamp) {
            timestampNanos=timestamp;
            if(values!=null&&values.length==4) for(int i=0;i<4;i++)
                weights[i]=Float.isFinite(values[i])?Math.max(0,Math.min(1,values[i])):0;
            readRotation(pose,angles);
        }
        InteractiveFace(float[] values,float[] fullWeights,float[] pose,long timestamp) {
            this(values,pose,timestamp);
            if(fullWeights==null||fullWeights.length!=52)throw new IllegalArgumentException("Expected full avatar blendshapes");
            for(int i=0;i<52;i++)blendshapes[i]=Float.isFinite(fullWeights[i])?Math.max(0,Math.min(1,fullWeights[i])):0;
        }
        private static void readRotation(float[] pose,float[] out) {
            if(pose==null||pose.length!=16) return;
            for(float value:pose) if(!Float.isFinite(value)) return;
            if(Math.abs(pose[3])>.001f||Math.abs(pose[7])>.001f||Math.abs(pose[11])>.001f||Math.abs(pose[15]-1)>.001f) return;
            // FaceGeometry packs Eigen columns: geometry_pipeline.cc -> MatrixDataProtoFromMatrix.
            // https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/framework/formats/matrix_data.proto
            // Discard translation and normalize each basis column; reject degenerate/reflected/sheared input.
            double[] r=new double[9];
            for(int column=0;column<3;column++) {
                int p=column*4,c=column*3;
                double x=pose[p],y=pose[p+1],z=pose[p+2],norm=Math.sqrt(x*x+y*y+z*z);
                if(norm<1e-6||norm>1e6) return;
                r[c]=x/norm; r[c+1]=y/norm; r[c+2]=z/norm;
            }
            for(int a=0;a<3;a++) for(int b=a+1;b<3;b++)
                if(Math.abs(r[a*3]*r[b*3]+r[a*3+1]*r[b*3+1]+r[a*3+2]*r[b*3+2])>.05) return;
            double determinant=r[0]*(r[4]*r[8]-r[7]*r[5])-r[3]*(r[1]*r[8]-r[7]*r[2])+r[6]*(r[1]*r[5]-r[4]*r[2]);
            if(determinant<.9) return;
            // R = Rz(roll) Ry(yaw) Rx(pitch), matching column-major OpenGL post-multiplication below.
            out[0]=limitDegrees(Math.atan2(r[5],r[8]),45);
            out[1]=limitDegrees(Math.asin(Math.max(-1,Math.min(1,-r[2]))),65);
            out[2]=limitDegrees(Math.atan2(r[1],r[0]),40);
        }
        private static float limitDegrees(double radians,float limit) {
            return (float)Math.max(-limit,Math.min(limit,Math.toDegrees(radians)));
        }
    }
    private void phaseUniforms(int program) {
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"uCount"),views);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program,"uPitch"),pitch);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program,"uTilt"),tilt);
        if(panelCalibration!=null) {
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program,"uPhase"),panelCalibration.phaseCycles());
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program,"uHeight"),height);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"uBgr"),panelCalibration.subpixelOrder()==PanelCalibration.SubpixelOrder.BGR?1:0);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"uReverse"),panelCalibration.reverseViews()?1:0);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"uTop"),panelCalibration.yOrigin()==PanelCalibration.YOrigin.TOP?1:0);
        }
    }
    private void drawInterlace(boolean cached) {
        if(atlas&&cached) throw new IllegalArgumentException("Atlas and lookup are independent experiments");
        if(sharedPhase&&(atlas||cached)) throw new IllegalArgumentException("Shared phase uses arithmetic array interlacing");
        int program=atlas?atlasProgram:(cached?lookupProgram:(sharedPhase?sharedPhaseProgram:(explicitLod?explicitLodProgram:interlaceProgram)));
        if(runtimeMode&&avatarScene!=null&&!atlas&&!cached&&!sharedPhase)program=runtimeBackgroundProgram;
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);
        GLES30.glViewport(0,0,width,height); GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
        GLES30.glUseProgram(program);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(atlas?GLES30.GL_TEXTURE_2D:GLES30.GL_TEXTURE_2D_ARRAY,atlas?atlasTexture:texture);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"uViews"),0);
        if(program==runtimeBackgroundProgram&&runtimeMode){
            screenBackground.bind(frameSceneView.background);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"uBackground"),frameSceneView.background);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"uBackgroundImage"),1);
        }
        if(atlas) {
            GLES30.glUniform2f(GLES30.glGetUniformLocation(program,"uGrid"),atlasColumns,atlasRows);
            GLES30.glUniform2f(GLES30.glGetUniformLocation(program,"uHalfTexel"),.5f/viewWidth,.5f/viewHeight);
        }
        if(cached) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,lookupTexture);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"uLookup"),1);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        } else phaseUniforms(program);
        // OVR multiview forbids timer queries. Only the final draw on framebuffer 0 is measured.
        if(gpuCallbackActive&&gpuProfile!=null)gpuProfile.beginFinal(gpuCallbackId,true);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
        if(gpuCallbackActive&&gpuProfile!=null)gpuProfile.endFinal();
    }
    private void makeLookup() {
        if(lookupTexture!=0) GLES30.glDeleteTextures(1,new int[]{lookupTexture},0);
        int[] ids=new int[1]; GLES30.glGenTextures(1,ids,0); lookupTexture=ids[0];
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,lookupTexture);
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D,1,GLES30.GL_RGBA8UI,width,height);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_NEAREST);
        int[] frame=new int[1]; GLES30.glGenFramebuffers(1,frame,0); GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,frame[0]);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,lookupTexture,0);
        if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Lookup framebuffer incomplete");
        GLES30.glViewport(0,0,width,height); GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glUseProgram(lookupGenerator); phaseUniforms(lookupGenerator);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0); GLES30.glDeleteFramebuffers(1,frame,0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        checkGl();
    }
    private void verifyLookupPixels() {
        // Every channel encodes a distinct value for every view. A repeated three-
        // color fixture would hide index mistakes in a four/eight-view lookup table.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo);
        for(int v=0;v<views;v++) {
            GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,texture,0,v);
            GLES30.glClearColor((v+1f)/(views+1),(views-v)/(views+1f),(v+1f)/(views+1)*.7f,1);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        }
        ByteBuffer reference=ByteBuffer.allocateDirect(width*height*4),candidate=ByteBuffer.allocateDirect(width*height*4);
        drawInterlace(false); GLES30.glReadPixels(0,0,width,height,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,reference);
        drawInterlace(true); GLES30.glReadPixels(0,0,width,height,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,candidate);
        lookupMismatch=0;
        for(int i=0;i<reference.capacity();i++) if(reference.get(i)!=candidate.get(i)) lookupMismatch++;
        if(lookupMismatch!=0) throw new IllegalStateException("Lookup pixel mismatch: "+lookupMismatch);
        checkGl();
    }
    private void fillCalibrationPattern() {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,texture);
        ByteBuffer upload=ByteBuffer.allocateDirect(viewWidth*viewHeight*4);
        for(int v=0;v<views;v++) {
            upload.clear(); upload.put(PanelTestImages.card(v,viewWidth,viewHeight)).flip();
            GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_2D_ARRAY,0,0,0,v,viewWidth,viewHeight,1,
                    GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,upload);
        }
    }
    private void copyViewsToAtlas() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,atlasTexture);
        for(int v=0;v<views;v++) {
            GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,texture,0,v);
            GLES30.glCopyTexSubImage2D(GLES30.GL_TEXTURE_2D,0,v%atlasColumns*viewWidth,v/atlasColumns*viewHeight,
                    0,0,viewWidth,viewHeight);
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);
    }
    private void fillEncodedViews() {
        ByteBuffer pixel=ByteBuffer.allocateDirect(viewWidth*viewHeight*4);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,texture);
        for(int v=0;v<views;v++) {
            pixel.clear();
            for(int p=0;p<viewWidth*viewHeight;p++)pixel.put((byte)PanelTestImages.code(v,0))
                    .put((byte)PanelTestImages.code(v,1)).put((byte)PanelTestImages.code(v,2)).put((byte)255);
            pixel.flip();
            GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_2D_ARRAY,0,0,0,v,viewWidth,viewHeight,1,
                    GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,pixel);
        }
        copyViewsToAtlas();
    }
    /** Diagnostic GL-thread entry. Reads actual RGB bytes; compares each path independently with the CPU oracle. */
    JSONObject verifyPanelPixels(java.util.function.BooleanSupplier cancelled) throws Exception {
        if(Thread.currentThread()!=renderThread||panelCalibration==null||scene||width<1||height<1
                ||atlasProgram==0||lookupGenerator==0||explicitLodProgram==0)
            throw new IllegalStateException("Panel check requires an initialized scene-free calibrated renderer with atlas/lookup/LOD programs");
        if(!error.isEmpty())throw new IllegalStateException(error);
        PanelCalibration savedPanel=panelCalibration;
        boolean savedAtlas=atlas,savedLookup=lookup,savedLod=explicitLod;
        JSONArray checks=new JSONArray(); long mismatches=0; boolean allViewsReached=true;
        ByteBuffer actual=ByteBuffer.allocateDirect(width*height*4);
        // Distinct cases isolate controls, followed by a combined units/signed-slope case.
        PanelCalibration[] cases={PanelCalibration.defaults(),
                new PanelCalibration(10,.2777777f,PanelCalibration.PitchUnits.SUBPIXELS,.137f,PanelCalibration.SubpixelOrder.RGB,false,PanelCalibration.YOrigin.BOTTOM),
                new PanelCalibration(10,.2777777f,PanelCalibration.PitchUnits.SUBPIXELS,0,PanelCalibration.SubpixelOrder.BGR,false,PanelCalibration.YOrigin.BOTTOM),
                new PanelCalibration(10,.2777777f,PanelCalibration.PitchUnits.SUBPIXELS,0,PanelCalibration.SubpixelOrder.RGB,true,PanelCalibration.YOrigin.BOTTOM),
                new PanelCalibration(10,.2777777f,PanelCalibration.PitchUnits.SUBPIXELS,0,PanelCalibration.SubpixelOrder.RGB,false,PanelCalibration.YOrigin.TOP),
                new PanelCalibration(3.5f,-.125f,PanelCalibration.PitchUnits.PIXELS,.375f,PanelCalibration.SubpixelOrder.BGR,true,PanelCalibration.YOrigin.TOP)};
        try {
            fillEncodedViews(); GLES30.glDisable(GLES30.GL_DITHER);
            for(int ci=0;ci<cases.length;ci++) {
                if(cancelled.getAsBoolean())throw new InterruptedException("Panel check cancelled");
                applyPanel(cases[ci]); makeLookup();
                for(int path=0;path<4;path++) {
                    atlas=path==2; lookup=path==3; explicitLod=path==1;
                    drawInterlace(lookup); actual.clear();
                    GLES30.glReadPixels(0,0,width,height,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,actual); checkGl();
                    long bad=0; String first=""; long[] viewComponents=new long[views];
                    for(int y=0;y<height;y++) {
                      if(cancelled.getAsBoolean())throw new InterruptedException("Panel check cancelled");
                      for(int x=0;x<width;x++)for(int channel=0;channel<3;channel++) {
                        int expectedView=cases[ci].viewIndex(x,y,height,channel,views);
                        viewComponents[expectedView]++;
                        int expected=PanelTestImages.code(expectedView,channel), value=actual.get((y*width+x)*4+channel)&255;
                        if(value!=expected) {bad++;if(first.isEmpty())first="x="+x+",y="+y+",channel="+channel+",view="+expectedView+",expected="+expected+",actual="+value;}
                      }
                    }
                    mismatches+=bad;
                    JSONArray coverage=new JSONArray(); boolean reached=true;
                    for(long count:viewComponents){coverage.put(count);reached&=count>0;}
                    allViewsReached&=reached;
                    checks.put(new JSONObject().put("case",ci).put("parameters",calibration)
                            .put("path",new String[]{"array","array_explicit_lod","atlas","lookup"}[path])
                            .put("oracle_view_component_counts",coverage).put("oracle_all_views_reached",reached)
                            .put("rgb_byte_mismatches",bad).put("checked_rgb_bytes",width*(long)height*3).put("first_mismatch",first));
                }
            }
        } finally {
            atlas=savedAtlas; lookup=savedLookup; explicitLod=savedLod; applyPanel(savedPanel); makeLookup();
            if(calibrationPattern)fillCalibrationPattern();
            copyViewsToAtlas(); GLES30.glEnable(GLES30.GL_DITHER);
        }
        return new JSONObject().put("passed",mismatches==0&&allViewsReached).put("rgb_byte_mismatches",mismatches)
                .put("oracle_all_views_reached",allViewsReached)
                .put("width",width).put("height",height).put("view_count",views).put("cases",checks)
                .put("scope","All output pixels and RGB components, independent CPU float oracle; optical alignment is not verified")
                .put("optical_alignment_verified",false);
    }
    private void verifySharedPhasePixels() throws Exception {
        // Every view has a unique value in every channel: detect even a one-view index error at any output pixel.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo); GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
        for(int v=0;v<views;v++) {
            GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,texture,0,v);
            GLES30.glClearColor((v+1f)/(views+1),(views-v)/(views+1f),(v+1f)/(views+1)*.7f,1);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        }
        ByteBuffer reference=ByteBuffer.allocateDirect(width*height*4),candidate=ByteBuffer.allocateDirect(width*height*4);
        boolean saved=sharedPhase; long mismatches=0;
        try {
            sharedPhase=false; drawInterlace(false); GLES30.glReadPixels(0,0,width,height,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,reference);
            sharedPhase=true; drawInterlace(false); GLES30.glReadPixels(0,0,width,height,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,candidate);
            for(int i=0;i<reference.capacity();i++) if(reference.get(i)!=candidate.get(i)) mismatches++;
        } finally { sharedPhase=saved; }
        sharedPhaseVerification=new JSONObject().put("rgba_byte_mismatches",mismatches).put("pixels",width*(long)height)
                .put("scope","Every output pixel/channel versus original formula with 20 distinct view colors; exact current pitch/tan");
        if(mismatches!=0) throw new IllegalStateException("Shared phase differs from original calibration formula: "+mismatches);
        checkGl();
    }
    private void drawViews() {
        if(avatarScene!=null) {drawAvatarViews();return;}
        if(preblend) updatePreblend();
        boolean drawAtlas=atlas&&!atlasCopy;
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,multiview?multiviewFbo:(drawAtlas?atlasFbo:fbo));
        GLES30.glViewport(0,0,viewWidth,viewHeight);
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
        if(drawAtlas) {
            GLES30.glClearColor(.035f,.045f,.06f,1);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST);
        }
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        if(cull) {
            // The generated mesh uses clockwise outward-facing triangles.
            GLES30.glEnable(GLES30.GL_CULL_FACE); GLES30.glFrontFace(GLES30.GL_CW);
            GLES30.glCullFace(GLES30.GL_BACK);
        } else GLES30.glDisable(GLES30.GL_CULL_FACE);
        int activeProgram=preblend?(multiview?preblendMultiviewProgram:preblendSceneProgram)
                :(multiview?multiviewProgram:sceneProgram);
        GLES30.glUseProgram(activeProgram);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,preblend?preblendBuffer:vertexBuffer);
        int[] sizes=preblend?new int[]{3,3,2}:new int[]{3,3,2,3,3,3,3}; int offset=0;
        for(int attribute=0;attribute<sizes.length;attribute++) {
            GLES30.glEnableVertexAttribArray(attribute);
            GLES30.glVertexAttribPointer(attribute,sizes[attribute],GLES30.GL_FLOAT,false,preblend?32:80,offset);
            offset+=sizes[attribute]*4;
        }
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER,indexBuffer);
        float[] weights=expressions;
        GLES30.glUniform4fv(GLES30.glGetUniformLocation(activeProgram,"uWeights"),1,weights,0);
        Matrix.setIdentityM(model,0);
        if(runtimeMode&&Float.isNaN(fixedAngle)) {
            Matrix.rotateM(model,0,runtimeAngles[2],0,0,1);
            Matrix.rotateM(model,0,runtimeAngles[1],0,1,0);
            Matrix.rotateM(model,0,runtimeAngles[0],1,0,0);
        } else {
            float angle=Float.isNaN(fixedAngle)?(SystemClock.uptimeMillis()%12000)/12000f*360:fixedAngle;
            Matrix.rotateM(model,0,angle,0,1,0);
        }
        float aspect=(float)viewWidth/viewHeight;
        int group=multiview?4:1;
        for(int v=0;v<views;v+=group) {
            if(multiview) {
                MultiviewGl.attach(GLES30.GL_COLOR_ATTACHMENT0,texture,v,4);
                MultiviewGl.attach(GLES30.GL_DEPTH_ATTACHMENT,multiviewDepth,v,4);
                GLES30.glClearColor(.035f,.045f,.06f,1);
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);
            } else if(drawAtlas) {
                int x=v%atlasColumns*viewWidth,y=v/atlasColumns*viewHeight;
                GLES30.glViewport(x,y,viewWidth,viewHeight); GLES30.glScissor(x,y,viewWidth,viewHeight);
            } else {
                GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,texture,0,v);
                GLES30.glClearColor(.035f,.045f,.06f,1);
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);
            }
            for(int relative=0;relative<group;relative++) {
                float eye=views==1?0:((v+relative)/(float)(views-1)-.5f)*.4f;
                Matrix.setLookAtM(view,0,eye,0,3,eye,0,0,0,1,0);
                float near=.1f,half=.052f,shift=-eye*near/3;
                Matrix.frustumM(projection,0,-half*aspect+shift,half*aspect+shift,-half,half,near,10);
                Matrix.multiplyMM(mv,0,view,0,model,0); Matrix.multiplyMM(mvp,0,projection,0,mv,0);
                if(multiview) System.arraycopy(mvp,0,multiviewMatrices,relative*16,16);
            }
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(activeProgram,multiview?"uMvps":"uMvp"),group,false,
                    multiview?multiviewMatrices:mvp,0);
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(activeProgram,"uModel"),1,false,model,0);
            int perBatch=indexCount/8;
            for(int batch=0;batch<8;batch++) {
                GLES30.glUniform3f(GLES30.glGetUniformLocation(activeProgram,"uColor"),.65f+batch*.02f,.42f,.30f);
                GLES30.glDrawElements(GLES30.GL_TRIANGLES,perBatch,GLES30.GL_UNSIGNED_SHORT,batch*perBatch*2);
            }
            // Depth is used within this view batch only. It is cleared before reuse; color remains available for interlacing.
            if(discardDepth&&!drawAtlas) GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER,1,DEPTH_ATTACHMENT,0);
        }
        if(discardDepth&&drawAtlas) GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER,1,DEPTH_ATTACHMENT,0);
        for(int attribute=0;attribute<7;attribute++) GLES30.glDisableVertexAttribArray(attribute);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER,0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0);
        if(atlas&&atlasCopy) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);
            for(int v=0;v<views;v++) GLES32.glCopyImageSubData(texture,GLES30.GL_TEXTURE_2D_ARRAY,0,0,0,v,
                    atlasTexture,GLES30.GL_TEXTURE_2D,0,v%atlasColumns*viewWidth,v/atlasColumns*viewHeight,0,
                    viewWidth,viewHeight,1);
        }
    }
    private void drawAvatarViews() {
        frameSceneView=sceneView;
        avatarScene.setSceneView(frameSceneView);
        if(cachedCameraVpRequested)cameraVpCache.prepare(views,width,height,frameSceneView);
        long generation=avatarResumeGeneration;
        if(generation!=consumedAvatarResumeGeneration) {
            avatarScene.resumeCpuPose();consumedAvatarResumeGeneration=generation;
        }
        if(runtimeMode)runtimePrepareStart=SystemClock.elapsedRealtimeNanos();
        FacePlayback.apply(runtimeBlendshapes,runtimeAngles,frameSceneView.mirrorMotion,frameSceneView.expressionGain,playbackBlendshapes,playbackAngles);
        // Publish a completed small snapshot; status readers never observe partially mapped arrays.
        try {
            facePlaybackStatus=new JSONObject().put("mirror_motion",frameSceneView.mirrorMotion).put("expression_gain",frameSceneView.expressionGain)
                    .put("response_policy","artist_response_v3: opposed brows suppressed; blink .04-.72; wide exponent 1.65; gaze/squint/mouthClose unchanged; live calibration pending")
                    .put("raw_jaw_open",runtimeBlendshapes[25]).put("mapped_jaw_open",playbackBlendshapes[25])
                    .put("raw_blink_left",runtimeBlendshapes[9]).put("raw_blink_right",runtimeBlendshapes[10])
                    .put("mapped_blink_left",playbackBlendshapes[9]).put("mapped_blink_right",playbackBlendshapes[10])
                    .put("raw_brow_down_left",runtimeBlendshapes[1]).put("raw_brow_down_right",runtimeBlendshapes[2])
                    .put("raw_brow_inner_up",runtimeBlendshapes[3]).put("mapped_brow_inner_up",playbackBlendshapes[3])
                    .put("mapped_brow_down_left",playbackBlendshapes[1]).put("mapped_brow_down_right",playbackBlendshapes[2])
                    .put("raw_smile_left",runtimeBlendshapes[44]).put("raw_smile_right",runtimeBlendshapes[45])
                    .put("mapped_smile_left",playbackBlendshapes[44]).put("mapped_smile_right",playbackBlendshapes[45])
                    .put("raw_wide_left",runtimeBlendshapes[21]).put("raw_wide_right",runtimeBlendshapes[22])
                    .put("mapped_wide_left",playbackBlendshapes[21]).put("mapped_wide_right",playbackBlendshapes[22])
                    .put("mapped_head_pitch",playbackAngles[0]).put("mapped_head_yaw",playbackAngles[1]).put("mapped_head_roll",playbackAngles[2])
                    .put("scope","Latest GL callback's filtered source and mapped rig inputs; final artist binding curves apply afterwards");
        }catch(Exception invalid){throw new IllegalStateException("Cannot publish face playback status",invalid);}
        avatarScene.prepare(playbackBlendshapes,playbackAngles);
        if(runtimeMode) {
            runtimePrepareEnd=SystemClock.elapsedRealtimeNanos();
            runtimeViewTiming.begin(runtimePrepareEnd,views/(multiview?4:1));
        }
        if(gpuCallbackActive&&gpuProfile!=null)gpuProfile.captureFrameScope(gpuCallbackId,gpuFramePacingEpoch,gpuFrameTarget,runtimeFaceActive,
                frameSceneView,width,height,viewWidth,viewHeight,views);
        boolean drawAtlas=atlas&&!atlasCopy;
        boolean useBackgroundCache=staticBackgroundCacheRequested&&prepareStaticBackgroundCache();
        if(persistentFbosRequested) {
            if(persistentFbos==null)throw new IllegalStateException("Persistent framebuffer groups are not initialized");
            persistentFbos.bind(0,glContextGeneration);
        } else GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,multiview?multiviewFbo:(drawAtlas?atlasFbo:fbo));
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST);GLES30.glViewport(0,0,viewWidth,viewHeight);
        if(drawAtlas) {
            if(runtimeMode)GLES30.glClearColor(0,0,0,0);else GLES30.glClearColor(.035f,.045f,.06f,1);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST);
        }
        // Sampling resolution can be anisotropic; projection follows the physical display shape.
        int group=multiview?4:1;float aspect=(float)width/height;
        if(runtimeMode)runtimeViewTiming.setupComplete(SystemClock.elapsedRealtimeNanos());
        for(int v=0;v<views;v+=group) {
            if(multiview) {
                if(persistentFbosRequested)persistentFbos.bind(v,glContextGeneration);
                else {
                    MultiviewGl.attach(GLES30.GL_COLOR_ATTACHMENT0,texture,v,4);
                    MultiviewGl.attach(GLES30.GL_DEPTH_ATTACHMENT,multiviewDepth,v,4);
                }
            } else if(drawAtlas) {
                int x=v%atlasColumns*viewWidth,y=v/atlasColumns*viewHeight;
                GLES30.glViewport(x,y,viewWidth,viewHeight);GLES30.glScissor(x,y,viewWidth,viewHeight);
            } else GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,texture,0,v);
            if(!drawAtlas) {
                if(runtimeMode)GLES30.glClearColor(0,0,0,0);else GLES30.glClearColor(.035f,.045f,.06f,1);
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);
            }
            if(runtimeMode)runtimeViewTiming.attachmentsComplete(SystemClock.elapsedRealtimeNanos());
            if(useBackgroundCache)System.arraycopy(backgroundCacheVp,v*16,multiviewMatrices,0,group*16);
            else if(cachedCameraVpRequested)cameraVpCache.copyGroup(views,width,height,v,group,multiviewMatrices);
            else for(int relative=0;relative<group;relative++) {
                float eye=frameSceneView.eyeAt(v+relative,views);
                Matrix.setLookAtM(view,0,eye,0,3,eye,0,0,0,1,0);
                float near=.1f,half=.052f,shift=frameSceneView.frustumShift(eye);
                Matrix.frustumM(projection,0,-half*aspect+shift,half*aspect+shift,-half,half,near,10);
                Matrix.multiplyMM(mvp,0,projection,0,view,0);
                System.arraycopy(mvp,0,multiviewMatrices,relative*16,16);
            }
            if(runtimeMode)runtimeViewTiming.cameraComplete(SystemClock.elapsedRealtimeNanos());
            if(useBackgroundCache){
                avatarScene.drawDynamic(multiviewMatrices,group,aspect);
                // Props were originally trailing draws: restore after the head with the original LESS rule.
                backgroundCache.restoreGroup(glContextGeneration,v);
            } else avatarScene.draw(multiviewMatrices,group,aspect);
            if(runtimeMode)runtimeViewTiming.sceneComplete(SystemClock.elapsedRealtimeNanos());
            if(discardDepth&&!drawAtlas)GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER,1,DEPTH_ATTACHMENT,0);
            if(runtimeMode)runtimeViewTiming.groupComplete(SystemClock.elapsedRealtimeNanos());
        }
        if(discardDepth&&drawAtlas)GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER,1,DEPTH_ATTACHMENT,0);
        if(atlas&&atlasCopy) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);
            for(int v=0;v<views;v++)GLES32.glCopyImageSubData(texture,GLES30.GL_TEXTURE_2D_ARRAY,0,0,0,v,
                    atlasTexture,GLES30.GL_TEXTURE_2D,0,v%atlasColumns*viewWidth,v/atlasColumns*viewHeight,0,viewWidth,viewHeight,1);
        }
    }
    /** GL-owner only. Failure disables this experiment until resize/context recreation, with an explicit warning. */
    private boolean prepareStaticBackgroundCache(){
        if(!staticBackgroundCacheRequested){backgroundCacheActual="disabled";return false;}
        if(backgroundCacheActual.equals("fallback"))return false;
        AvatarGpuScene current=avatarScene;
        if(current==null||!current.hasCacheableStaticBackground()){
            releaseBackgroundCache();backgroundCacheActual="ineligible_scene";
            cameraVpActual=cachedCameraVpRequested?"cached":"per_frame";return false;
        }
        // Do not reinterpret an error from the ordinary pose/upload path as a cache allocation failure.
        checkGl();
        try{
            if(backgroundCache==null||backgroundCacheScene!=current){
                releaseBackgroundCache();backgroundCacheScene=current;
                backgroundCacheState=new float[current.staticBackgroundStateFloats()];backgroundCacheVp=new float[views*16];
                backgroundCache=new AvatarBackgroundCache(new AvatarBackgroundGl(current),glContextGeneration,viewWidth,viewHeight,views,multiview?4:1);
            }
            cameraVpCache.prepare(views,width,height,frameSceneView);
            int group=multiview?4:1;
            for(int base=0;base<views;base+=group){
                cameraVpCache.copyGroup(views,width,height,base,group,multiviewMatrices);
                System.arraycopy(multiviewMatrices,0,backgroundCacheVp,base*16,group*16);
            }
            float aspect=(float)width/height;current.copyStaticBackgroundState(aspect,backgroundCacheState);
            backgroundCache.prepare(glContextGeneration,current,current.modelSha256(),aspect,backgroundCacheState,backgroundCacheVp);
            backgroundCacheBytes=backgroundCache.storageBytes();backgroundCacheBuilds=backgroundCache.builds();
            cameraVpActual=cachedCameraVpRequested?"cached":"background_cache_fixed_camera";
            backgroundCacheActual="cached_unverified";return true;
        }catch(RuntimeException failure){
            try{releaseBackgroundCache();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}
            backgroundCacheActual="fallback";backgroundCacheWarning=failure.toString();
            cameraVpActual=cachedCameraVpRequested?"cached":"per_frame";
            if(backgroundCacheWarning.length()>240)backgroundCacheWarning=backgroundCacheWarning.substring(0,240);
            Log.w("MirrorRuntime","Experimental background cache unavailable; ordinary drawing retained",failure);return false;
        }
    }
    private void releaseBackgroundCache(){
        AvatarBackgroundCache previous=backgroundCache;backgroundCache=null;backgroundCacheScene=null;
        backgroundCacheState=null;backgroundCacheVp=null;backgroundCacheBytes=0;backgroundCacheBuilds=0;
        if(previous!=null)previous.close(glContextGeneration);
    }
    private void makeAtlas() {
        atlasColumns=(int)Math.ceil(Math.sqrt(views)); atlasRows=(views+atlasColumns-1)/atlasColumns;
        int w=atlasColumns*viewWidth,h=atlasRows*viewHeight;
        int[] limit=new int[1]; GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE,limit,0);
        if(w>limit[0]||h>limit[0]) throw new IllegalArgumentException("Atlas exceeds GPU texture size");
        if(atlasTexture!=0) GLES30.glDeleteTextures(1,new int[]{atlasTexture},0);
        if(atlasDepth!=0) GLES30.glDeleteRenderbuffers(1,new int[]{atlasDepth},0);
        int[] ids=new int[1]; GLES30.glGenTextures(1,ids,0); atlasTexture=ids[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,atlasTexture);
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D,1,GLES30.GL_RGBA8,w,h);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glGenRenderbuffers(1,ids,0); atlasDepth=ids[0];
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER,atlasDepth);
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER,GLES30.GL_DEPTH_COMPONENT16,w,h);
        if(atlasFbo==0) { GLES30.glGenFramebuffers(1,ids,0); atlasFbo=ids[0]; }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,atlasFbo);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,atlasTexture,0);
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER,GLES30.GL_DEPTH_ATTACHMENT,GLES30.GL_RENDERBUFFER,atlasDepth);
        if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Atlas framebuffer incomplete");
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);
    }
    private void updatePreblend() {
        float[] weights=expressions;
        if(weights==lastBlendedWeights) return;
        // OVR disallows active transform feedback on a multiview framebuffer.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);
        GLES30.glUseProgram(preblendProgram);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,vertexBuffer);
        int[] sizes={3,3,2,3,3,3,3}; int offset=0;
        for(int attribute=0;attribute<sizes.length;attribute++) {
            GLES30.glEnableVertexAttribArray(attribute);
            GLES30.glVertexAttribPointer(attribute,sizes[attribute],GLES30.GL_FLOAT,false,80,offset);
            offset+=sizes[attribute]*4;
        }
        GLES30.glUniform4fv(GLES30.glGetUniformLocation(preblendProgram,"uWeights"),1,weights,0);
        GLES30.glBindBufferBase(GLES30.GL_TRANSFORM_FEEDBACK_BUFFER,0,preblendBuffer);
        GLES30.glEnable(GLES30.GL_RASTERIZER_DISCARD);
        GLES30.glBeginTransformFeedback(GLES30.GL_POINTS);
        GLES30.glDrawArrays(GLES30.GL_POINTS,0,vertexCount);
        GLES30.glEndTransformFeedback(); GLES30.glDisable(GLES30.GL_RASTERIZER_DISCARD);
        GLES30.glBindBufferBase(GLES30.GL_TRANSFORM_FEEDBACK_BUFFER,0,0);
        for(int attribute=0;attribute<7;attribute++) GLES30.glDisableVertexAttribArray(attribute);
        lastBlendedWeights=weights; preblendUpdates++;
    }
    private void verifyPreblendPixels() throws Exception {
        boolean saved=preblend;
        try { preblendVerification=compareScenePixels("Per-view morph versus shared transform-feedback morph",value->preblend=value); }
        finally { preblend=saved; }
        validateScenePixels(preblendVerification);
    }
    private void makeMultiviewDepth() {
        if(multiviewDepth!=0) GLES30.glDeleteTextures(1,new int[]{multiviewDepth},0);
        int[] ids=new int[1]; GLES30.glGenTextures(1,ids,0); multiviewDepth=ids[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,multiviewDepth);
        GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY,1,GLES30.GL_DEPTH_COMPONENT16,viewWidth,viewHeight,views);
        if(persistentFbosRequested) {
            persistentFbos=PersistentMultiviewFbos.create(PersistentMultiviewGl.INSTANCE,glContextGeneration,views,texture,multiviewDepth);
            persistentFboCount=persistentFbos.groups();multiviewFboActual="persistent_groups";
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);return;
        }
        if(multiviewFbo==0) { GLES30.glGenFramebuffers(1,ids,0); multiviewFbo=ids[0]; }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,multiviewFbo);
        MultiviewGl.attach(GLES30.GL_COLOR_ATTACHMENT0,texture,0,4);
        MultiviewGl.attach(GLES30.GL_DEPTH_ATTACHMENT,multiviewDepth,0,4);
        if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Multiview framebuffer incomplete");
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0); checkGl();
        multiviewFboActual="legacy";
    }
    private void releasePersistentFbos() {
        PersistentMultiviewFbos previous=persistentFbos;persistentFbos=null;persistentFboCount=0;
        if(previous!=null)previous.close(glContextGeneration);
    }
    private void verifyMultiviewPixels() throws Exception {
        boolean saved=multiview;
        try { multiviewVerification=compareScenePixels("Serial versus OVR four-view batches",value->multiview=value); }
        finally { multiview=saved; }
        validateScenePixels(multiviewVerification);
    }
    private void verifyAtlasPixels() throws Exception {
        boolean saved=atlas;
        try { atlasVerification=compareScenePixels("Texture array versus atlas",value->atlas=value); }
        finally { atlas=saved; }
        validateScenePixels(atlasVerification);
    }
    private void verifyCullingPixels() throws Exception {
        boolean saved=cull;
        try { cullVerification=compareScenePixels("Two-sided versus backface-culled closed mesh",value->cull=value); }
        finally { cull=saved; }
        long rgbBytes=width*height*3L*5;
        if(cullVerification.getInt("max_rgb_error")>1||cullVerification.getInt("alpha_mismatches")!=0
                ||cullVerification.getLong("rgb_byte_mismatches")>rgbBytes/1_000_000)
            throw new IllegalStateException("Culling changed output: "+cullVerification);
    }
    private JSONObject compareScenePixels(String scope,java.util.function.Consumer<Boolean> mode) throws Exception {
        float[] savedExpressions=expressions; float savedAngle=fixedAngle;
        ByteBuffer expected=ByteBuffer.allocateDirect(width*height*4);
        ByteBuffer actual=ByteBuffer.allocateDirect(width*height*4);
        long differences=0,squared=0; int max=0,alpha=0;
        float[] angles={0,31,90,180,277};
        try {
            for(int fixture=0;fixture<angles.length;fixture++) {
                fixedAngle=angles[fixture];
                expressions=fixture%2==0?new float[]{0,0,0,0}:new float[]{1,1,1,1};
                mode.accept(false); drawViews(); drawInterlace(false);
                GLES30.glReadPixels(0,0,width,height,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,expected);
                mode.accept(true); drawViews(); drawInterlace(false);
                GLES30.glReadPixels(0,0,width,height,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,actual);
                checkGl();
                for(int i=0;i<expected.capacity();i++) {
                    int delta=Math.abs((expected.get(i)&255)-(actual.get(i)&255));
                    if(i%4==3) { if(delta>0) alpha++; }
                    else { if(delta>0) differences++; squared+=(long)delta*delta; max=Math.max(max,delta); }
                }
            }
        } finally { expressions=savedExpressions; fixedAngle=savedAngle; }
        return new JSONObject().put("fixtures",angles.length).put("views",views)
                .put("angles_degrees",new JSONArray(angles)).put("rgb_byte_mismatches",differences)
                .put("max_rgb_error",max).put("alpha_mismatches",alpha)
                .put("rmse",Math.sqrt(squared/(double)(width*height*3L*angles.length)))
                .put("scope",scope+"; full interlaced output, neutral/maximum morph weights");
    }
    private static void validateScenePixels(JSONObject report) throws Exception {
        if(report.getInt("max_rgb_error")>1||report.getInt("alpha_mismatches")!=0||report.getDouble("rmse")>.1)
            throw new IllegalStateException("Scene output changed: "+report);
    }
    private void verifyCombinedPixels() throws Exception {
        if(atlas||lookup) throw new IllegalArgumentException("Combined check covers array storage with arithmetic interlace");
        boolean savedCull=cull,savedMultiview=multiview,savedPreblend=preblend,savedLod=explicitLod,savedDiscard=discardDepth,savedPhase=sharedPhase;
        try {
            combinedVerification=compareScenePixels("Original scene versus selected combined optimizations",enabled->{
                cull=enabled&&savedCull; multiview=enabled&&savedMultiview;
                preblend=enabled&&savedPreblend; explicitLod=enabled&&savedLod;
                discardDepth=enabled&&savedDiscard;
                sharedPhase=enabled&&savedPhase;
            });
        } finally {
            cull=savedCull; multiview=savedMultiview; preblend=savedPreblend; explicitLod=savedLod;
            discardDepth=savedDiscard;
            sharedPhase=savedPhase;
        }
        validateScenePixels(combinedVerification);
    }
    private void makeMesh() {
        int segments=128,rings=80;
        vertexCount=(segments+1)*(rings+1);
        ByteBuffer vertices=ByteBuffer.allocateDirect((segments+1)*(rings+1)*80).order(ByteOrder.nativeOrder());
        for(int r=0;r<=rings;r++) for(int s=0;s<=segments;s++) {
            double a=Math.PI*r/rings,b=2*Math.PI*s/segments;
            float x=(float)(Math.sin(a)*Math.cos(b)),y=(float)Math.cos(a),z=(float)(Math.sin(a)*Math.sin(b));
            vertices.putFloat(x*.72f).putFloat(y).putFloat(z*.65f);
            vertices.putFloat(x).putFloat(y).putFloat(z).putFloat(s/(float)segments).putFloat(r/(float)rings);
            float front=Math.max(0,z),mouth=(float)Math.exp(-6*x*x-12*(y+.35f)*(y+.35f))*front;
            float eye=(float)Math.exp(-35*(Math.abs(x)-.32f)*(Math.abs(x)-.32f)-30*(y-.2f)*(y-.2f))*front;
            float brow=(float)Math.exp(-12*x*x-35*(y-.45f)*(y-.45f))*front;
            vertices.putFloat(0).putFloat(-.18f*mouth).putFloat(.03f*mouth);
            vertices.putFloat(0).putFloat(-.1f*eye).putFloat(0);
            vertices.putFloat(.025f*x*mouth).putFloat(.07f*Math.abs(x)*mouth).putFloat(0);
            vertices.putFloat(0).putFloat(.07f*brow).putFloat(0);
        }
        vertices.position(0);
        indexCount=segments*rings*6;
        ByteBuffer indices=ByteBuffer.allocateDirect(indexCount*2).order(ByteOrder.nativeOrder());
        for(int r=0;r<rings;r++) for(int s=0;s<segments;s++) {
            int a=r*(segments+1)+s,b=a+segments+1;
            for(int index:new int[]{a,b,a+1,a+1,b,b+1}) indices.putShort((short)index);
        }
        indices.position(0);
        int[] ids=new int[2]; GLES30.glGenBuffers(2,ids,0); vertexBuffer=ids[0]; indexBuffer=ids[1];
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,vertexBuffer);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,vertices.capacity(),vertices,GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER,indexBuffer);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER,indices.capacity(),indices,GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0); GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER,0);
        if(preblend||verifyPreblend) {
            GLES30.glGenBuffers(1,ids,0); preblendBuffer=ids[0];
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,preblendBuffer);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,vertexCount*32,null,GLES30.GL_DYNAMIC_COPY);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0);
        }
    }
    synchronized JSONObject summary() throws Exception {
        if(runtimeMode) return runtimeStatus();
        measuring=false;
        double seconds=lastFrame>firstFrame?(lastFrame-firstFrame)/1e9:0;
        return new JSONObject().put("mode",scene?"scene+interlace":"interlace_only")
                .put("gpu",gpu).put("error",error).put("views",views)
                .put("gl_extensions",extensions).put("max_array_texture_layers",maxArrayLayers)
                .put("max_multiview_views",maxMultiviewViews)
                .put("backface_culling",cull).put("culling_verification",cullVerification)
                .put("view_storage",atlas?"single RGBA8 atlas":"RGBA8 texture array")
                .put("atlas_population",atlasCopy?"exact GPU copy of independently rendered array layers":"direct viewport rendering")
                .put("atlas_grid_columns",atlasColumns).put("atlas_grid_rows",atlasRows)
                .put("atlas_verification",atlasVerification)
                .put("view_submission",multiview?"OVR_multiview2 four-view batches":"serial per view")
                .put("multiview_verification",multiviewVerification)
                .put("morph_computation",preblend?"Transform feedback once per expression update, shared by every view":"Repeated per view")
                .put("preblend_updates",preblendUpdates).put("preblend_verification",preblendVerification)
                .put("interlace_explicit_lod_zero",explicitLod)
                .put("combined_verification",combinedVerification)
                .put("depth_store_discarded",discardDepth).put("depth_discard_verification",discardDepthVerification)
                .put("window_depth_bits",windowDepthBits)
                .put("shared_rgb_phase_index",sharedPhase).put("shared_phase_verification",sharedPhaseVerification)
                .put("submitted_scene_draw_calls_per_frame",scene?views*8/(multiview?4:1):0)
                .put("output_width",width).put("output_height",height)
                .put("view_width",viewWidth).put("view_height",viewHeight)
                .put("view_color_buffer_mib",viewWidth*(double)viewHeight*views*4/1048576)
                .put("triangles_per_view",scene?indexCount/3:0).put("draw_calls_per_view",scene?8:0)
                .put("morph_targets",scene?4:0).put("frames",workMs.size())
                .put("sync_mode",pipeline?"bounded two-frame GPU fence pipeline":"per-frame glFinish")
                .put("max_frames_in_flight",pipeline?2:1)
                .put("interlace_index_mode",lookup?"GPU-generated integer lookup":"per-pixel arithmetic")
                .put("lookup_color_buffer_mib",lookup||verifyLookup?width*(double)height*4/1048576:0)
                .put("lookup_verification_byte_mismatches",lookupMismatch)
                .put("fps",seconds>0?(workMs.size()-1)/seconds:0)
                .put("render_target_fps",targetFps)
                .put("frame_work_p50_ms",BenchActivity.percentile(workMs,.5))
                .put("frame_work_p95_ms",BenchActivity.percentile(workMs,.95))
                .put("frame_interval_p95_ms",BenchActivity.percentile(intervalMs,.95))
                .put("frame_work_samples_ms",new JSONArray(workMs)).put("frame_interval_samples_ms",new JSONArray(intervalMs))
                .put("stage_profile_samples",sceneStageMs.size())
                .put("scene_completion_mean_ms",sceneStageMs.stream().mapToDouble(x->x).average().orElse(0))
                .put("scene_completion_p95_ms",BenchActivity.percentile(sceneStageMs,.95))
                .put("interlace_completion_mean_ms",interlaceStageMs.stream().mapToDouble(x->x).average().orElse(0))
                .put("interlace_completion_p95_ms",BenchActivity.percentile(interlaceStageMs,.95))
                .put("stage_timing",pipeline?"Completion stage profiling disabled for asynchronous pipeline"
                        :"One extra glFinish every 30 frames; completion wall time including submission, GPU work and shared-GPU waiting; not GPU-exclusive")
                .put("phase_pitch_rgb_subpixels",pitch).put("phase_tilt_rgb_subpixels_per_y_pixel",tilt)
                .put("calibration",calibration)
                .put("timing",pipeline?"CPU submission and bounded fence wait; not GPU completion; callback FPS includes EGL swap backpressure"
                        :"glFinish completion wall time; includes CPU submission and GPU execution; excludes EGL swap");
    }
    private void fail(Throwable problem) {
        if(gpuProfile!=null)try{gpuProfile.close();}catch(Throwable cleanup){if(cleanup!=problem)problem.addSuppressed(cleanup);}
        if(cachedCameraVpRequested)cameraVpActual="failed";
        if(persistentFbosRequested){multiviewFboActual="failed";try{releasePersistentFbos();}catch(Throwable cleanup){problem.addSuppressed(cleanup);}}
        error=problem.toString(); Log.e("MirrorBench","GL failure",problem);
    }
    private static void checkGl() { int e=GLES30.glGetError(); if(e!=0) throw new IllegalStateException("GL error "+e); }
    private static int program(String vertex,String fragment) {
        return program(vertex,fragment,null);
    }
    private static String multiviewVertex(String shader) {
        return shader.replace("#version 300 es",
                "#version 300 es\n#extension GL_OVR_multiview2 : require\nlayout(num_views=4) in;")
                .replace("uniform mat4 uMvp,uModel;","uniform mat4 uMvps[4],uModel;")
                .replace("uMvp*vec4(p,1)","uMvps[gl_ViewID_OVR]*vec4(p,1)");
    }
    private static int program(String vertex,String fragment,String[] feedback) {
        int p=GLES30.glCreateProgram();
        for(int type:new int[]{GLES30.GL_VERTEX_SHADER,GLES30.GL_FRAGMENT_SHADER}) {
            int shader=GLES30.glCreateShader(type);
            GLES30.glShaderSource(shader,type==GLES30.GL_VERTEX_SHADER?vertex:fragment); GLES30.glCompileShader(shader);
            int[] ok=new int[1]; GLES30.glGetShaderiv(shader,GLES30.GL_COMPILE_STATUS,ok,0);
            if(ok[0]==0) throw new IllegalStateException(GLES30.glGetShaderInfoLog(shader));
            GLES30.glAttachShader(p,shader); GLES30.glDeleteShader(shader);
        }
        if(feedback!=null) GLES30.glTransformFeedbackVaryings(p,feedback,GLES30.GL_INTERLEAVED_ATTRIBS);
        GLES30.glLinkProgram(p); int[] ok=new int[1]; GLES30.glGetProgramiv(p,GLES30.GL_LINK_STATUS,ok,0);
        if(ok[0]==0) throw new IllegalStateException(GLES30.glGetProgramInfoLog(p));
        return p;
    }
    private static final String SCREEN_VERTEX="""
        #version 300 es
        out vec2 vUv;
        void main(){vec2 p=gl_VertexID==0?vec2(-1,-1):(gl_VertexID==1?vec2(3,-1):vec2(-1,3));vUv=p*.5+.5;gl_Position=vec4(p,0,1);}
        """;
    private static final String LEGACY_VIEW_FUNCTION="float viewAt(float c){return floor(fract((gl_FragCoord.x*3.0+gl_FragCoord.y*uTilt+c)/uPitch)*float(uCount));}";
    // One function shared by arithmetic array, atlas and lookup generation. BGR changes physical offsets,
    // while each caller still samples the corresponding R/G/B component. Phase is measured in cycles.
    private static final String PANEL_VIEW_FUNCTION="""
        uniform float uPhase,uHeight; uniform int uBgr,uReverse,uTop;
        float viewAt(float c){
            precise float y=uTop!=0?uHeight-gl_FragCoord.y:gl_FragCoord.y;
            precise float offset=uBgr!=0?2.0-c:c;
            precise float xt=gl_FragCoord.x*3.0;
            precise float yt=y*uTilt;
            precise float sum=xt+yt;
            sum=sum+offset;
            precise float q=sum/uPitch;
            if(uPhase!=0.0)q=q+uPhase;
            precise float fraction=q-floor(q);
            precise float v=min(float(uCount-1),floor(fraction*float(uCount)));
            return uReverse!=0?float(uCount-1)-v:v;
        }
        """;
    private static final String INTERLACE_FRAGMENT="""
        #version 300 es
        precision highp float;
        precision highp sampler2DArray;
        in vec2 vUv;
        uniform sampler2DArray uViews; uniform int uCount; uniform float uPitch,uTilt;
        out vec4 color;
        float viewAt(float c){return floor(fract((gl_FragCoord.x*3.0+gl_FragCoord.y*uTilt+c)/uPitch)*float(uCount));}
        void main(){color=vec4(texture(uViews,vec3(vUv,viewAt(0.0))).r,texture(uViews,vec3(vUv,viewAt(1.0))).g,texture(uViews,vec3(vUv,viewAt(2.0))).b,1);}
        """;
    private static final String LOOKUP_GENERATOR="""
        #version 300 es
        precision highp float; precision highp int;
        uniform int uCount; uniform float uPitch,uTilt; out uvec4 indices;
        float viewAt(float c){return floor(fract((gl_FragCoord.x*3.0+gl_FragCoord.y*uTilt+c)/uPitch)*float(uCount));}
        void main(){indices=uvec4(uint(viewAt(0.0)),uint(viewAt(1.0)),uint(viewAt(2.0)),0u);}
        """;
    private static final String ATLAS_FRAGMENT="""
        #version 300 es
        precision highp float;
        precision highp sampler2D;
        in vec2 vUv;
        uniform sampler2D uViews; uniform int uCount; uniform float uPitch,uTilt;
        uniform vec2 uGrid,uHalfTexel; out vec4 color;
        float viewAt(float c){return floor(fract((gl_FragCoord.x*3.0+gl_FragCoord.y*uTilt+c)/uPitch)*float(uCount));}
        vec2 atlasUv(float v){vec2 tile=vec2(mod(v,uGrid.x),floor(v/uGrid.x));return (tile+clamp(vUv,uHalfTexel,1.0-uHalfTexel))/uGrid;}
        void main(){color=vec4(textureLod(uViews,atlasUv(viewAt(0.0)),0.0).r,textureLod(uViews,atlasUv(viewAt(1.0)),0.0).g,textureLod(uViews,atlasUv(viewAt(2.0)),0.0).b,1);}
        """;
    private static final String LOOKUP_FRAGMENT="""
        #version 300 es
        precision highp float; precision highp int;
        precision highp sampler2DArray; precision highp usampler2D;
        in vec2 vUv; uniform sampler2DArray uViews; uniform usampler2D uLookup; out vec4 color;
        void main(){uvec3 v=texelFetch(uLookup,ivec2(gl_FragCoord.xy),0).rgb;
        color=vec4(texture(uViews,vec3(vUv,float(v.r))).r,texture(uViews,vec3(vUv,float(v.g))).g,texture(uViews,vec3(vUv,float(v.b))).b,1);}
        """;
    private static final String SCENE_VERTEX="""
        #version 300 es
        layout(location=0) in vec3 aPosition; layout(location=1) in vec3 aNormal; layout(location=2) in vec2 aUv;
        layout(location=3) in vec3 aJaw; layout(location=4) in vec3 aBlink; layout(location=5) in vec3 aSmile; layout(location=6) in vec3 aBrow;
        uniform mat4 uMvp,uModel; uniform vec4 uWeights; out vec3 vNormal; out vec2 vUv;
        void main(){vec3 p=aPosition+aJaw*uWeights.x+aBlink*uWeights.y+aSmile*uWeights.z+aBrow*uWeights.w;gl_Position=uMvp*vec4(p,1);vNormal=mat3(uModel)*aNormal;vUv=aUv;}
        """;
    private static final String PREBLEND_VERTEX="""
        #version 300 es
        layout(location=0) in vec3 aPosition; layout(location=1) in vec3 aNormal; layout(location=2) in vec2 aUv;
        layout(location=3) in vec3 aJaw; layout(location=4) in vec3 aBlink; layout(location=5) in vec3 aSmile; layout(location=6) in vec3 aBrow;
        uniform vec4 uWeights; out vec3 tfPosition,tfNormal; out vec2 tfUv;
        out vec3 vNormal; out vec2 vUv;
        void main(){vec3 p=aPosition+aJaw*uWeights.x+aBlink*uWeights.y+aSmile*uWeights.z+aBrow*uWeights.w;
        tfPosition=p;tfNormal=aNormal;tfUv=aUv;vNormal=aNormal;vUv=aUv;gl_Position=vec4(p,1);}
        """;
    private static final String PREBLEND_SCENE_VERTEX="""
        #version 300 es
        layout(location=0) in vec3 aPosition; layout(location=1) in vec3 aNormal; layout(location=2) in vec2 aUv;
        uniform mat4 uMvp,uModel; out vec3 vNormal; out vec2 vUv;
        void main(){vec3 p=aPosition;gl_Position=uMvp*vec4(p,1);vNormal=mat3(uModel)*aNormal;vUv=aUv;}
        """;
    private static final String SCENE_FRAGMENT="""
        #version 300 es
        precision mediump float;
        in vec3 vNormal; in vec2 vUv; uniform vec3 uColor; out vec4 color;
        void main(){vec3 n=normalize(vNormal);float light=.2+.5*max(dot(n,normalize(vec3(1,1,2))),0.0)+.25*max(dot(n,normalize(vec3(-1,.3,1))),0.0);float pattern=.92+.08*sin(vUv.x*60.0)*sin(vUv.y*60.0);color=vec4(uColor*light*pattern,1);}
        """;
}
