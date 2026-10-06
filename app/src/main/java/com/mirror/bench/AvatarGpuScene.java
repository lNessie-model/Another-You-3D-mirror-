package com.mirror.bench;

import android.content.res.AssetManager;
import android.opengl.GLES30;
import android.opengl.Matrix;
import android.opengl.GLUtils;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Real GLB scene. GL-thread owner; one deformation/upload is shared by every independent view. */
final class AvatarGpuScene {
    enum DrawMode { INDIVIDUAL, BATCHED, VERIFY }
    private final AvatarAsset asset;
    private final AvatarRig rig;
    private final AvatarDeformer deformer;
    private final List<GpuPrimitive>[] primitives;
    private final float[][] weights;
    private Program program, multiviewProgram;
    private AvatarBatchGpu batch;
    private final DrawMode drawMode;
    private final AvatarDrawPartition drawPartition;
    private final int[] staticBackgroundNodes;
    private final List<Integer> allocatedBuffers=new ArrayList<>();
    private final float[] world=new float[16], fittedWorld=new float[16], fit=new float[16], inverse=new float[16], normal=new float[9];
    private final float[] baseFit=new float[16],userTransform=new float[16];
    private SceneViewSettings sceneView=SceneViewSettings.DEFAULT;
    private AvatarFraming framing;
    private AvatarPoseWorker poseWorker;
    private final float[] displayedWorlds;
    private boolean asyncHasFrame;
    private long lastAppliedInput;
    private final AvatarPoseProgressWatchdog poseProgress=new AvatarPoseProgressWatchdog();
    private double poseAgeMs,copyMs;
    private final String sha256;
    private final Object metricsLock=new Object();
    private long updates, changedMeshes, uploadBytes;
    private double deformMs,uploadMs;
    private double rigMs,morphMs,meanRigMs,meanMorphMs,meanCopyMs,meanUploadMs,meanPoseAgeMs,maxPoseAgeMs;
    private final int primitiveCount;
    private int atlasTexture;
    private final int[] detailTextures=new int[2];
    private final float[] pbrParams=new float[4];
    private final AvatarOrmUploadPolicy.Decision ormUploadPolicy;
    private volatile boolean ormRg8Uploaded;
    private OrmComparison ormComparison;
    private final boolean pbrFastMathRequested;
    private boolean pbrFastMathSelected;
    private PbrComparison pbrComparison;
    private boolean specializedBatch;
    private boolean disposed;
    // GL-owner submission counters; pixel verification snapshots them on that same owner.
    private long individualDrawCalls,individualViewGroups;
    private int individualLastProgram,individualLastViewCount;
    private AvatarScreenBounds screenBounds;

    static AvatarGpuScene builtin(AssetManager assets,boolean multiview) throws Exception {
        return builtin(assets,multiview,false);
    }
    static AvatarGpuScene builtin(AssetManager assets,boolean multiview,boolean asynchronous) throws Exception {
        return builtin(assets,multiview,asynchronous,DrawMode.INDIVIDUAL);
    }
    static AvatarGpuScene builtin(AssetManager assets,boolean multiview,boolean asynchronous,DrawMode drawMode) throws Exception {
        return builtin(assets,multiview,asynchronous,drawMode,false);
    }
    static AvatarGpuScene builtin(AssetManager assets,boolean multiview,boolean asynchronous,DrawMode drawMode,boolean ormRg8Requested) throws Exception {
        return builtin(assets,multiview,asynchronous,drawMode,ormRg8Requested,false);
    }
    static AvatarGpuScene builtin(AssetManager assets,boolean multiview,boolean asynchronous,DrawMode drawMode,boolean ormRg8Requested,boolean pbrFastMath) throws Exception {
        String directory="avatars/builtin-guide/";
        byte[] manifest,glb;
        try(InputStream input=assets.open(directory+"avatar.json")){manifest=readBounded(input,262_144);}
        JSONObject json=new JSONObject(new String(manifest,StandardCharsets.UTF_8));
        String filename=json.getString("model");
        if(!filename.matches("[A-Za-z0-9_-]+\\.glb"))throw new IllegalArgumentException("Invalid avatar GLB filename");
        try(InputStream input=assets.open(directory+filename)){glb=readBounded(input,32*1024*1024);}
        String hash=sha256(glb);
        if(!hash.equalsIgnoreCase(json.getString("modelSha256")))throw new IllegalArgumentException("Avatar GLB digest differs from manifest");
        AvatarAsset asset=AvatarGlbLoader.load(glb);
        return fromAsset(asset,new String(manifest,StandardCharsets.UTF_8),hash,multiview,asynchronous,drawMode,ormRg8Requested,pbrFastMath);
    }
    /** APK catalog selection. Read and decode only this one model on its GL owner. */
    static AvatarGpuScene bundled(AssetManager assets,String directory,String modelDigest,String manifestDigest,
                                  boolean multiview,boolean asynchronous,DrawMode mode)throws Exception {
        return bundled(assets,directory,modelDigest,manifestDigest,multiview,asynchronous,mode,false);
    }
    static AvatarGpuScene bundled(AssetManager assets,String directory,String modelDigest,String manifestDigest,
                                  boolean multiview,boolean asynchronous,DrawMode mode,boolean ormRg8Requested)throws Exception {
        return bundled(assets,directory,modelDigest,manifestDigest,multiview,asynchronous,mode,ormRg8Requested,false);
    }
    static AvatarGpuScene bundled(AssetManager assets,String directory,String modelDigest,String manifestDigest,
                                  boolean multiview,boolean asynchronous,DrawMode mode,boolean ormRg8Requested,boolean pbrFastMath)throws Exception {
        if(!directory.matches("avatars/(?:catalog/[a-z0-9][a-z0-9-]{0,63}|builtin-guide)"))
            throw new IllegalArgumentException("Invalid bundled avatar directory");
        if(!modelDigest.matches("[0-9a-f]{64}")||!manifestDigest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Bundled avatar catalog digests required");
        byte[] manifest,glb;
        try(InputStream input=assets.open(directory+"/avatar.json")){manifest=readBounded(input,262_144);}
        if(!sha256(manifest).equals(manifestDigest))throw new IllegalArgumentException("Bundled avatar manifest digest mismatch");
        JSONObject json=new JSONObject(new String(manifest,StandardCharsets.UTF_8));
        String filename=json.getString("model");
        if(!"character.glb".equals(filename))throw new IllegalArgumentException("Bundled avatar model filename");
        try(InputStream input=assets.open(directory+"/"+filename)){glb=readBounded(input,AvatarGlbLoader.MAX_FILE_BYTES);}
        String hash=sha256(glb);
        if(!hash.equals(modelDigest)||!hash.equalsIgnoreCase(json.getString("modelSha256")))
            throw new IllegalArgumentException("Bundled avatar GLB digest mismatch");
        return fromAsset(AvatarGlbLoader.load(glb),new String(manifest,StandardCharsets.UTF_8),hash,multiview,asynchronous,mode,ormRg8Requested,pbrFastMath);
    }
    /** Debug-only caller uses this fixed app-private diagnostic directory; never selects a stored avatar. */
    static AvatarGpuScene privateHeadCheck(java.io.File files,boolean multiview,DrawMode mode)throws Exception {
        return privateHeadCheck(files,multiview,false,mode);
    }
    static AvatarGpuScene privateHeadCheck(java.io.File files,boolean multiview,boolean asynchronous,DrawMode mode)throws Exception {
        return privateHeadCheck(files,multiview,asynchronous,mode,false);
    }
    static AvatarGpuScene privateHeadCheck(java.io.File files,boolean multiview,boolean asynchronous,DrawMode mode,boolean ormRg8Requested)throws Exception {
        return privateHeadCheck(files,multiview,asynchronous,mode,ormRg8Requested,false);
    }
    static AvatarGpuScene privateHeadCheck(java.io.File files,boolean multiview,boolean asynchronous,DrawMode mode,boolean ormRg8Requested,boolean pbrFastMath)throws Exception {
        java.io.File directory=new java.io.File(files,"tripo-head-check");byte[] manifest,glb;
        try(InputStream in=new java.io.FileInputStream(new java.io.File(directory,"avatar.json"))){manifest=readBounded(in,262_144);}
        JSONObject json=new JSONObject(new String(manifest,StandardCharsets.UTF_8));
        if(!"character.glb".equals(json.getString("model")))throw new IllegalArgumentException("Diagnostic head model filename");
        try(InputStream in=new java.io.FileInputStream(new java.io.File(directory,"character.glb"))){glb=readBounded(in,AvatarGlbLoader.MAX_FILE_BYTES);}
        String hash=sha256(glb);if(!hash.equalsIgnoreCase(json.getString("modelSha256")))throw new IllegalArgumentException("Diagnostic head digest mismatch");
        return fromAsset(AvatarGlbLoader.load(glb),new String(manifest,StandardCharsets.UTF_8),hash,multiview,asynchronous,mode,ormRg8Requested,pbrFastMath);
    }
    /** Inputs are a validated CPU asset/manifest and its verified model digest; no package-store dependency. */
    static AvatarGpuScene fromAsset(AvatarAsset asset,String manifestJson,String modelSha256,boolean multiview,boolean asynchronous) throws Exception {
        return fromAsset(asset,manifestJson,modelSha256,multiview,asynchronous,DrawMode.INDIVIDUAL);
    }
    static AvatarGpuScene fromAsset(AvatarAsset asset,String manifestJson,String modelSha256,boolean multiview,boolean asynchronous,DrawMode drawMode) throws Exception {
        return fromAsset(asset,manifestJson,modelSha256,multiview,asynchronous,drawMode,false);
    }
    static AvatarGpuScene fromAsset(AvatarAsset asset,String manifestJson,String modelSha256,boolean multiview,boolean asynchronous,DrawMode drawMode,boolean ormRg8Requested) throws Exception {
        return fromAsset(asset,manifestJson,modelSha256,multiview,asynchronous,drawMode,ormRg8Requested,false);
    }
    static AvatarGpuScene fromAsset(AvatarAsset asset,String manifestJson,String modelSha256,boolean multiview,boolean asynchronous,DrawMode drawMode,boolean ormRg8Requested,boolean pbrFastMath) throws Exception {
        if(drawMode==null)throw new IllegalArgumentException("Avatar draw mode required");
        if(drawMode==DrawMode.VERIFY&&asynchronous)throw new IllegalArgumentException("Avatar pixel verification requires synchronous pose ownership");
        AvatarRig rig=new AvatarRig(asset,manifestJson);
        AvatarGpuScene scene=new AvatarGpuScene(asset,rig,modelSha256,multiview,drawMode,ormRg8Requested,pbrFastMath);
        try {
            if(asynchronous)scene.poseWorker=new AvatarPoseWorker(asset,manifestJson);
            return scene;
        } catch(Exception|Error failure){try{scene.dispose();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    @SuppressWarnings("unchecked")
    private AvatarGpuScene(AvatarAsset asset,AvatarRig rig,String hash,boolean multiview,DrawMode drawMode,boolean ormRg8Requested,boolean pbrFastMath) {
        this.asset=asset;this.rig=rig;sha256=hash;this.drawMode=drawMode;
        pbrFastMathRequested=pbrFastMath;pbrFastMathSelected=pbrFastMath;
        ormUploadPolicy=AvatarOrmUploadPolicy.decide(asset,hash,ormRg8Requested);
        drawPartition=new AvatarDrawPartition(asset,rig);
        staticBackgroundNodes=drawPartition.staticNodes();
        displayedWorlds=new float[asset.nodes().size()*16];
        deformer=new AvatarDeformer(asset,AvatarDeformer.NormalPolicy.fromManifest(rig.normalPolicy()));
        weights=new float[asset.meshes().size()][];primitives=new List[weights.length];
        try {
        boolean atlas=asset.albedoAtlas()!=null;
        if(atlas)uploadAtlas(asset.albedoAtlas());
        boolean pbr=asset.normalMap()!=null;
        if(pbr){uploadMap(asset.normalMap(),1);uploadMap(asset.ormMap(),2);}
        if(drawMode!=DrawMode.BATCHED){program=new Program(false,atlas,pbr,pbrFastMath);multiviewProgram=multiview?new Program(true,atlas,pbr,pbrFastMath):null;}
        int count=0;
        for(int m=0;m<weights.length;m++) {
            weights[m]=new float[asset.meshes().get(m).targetCount()];
            primitives[m]=new ArrayList<>();int p=0;
            for(AvatarAsset.Primitive primitive:asset.meshes().get(m).primitives()) {
                primitives[m].add(new GpuPrimitive(primitive,deformer.primitives(m).get(p++)));count++;
            }
        }
        primitiveCount=count;
        if(drawMode!=DrawMode.INDIVIDUAL)batch=new AvatarBatchGpu(asset,rig,multiview,pbrFastMath);
        framing=AvatarGeometryBounds.fromAsset(asset,rig);
        prepare(new float[52],new float[3]);
        checkGl("avatar initialization");
        } catch(RuntimeException|Error failure) {try{dispose();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    /** Call only while the owning EGL context is current. On context loss discard this object instead. */
    void dispose() {
        if(disposed)return;disposed=true;
        ResourceCleanup cleanup=new ResourceCleanup(null);
        cleanup.close("avatar CPU owner",this::stopCpu);
        cleanup.close("avatar ORM comparison",ormComparison);
        cleanup.close("avatar PBR comparison",pbrComparison);
        AvatarBatchGpu oldBatch=batch;batch=null;specializedBatch=false;
        if(oldBatch!=null)cleanup.close("avatar batch",oldBatch::dispose);
        for(int buffer:allocatedBuffers)cleanup.close("avatar primitive buffer",()->GLES30.glDeleteBuffers(1,new int[]{buffer},0));
        allocatedBuffers.clear();
        int atlas=atlasTexture;atlasTexture=0;
        if(atlas!=0)cleanup.close("avatar albedo",()->GLES30.glDeleteTextures(1,new int[]{atlas},0));
        int[] details=detailTextures.clone();detailTextures[0]=detailTextures[1]=0;
        cleanup.close("avatar detail textures",()->GLES30.glDeleteTextures(2,details,0));
        Program a=program,b=multiviewProgram;program=null;multiviewProgram=null;
        if(a!=null)cleanup.close("avatar individual program",()->GLES30.glDeleteProgram(a.id));
        if(b!=null)cleanup.close("avatar OVR program",()->GLES30.glDeleteProgram(b.id));
        Throwable failure=cleanup.failure();if(failure instanceof Error e)throw e;
        if(failure instanceof RuntimeException r)throw r;if(failure!=null)throw new IllegalStateException(failure);
    }
    /** Safe from Activity teardown; CPU ownership is independent of EGL and never joined on UI. */
    void stopCpu(){if(poseWorker!=null)poseWorker.close();}
    /** GL thread consumes an explicit Activity resume, using the worker's monotonic clock. */
    void resumeCpuPose(){poseProgress.resume(System.nanoTime());}
    void prepare(float[] blendshapes,float[] headAngles) {
        if(poseWorker!=null){prepareAsync(blendshapes,headAngles);return;}
        long start=System.nanoTime();rig.update(blendshapes,headAngles);
        long rigEnd=System.nanoTime(),changed=0,uploaded=0;
        for(int m=0;m<weights.length;m++) {
            rig.copyMeshWeights(m,weights[m]);
            if(deformer.updateMesh(m,weights[m]))changed++;
        }
        for(int n=0;n<asset.nodes().size();n++)if(rig.activeNode(n)) {
            rig.copyWorldMatrix(n,world);System.arraycopy(world,0,displayedWorlds,n*16,16);
        }
        long uploadStart=System.nanoTime();
        for(int m=0;m<primitives.length;m++)for(int p=0;p<primitives[m].size();p++) {
            GpuPrimitive primitive=primitives[m].get(p);
            if(primitive.lastRevision==primitive.output.revision())continue;
            if(drawMode!=DrawMode.BATCHED) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,primitive.dynamicBuffer);
            primitive.upload.position(0);
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER,0,primitive.upload.capacity()*4,primitive.upload);
            uploaded+=(long)primitive.upload.capacity()*4;
            }
            if(batch!=null)uploaded+=batch.upload(m,p,primitive.upload);
            if(screenBounds!=null)screenBounds.updatePrimitive(m,p,primitive.upload);
            primitive.lastRevision=primitive.output.revision();
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0);
        recordPose((rigEnd-start)/1e6,(uploadStart-rigEnd)/1e6,0,
                (System.nanoTime()-uploadStart)/1e6,0,0,changed,uploaded);
    }
    private void prepareAsync(float[] blendshapes,float[] headAngles) {
        // Scheduling delays alone never reset the deadline; only an explicit resume may do so.
        if(poseProgress.check(System.nanoTime()))throw new IllegalStateException("Avatar deformation made no fresh progress for 2 seconds");
        Throwable failure=poseWorker.failure();
        if(failure!=null)throw new IllegalStateException("Avatar deformation failed",failure);
        if(poseWorker.submit(blendshapes,headAngles)<0)throw new IllegalStateException("Avatar pose worker stopped");
        AvatarPoseWorker.Frame frame=poseWorker.acquireLatest();
        if(frame!=null)try {
            long appliedAt=System.nanoTime();
            if(appliedAt-frame.submittedNanos()>=0&&appliedAt-frame.submittedNanos()<=AvatarPoseProgressWatchdog.FRESH_RESULT_NS) {
                long uploadStart=System.nanoTime(),changed=0,uploaded=0;
                frame.copyWorldMatrices(displayedWorlds);
                for(int m=0;m<primitives.length;m++) {
                  boolean meshChanged=false;
                  for(int p=0;p<primitives[m].size();p++) {
                    GpuPrimitive primitive=primitives[m].get(p);long revision=frame.primitiveRevision(m,p);
                    if(asyncHasFrame&&primitive.lastRevision==revision)continue;
                    FloatBuffer buffer=frame.interleaved(m,p);
                    if(drawMode!=DrawMode.BATCHED) {
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,primitive.dynamicBuffer);
                    GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER,0,buffer.capacity()*4,buffer);
                    uploaded+=(long)buffer.capacity()*4;
                    }
                    if(batch!=null)uploaded+=batch.upload(m,p,buffer);
                    if(screenBounds!=null)screenBounds.updatePrimitive(m,p,buffer);
                    primitive.lastRevision=revision;meshChanged=true;
                  }
                  if(meshChanged)changed++;
                }
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0);
                long uploadEnd=System.nanoTime();
                if(!poseProgress.applied(frame.submittedNanos(),uploadEnd))
                    throw new IllegalStateException("Avatar pose became stale while uploading");
                asyncHasFrame=true;
                recordPose(frame.rigMs(),frame.deformMs(),frame.copyMs(),(uploadEnd-uploadStart)/1e6,
                        (uploadEnd-frame.submittedNanos())/1e6,frame.inputId(),changed,uploaded);
            }
        } finally {poseWorker.release(frame);}
    }
    /** Publish coherent, constant-space timing counters without holding a lock during GL calls. */
    private void recordPose(double rig,double morph,double copy,double upload,double age,long input,long changed,long uploaded) {
        synchronized(metricsLock) {
            updates++;changedMeshes+=changed;uploadBytes+=uploaded;lastAppliedInput=input;
            rigMs=rig;morphMs=morph;deformMs=rig+morph;copyMs=copy;uploadMs=upload;poseAgeMs=age;
            meanRigMs+=(rig-meanRigMs)/updates;meanMorphMs+=(morph-meanMorphMs)/updates;
            meanCopyMs+=(copy-meanCopyMs)/updates;meanUploadMs+=(upload-meanUploadMs)/updates;
            meanPoseAgeMs+=(age-meanPoseAgeMs)/updates;maxPoseAgeMs=Math.max(maxPoseAgeMs,age);
        }
    }
    /** viewProjections contain one or four independent off-axis camera matrices, without model transform. */
    void draw(float[] viewProjections,int viewCount,float aspect) {
        drawPass(viewProjections,viewCount,aspect,AvatarDrawPartition.Pass.ALL);
    }
    /** Explicit diagnostic only; shares the reference's current uploaded VBOs, fit and world matrices. */
    void drawMaterialCoverage(float[] viewProjections,int viewCount,float aspect,int estimateTexture) {
        if(drawMode!=DrawMode.BATCHED||batch==null||ormComparison!=null||pbrComparison!=null||specializedBatch)
            throw new IllegalStateException("Ordinary batched diagnostic scene required");
        copyFitMatrix(aspect,fit);
        batch.drawMaterialCoverage(viewProjections,viewCount,fit,displayedWorlds,estimateTexture);
    }
    org.json.JSONArray materialCoverageEntries()throws Exception {
        if(drawMode!=DrawMode.BATCHED||batch==null)throw new IllegalStateException("Batched diagnostic scene required");
        return batch.materialCoverageEntries();
    }
    boolean hasCacheableStaticBackground(){return drawMode==DrawMode.INDIVIDUAL&&drawPartition.canCacheTrailingBackground();}
    String modelSha256(){return sha256;}
    int staticBackgroundStateFloats(){return 16*(1+staticBackgroundNodes.length);}
    /** Exact current fit and static world matrices, excluding the independently animated head. */
    void copyStaticBackgroundState(float aspect,float[] destination){
        if(!hasCacheableStaticBackground()||destination==null||destination.length!=staticBackgroundStateFloats())
            throw new IllegalArgumentException("Eligible background and complete key destination required");
        copyFitMatrix(aspect,fit);System.arraycopy(fit,0,destination,0,16);
        for(int i=0;i<staticBackgroundNodes.length;i++)System.arraycopy(displayedWorlds,staticBackgroundNodes[i]*16,destination,(i+1)*16,16);
    }
    /** Debug candidate: shared original textures/VBOs, only per-material programs and draw ranges change. */
    void beginSpecializedBatch(){
        if(disposed||specializedBatch||(drawMode!=DrawMode.BATCHED&&drawMode!=DrawMode.VERIFY)||batch==null||ormComparison!=null||pbrComparison!=null
                ||ormRg8Uploaded||pbrFastMathRequested||!sha256.equals("9381f452c53098314f97e1a1799ec55d2be37878e66bf205c7a26958afcef531"))
            throw new IllegalStateException("Specialized candidate requires original Geralt PBR batched scene without other material experiments");
        batch.beginSpecializedComparison();specializedBatch=true;
    }
    void selectSpecializedBatch(boolean enabled){
        if(!specializedBatch)throw new IllegalStateException("Specialized comparison not initialized");
        batch.selectSpecialized(enabled);
    }
    void endSpecializedBatch(){
        if(!specializedBatch)return;batch.endSpecializedComparison();specializedBatch=false;
    }
    /** Expected original program for the independent-buffer VERIFY diagnostic only; no GL call. */
    int individualProgramIdForVerification(int viewCount){
        if(disposed||drawMode!=DrawMode.VERIFY||!specializedBatch||poseWorker!=null||pbrFastMathSelected)
            throw new IllegalStateException("Live synchronous ordinary-reference verification required");
        Program selected=selectedPbrProgram(viewCount);
        if(selected==null)throw new IllegalStateException("Individual verification program unavailable");
        return selected.id;
    }

    /** Same shaders, matrices and geometry as the ordinary selected-node drawing path. */
    void drawStaticBackground(float[] viewProjections,int viewCount,float aspect) {
        drawPass(viewProjections,viewCount,aspect,AvatarDrawPartition.Pass.STATIC);
    }
    void drawDynamic(float[] viewProjections,int viewCount,float aspect) {
        drawPass(viewProjections,viewCount,aspect,AvatarDrawPartition.Pass.DYNAMIC);
    }
    private void drawPass(float[] viewProjections,int viewCount,float aspect,AvatarDrawPartition.Pass pass) {
        if(pass!=AvatarDrawPartition.Pass.ALL&&!hasCacheableStaticBackground())
            throw new IllegalStateException("Static/dynamic split requires eligible trailing unlit props and individual draw mode");
        if(drawMode==DrawMode.BATCHED){drawBatched(viewProjections,viewCount,aspect);return;}
        if((viewCount!=1&&viewCount!=4)||viewProjections.length<viewCount*16)throw new IllegalArgumentException("Invalid avatar view group");
        Program active=selectedPbrProgram(viewCount);
        if(active==null)throw new IllegalStateException("Avatar multiview program was not initialized");
        // Fixed envelope includes morphs and rigid joints; preserve framing throughout every head pose.
        // Uniform root coordinate scale is absorbed by this normalized automatic fit, not extra zoom.
        copyFitMatrix(aspect,fit);
        GLES30.glUseProgram(active.id);
        if(atlasTexture!=0){bindAtlas();GLES30.glUniform1i(active.sampler,0);}
        if(asset.normalMap()!=null){GLES30.glUniform1i(active.normalSampler,1);GLES30.glUniform1i(active.ormSampler,2);}
        GLES30.glUniformMatrix4fv(active.viewProjection,viewCount,false,viewProjections,0);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);GLES30.glEnable(GLES30.GL_CULL_FACE);
        GLES30.glFrontFace(GLES30.GL_CCW);GLES30.glCullFace(GLES30.GL_BACK);
        GLES30.glDisable(GLES30.GL_BLEND);
        for(int n=0;n<asset.nodes().size();n++)if(drawPartition.matchesNode(n,pass)) {
            int mesh=asset.nodes().get(n).meshIndex();if(mesh<0)continue;
            Matrix.multiplyMM(fittedWorld,0,fit,0,displayedWorlds,n*16);
            if(!Matrix.invertM(inverse,0,fittedWorld,0))throw new IllegalStateException("Singular avatar node transform");
            for(int col=0;col<3;col++)for(int row=0;row<3;row++)normal[col*3+row]=inverse[row*4+col];
            GLES30.glUniformMatrix4fv(active.world,1,false,fittedWorld,0);
            GLES30.glUniformMatrix3fv(active.normal,1,false,normal,0);
            for(GpuPrimitive primitive:primitives[mesh]) {
                var material=asset.materials().get(primitive.source.materialIndex());
                GLES30.glUniform4fv(active.color,1,primitive.materialColor,0);
                GLES30.glUniform1f(active.unlit,material.unlit()?1:0);GLES30.glUniform1f(active.roughness,material.roughness());
                if(asset.normalMap()!=null){pbrParams[0]=material.normalScale();pbrParams[1]=material.occlusionStrength();pbrParams[2]=material.metallic();pbrParams[3]=material.pbrMaps()?1:0;GLES30.glUniform4fv(active.pbr,1,pbrParams,0);}
                if(atlasTexture!=0){GLES30.glUniform1f(active.useTexture,material.textured()?1:0);
                    if(primitive.uvBuffer!=0){GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,primitive.uvBuffer);GLES30.glEnableVertexAttribArray(4);GLES30.glVertexAttribPointer(4,2,GLES30.GL_FLOAT,false,8,0);}
                    else {GLES30.glDisableVertexAttribArray(4);GLES30.glVertexAttrib2f(4,0,0);}}
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,primitive.dynamicBuffer);
                GLES30.glEnableVertexAttribArray(0);GLES30.glVertexAttribPointer(0,3,GLES30.GL_FLOAT,false,24,0);
                GLES30.glEnableVertexAttribArray(1);GLES30.glVertexAttribPointer(1,3,GLES30.GL_FLOAT,false,24,12);
                if(primitive.colorBuffer!=0) {
                    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,primitive.colorBuffer);
                    GLES30.glEnableVertexAttribArray(2);GLES30.glVertexAttribPointer(2,4,GLES30.GL_FLOAT,false,16,0);
                } else {GLES30.glDisableVertexAttribArray(2);GLES30.glVertexAttrib4f(2,1,1,1,1);}
                GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER,primitive.indexBuffer);
                GLES30.glDrawElements(GLES30.GL_TRIANGLES,primitive.indexCount,GLES30.GL_UNSIGNED_INT,0);
                individualDrawCalls++;individualLastProgram=active.id;individualLastViewCount=viewCount;
            }
        }
        for(int i=0;i<3;i++)GLES30.glDisableVertexAttribArray(i);
        if(atlasTexture!=0){GLES30.glDisableVertexAttribArray(4);unbindMaps();}
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0);GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER,0);
        individualViewGroups++;
    }
    /** VERIFY keeps independent legacy buffers; both draws share one prepared CPU pose. */
    void drawBatched(float[] viewProjections,int viewCount,float aspect) {
        if(batch==null)throw new IllegalStateException("Avatar batch backend not initialized");
        if(atlasTexture!=0)bindAtlas();
        copyFitMatrix(aspect,fit);
        batch.draw(viewProjections,viewCount,aspect,framing,displayedWorlds,fit);
        if(atlasTexture!=0)unbindMaps();
    }
    /** Called only by the GL owner using one immutable configuration per frame. */
    void setSceneView(SceneViewSettings value){if(value==null)throw new IllegalArgumentException("Scene view required");sceneView=value;}
    private void copyFitMatrix(float aspect,float[] destination){
        if(sceneView.isIdentityTransform()){framing.copyFitMatrix(aspect,destination);return;}
        framing.copyFitMatrix(aspect,baseFit);sceneView.copyUserTransform(aspect,userTransform);
        Matrix.multiplyMM(destination,0,userTransform,0,baseFit,0);
    }
    /** GL owner, immediately after scene creation, before any asynchronous lease is consumed. */
    void enableScreenBounds(){
        if(screenBounds!=null)return;
        if(asyncHasFrame||updates!=1)throw new IllegalStateException("Bounds tracking requires initial neutral VBOs");
        screenBounds=new AvatarScreenBounds(asset,rig);
        for(int m=0;m<primitives.length;m++)for(int p=0;p<primitives[m].size();p++)
            screenBounds.updatePrimitive(m,p,primitives[m].get(p).upload);
    }
    /** Uses the exact accepted/displayed world matrices and the same fit as the draws. */
    boolean copyScreenUvBounds(float[] vp,int count,float aspect,int viewW,int viewH,int outputW,int outputH,float[] out){
        if(screenBounds==null){out[0]=out[1]=0;out[2]=out[3]=1;return false;}
        copyFitMatrix(aspect,fit);
        return screenBounds.copyUvBounds(displayedWorlds,fit,vp,count,viewW,viewH,outputW,outputH,out);
    }
    JSONObject status() throws Exception {
        JSONObject value;
        synchronized(metricsLock) {
        value=new JSONObject().put("id",rig.id()).put("display_name",rig.displayName()).put("model_sha256",sha256)
                .put("vertices",asset.vertexCount()).put("triangles",asset.triangleCount()).put("primitives",primitiveCount)
                .put("decoded_asset_bytes",asset.decodedBytes()).put("morph_updates",updates).put("changed_meshes",changedMeshes)
                .put("last_deform_ms",deformMs).put("last_upload_ms",uploadMs).put("uploaded_bytes",uploadBytes)
                .put("pose_backend",poseWorker==null?"synchronous_gl_thread":"bounded_cpu_worker")
                .put("pose_copy_ms",copyMs).put("applied_pose_age_ms",poseAgeMs).put("applied_pose_input_id",lastAppliedInput)
                .put("last_rig_ms",rigMs).put("last_morph_ms",morphMs)
                .put("rig_mean_ms",meanRigMs).put("morph_mean_ms",meanMorphMs).put("pose_copy_mean_ms",meanCopyMs)
                .put("upload_mean_ms",meanUploadMs).put("applied_pose_age_mean_ms",meanPoseAgeMs).put("applied_pose_age_max_ms",maxPoseAgeMs)
                .put("timing_scope","online means of applied poses since GL scene creation, including initial neutral pose; no per-frame samples retained")
                .put("complete_source_mapping",rig.completeSourceCoverage()).put("artwork_validated",false)
                .put("normal_policy",rig.normalPolicy()).put("deformation_scope",drawMode==DrawMode.VERIFY?
                        "one CPU pose; separate individual and packed batch buffers receive the same deformation output":"once per animation snapshot; all independent views share the same VBO")
                .put("draw_backend",drawMode.name().toLowerCase(java.util.Locale.ROOT))
                .put("individual_draw_calls",individualDrawCalls).put("individual_view_groups",individualViewGroups)
                .put("individual_last_program_id",individualLastProgram).put("individual_last_view_count",individualLastViewCount)
                .put("draw_counter_scope","GL calls returned on the owning thread since scene creation; not GPU completion; groups count completed submission/cleanup only")
                .put("pbr_fast_math_requested",pbrFastMathRequested)
                .put("pbr_shader_variant",AvatarPbrShaderVariant.name(asset.normalMap()!=null,pbrFastMathSelected));
        if(asset.normalMap()!=null)value.put("pbr_fragment_source_sha256",AvatarPbrShaderVariant.sha256(AvatarPbrShaderVariant.fragment(pbrFastMathSelected)));
        }
        AvatarBatchGpu currentBatch=batch;
        if(asset.albedoAtlas()!=null)value.put("albedo_atlas",new JSONObject().put("width",asset.albedoAtlas().width()).put("height",asset.albedoAtlas().height()).put("encoded_bytes",asset.albedoAtlas().encodedBytes()));
        if(asset.normalMap()!=null)value.put("pbr_materials",new JSONObject().put("normal_width",asset.normalMap().width()).put("orm_width",asset.ormMap().width()).put("normal_scale",asset.materials().get(0).normalScale()).put("lighting","GGX key light, diffuse fill, hemisphere ambient; no image-based lighting or skin subsurface scattering").put("tangent_frame","fragment derivatives of current deformed geometry; not authored MikkTSpace tangents").put("sampling","GPU-generated mip chain, trilinear minification; original used level-zero channels retained").put("texture_gpu_bytes",mapGpuBytes(asset.albedoAtlas().width(),asset.albedoAtlas().height(),true)+mapGpuBytes(asset.normalMap().width(),asset.normalMap().height(),true)+AvatarOrmUploadPolicy.logicalBytes(asset.ormMap().width(),asset.ormMap().height(),ormRg8Uploaded?2:4,true)).put("texture_gpu_bytes_scope","logical sized-format mip texels; not measured GPU residency"));
        if(ormUploadPolicy!=null)value.put("orm_upload",ormUploadStatus());
        if(currentBatch!=null)value.put("batch",currentBatch.status());
        if(poseWorker!=null) {
            var s=poseWorker.status();value.put("pose_worker",new JSONObject().put("submitted_inputs",s.submittedInputs)
                    .put("processed_inputs",s.processedInputs).put("published_frames",s.publishedFrames)
                    .put("dropped_pending_inputs",s.droppedInputs).put("superseded_ready_frames",s.droppedReadyFrames)
                    .put("pending_count",s.pendingCount).put("ready_count",s.readyCount).put("writing_count",s.writingCount)
                    .put("leased_count",s.leasedCount).put("max_pending_count",s.maxPendingCount).put("max_ready_count",s.maxReadyCount)
                    .put("output_slots",AvatarPoseWorker.OUTPUT_SLOTS).put("closed",s.closed).put("terminated",s.terminated)
                    .put("run_exit_marked",s.runExitMarked).put("thread_state",s.threadState.name()).put("cpu_stop",cpuStopJson(s.stop)));
        }
        return value;
    }
    private JSONObject ormUploadStatus()throws Exception {
        boolean uploaded=ormRg8Uploaded;boolean present=asset.ormMap()!=null;
        long actual=uploaded?ormUploadPolicy.rgBytes:ormUploadPolicy.rgbaBytes;
        return new JSONObject().put("requested",ormUploadPolicy.requested).put("eligible",ormUploadPolicy.eligible)
                .put("actual",!present?"no_orm":uploaded?"rg8":"rgba8")
                .put("fallback_reason",ormUploadPolicy.requested&&!ormUploadPolicy.eligible?ormUploadPolicy.reason:"")
                .put("policy",ormUploadPolicy.reason).put("rgba8_logical_bytes",ormUploadPolicy.rgbaBytes)
                .put("actual_logical_bytes",actual).put("saved_logical_bytes",ormUploadPolicy.rgbaBytes-actual)
                .put("payload_scope","sized-format texels including all mip levels; not driver allocation or residency")
                .put("quality_scope","R/G level-zero bytes retained; GPU-generated mips/pixels and performance require device verification");
    }
    private static JSONObject cpuStopJson(AvatarPoseWorker.StopReceipt receipt)throws Exception {
        return new JSONObject().put("worker_id",receipt.workerId()).put("clock","System.nanoTime")
                .put("requested_monotonic_ns",receipt.requestedNs()).put("observed_monotonic_ns",receipt.observedNs())
                .put("close_requested",receipt.closeRequested()).put("run_exit_marked",receipt.runExitMarked())
                .put("thread_state",receipt.threadState().name()).put("thread_terminated",receipt.threadTerminated())
                .put("pending_count",receipt.pendingCount()).put("ready_count",receipt.readyCount())
                .put("writing_count",receipt.writingCount()).put("leased_count",receipt.leasedCount())
                .put("failed",receipt.failed()).put("failure_type",receipt.failureType())
                .put("ownership_stopped",receipt.ownershipStopped()).put("close_succeeded",receipt.closeSucceeded())
                .put("scope","CPU pose thread and frame leases only")
                .put("hardware_qualified",false);
    }
    private final class GpuPrimitive {
        final AvatarAsset.Primitive source;final AvatarDeformer.PrimitiveOutput output;
        final FloatBuffer upload;final float[] materialColor=new float[4];
        final int dynamicBuffer,colorBuffer,indexBuffer,indexCount,uvBuffer;long lastRevision=-1;
        GpuPrimitive(AvatarAsset.Primitive source,AvatarDeformer.PrimitiveOutput output) {
            this.source=source;this.output=output;upload=output.interleaved();
            asset.materials().get(source.materialIndex()).baseColor().get(materialColor);
            if(drawMode==DrawMode.BATCHED){dynamicBuffer=0;colorBuffer=0;indexBuffer=0;uvBuffer=0;indexCount=source.indices().remaining();return;}
            boolean hasUv=asset.albedoAtlas()!=null&&source.texCoords()!=null;
            int[] names=new int[2+(source.colors()==null?0:1)+(hasUv?1:0)];GLES30.glGenBuffers(names.length,names,0);
            for(int name:names)allocatedBuffers.add(name);
            dynamicBuffer=names[0];indexBuffer=names[1];
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,dynamicBuffer);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,upload.capacity()*4,null,GLES30.GL_DYNAMIC_DRAW);
            IntBuffer indices=directInts(source.indices());indexCount=indices.remaining();
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER,indexBuffer);
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER,indexCount*4,indices,GLES30.GL_STATIC_DRAW);
            if(source.colors()!=null) {
                colorBuffer=names[2];FloatBuffer colors=directFloats(source.colors());
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,colorBuffer);
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,colors.remaining()*4,colors,GLES30.GL_STATIC_DRAW);
            } else colorBuffer=0;
            if(hasUv){uvBuffer=names[names.length-1];FloatBuffer uv=directFloats(source.texCoords());
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,uvBuffer);GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,uv.remaining()*4,uv,GLES30.GL_STATIC_DRAW);}
            else uvBuffer=0;
        }
    }
    private static final class Program {
        final int id,viewProjection,world,normal,color,unlit,roughness,sampler,useTexture,normalSampler,ormSampler,pbr;
        Program(boolean multiview,boolean atlas,boolean hasPbr,boolean fastMath) {
            String vertex=atlas?atlasVertex(VERTEX):VERTEX;
            if(multiview)vertex=vertex.replace("#version 300 es","#version 300 es\n#extension GL_OVR_multiview2 : require\nlayout(num_views=4) in;")
                    .replace("uniform mat4 uViewProjection;","uniform mat4 uViewProjection[4];")
                    .replace("uViewProjection*", "uViewProjection[gl_ViewID_OVR]*");
            int vs=0,fs=0,created=0;
            try {
                vs=shader(GLES30.GL_VERTEX_SHADER,vertex);fs=shader(GLES30.GL_FRAGMENT_SHADER,hasPbr?AvatarPbrShaderVariant.fragment(fastMath):atlas?atlasFragment(FRAGMENT):FRAGMENT);
                created=GLES30.glCreateProgram();GLES30.glAttachShader(created,vs);GLES30.glAttachShader(created,fs);GLES30.glLinkProgram(created);
                int[] ok=new int[1];GLES30.glGetProgramiv(created,GLES30.GL_LINK_STATUS,ok,0);
                if(ok[0]==0)throw new IllegalStateException("Avatar program: "+GLES30.glGetProgramInfoLog(created));
                id=created;
            } catch(RuntimeException|Error failure){if(created!=0)GLES30.glDeleteProgram(created);throw failure;}
            finally {if(vs!=0)GLES30.glDeleteShader(vs);if(fs!=0)GLES30.glDeleteShader(fs);}
            viewProjection=GLES30.glGetUniformLocation(id,"uViewProjection");world=GLES30.glGetUniformLocation(id,"uWorld");
            normal=GLES30.glGetUniformLocation(id,"uNormal");color=GLES30.glGetUniformLocation(id,"uColor");
            unlit=GLES30.glGetUniformLocation(id,"uUnlit");roughness=GLES30.glGetUniformLocation(id,"uRoughness");
            sampler=atlas?GLES30.glGetUniformLocation(id,"uAtlas"):-1;useTexture=atlas?GLES30.glGetUniformLocation(id,"uUseTexture"):-1;
            normalSampler=hasPbr?GLES30.glGetUniformLocation(id,"uNormalMap"):-1;ormSampler=hasPbr?GLES30.glGetUniformLocation(id,"uOrmMap"):-1;pbr=hasPbr?GLES30.glGetUniformLocation(id,"uPbrParams"):-1;
            if(atlas&&(sampler<0||useTexture<0)){GLES30.glDeleteProgram(id);throw new IllegalStateException("Missing avatar atlas uniforms");}
            if(hasPbr&&(normalSampler<0||ormSampler<0||pbr<0)){GLES30.glDeleteProgram(id);throw new IllegalStateException("Missing PBR uniforms");}
        }
    }
    private void bindAtlas(){
        for(int i=0;i<2;i++)if(detailTextures[i]!=0){GLES30.glActiveTexture(GLES30.GL_TEXTURE1+i);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,detailTextures[i]);}
        if(ormComparison!=null)ormComparison.verifyBound();
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,atlasTexture);
    }
    private Program selectedPbrProgram(int count){
        if(count!=1&&count!=4)throw new IllegalArgumentException("Invalid individual view group");
        if(pbrFastMathSelected==pbrFastMathRequested)return count==4?multiviewProgram:program;
        if(pbrComparison==null)throw new IllegalStateException("PBR comparison not initialized");
        return count==4?pbrComparison.alternateMultiview:pbrComparison.alternateSingle;
    }
    /** Two shader variants and both draw backends share one synchronous pose, texture set and VBO set. */
    PbrComparison createPbrComparison(){
        if(specializedBatch||pbrComparison!=null||ormComparison!=null||asset.normalMap()==null||poseWorker!=null
                ||drawMode!=DrawMode.VERIFY||program==null||batch==null)
            throw new IllegalStateException("PBR comparison requires a synchronous PBR VERIFY scene without another comparison");
        PbrComparison created=new PbrComparison();
        try{created.initialize();pbrComparison=created;return created;}
        catch(RuntimeException|Error failure){try{created.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    final class PbrComparison implements AutoCloseable {
        private final Thread owner=Thread.currentThread();
        private final int[] bound=new int[1];
        private final String referenceBatchSha=batch.pbrFragmentSha256(false),candidateBatchSha=batch.pbrFragmentSha256(true);
        private Program alternateSingle,alternateMultiview;
        private boolean closed;
        private long referenceIndividual,candidateIndividual,referenceBatched,candidateBatched;
        private void initialize(){
            alternateSingle=new Program(false,true,true,!pbrFastMathRequested);
            if(multiviewProgram!=null)alternateMultiview=new Program(true,true,true,!pbrFastMathRequested);
            batch.beginPbrComparison();
        }
        void draw(float[] vp,int count,float aspect,boolean fast,boolean batched){
            requireOwner();pbrFastMathSelected=fast;batch.selectPbrVariant(fast);
            if(batched)drawBatched(vp,count,aspect);else AvatarGpuScene.this.draw(vp,count,aspect);
            verifyBound(count,fast,batched);
        }
        private void verifyBound(int count,boolean fast,boolean batched){
            requireOwner();
            int expected=batched?batch.selectedPbrProgramId(count):selectedPbrProgram(count).id;
            GLES30.glGetIntegerv(GLES30.GL_CURRENT_PROGRAM,bound,0);
            if(bound[0]!=expected)throw new IllegalStateException("PBR diagnostic program differs from selection");
            checkGl("PBR diagnostic program binding");
            if(batched){if(fast)candidateBatched++;else referenceBatched++;}
            else {if(fast)candidateIndividual++;else referenceIndividual++;}
        }
        JSONObject status()throws Exception {
            return new JSONObject().put("reference_variant",AvatarPbrShaderVariant.REFERENCE).put("candidate_variant",AvatarPbrShaderVariant.FAST)
                .put("reference_individual_binding_checks",referenceIndividual).put("candidate_individual_binding_checks",candidateIndividual)
                .put("reference_batched_binding_checks",referenceBatched).put("candidate_batched_binding_checks",candidateBatched)
                .put("reference_individual_fragment_sha256",AvatarPbrShaderVariant.sha256(AvatarPbrShaderVariant.fragment(false)))
                .put("candidate_individual_fragment_sha256",AvatarPbrShaderVariant.sha256(AvatarPbrShaderVariant.fragment(true)))
                .put("reference_batched_fragment_sha256",referenceBatchSha)
                .put("candidate_batched_fragment_sha256",candidateBatchSha)
                .put("selected_fast_math",pbrFastMathSelected).put("closed",closed)
                .put("scope","Actual GL_CURRENT_PROGRAM checked immediately after each selected draw; shared prepared pose, textures and VBOs; not GPU timing");
        }
        private void requireOwner(){if(closed||Thread.currentThread()!=owner)throw new IllegalStateException("PBR comparison requires live GL owner");}
        @Override public void close(){
            if(Thread.currentThread()!=owner)throw new IllegalStateException("PBR comparison close requires GL owner");
            if(closed)return;closed=true;pbrFastMathSelected=pbrFastMathRequested;pbrComparison=null;
            Program a=alternateSingle,b=alternateMultiview;alternateSingle=null;alternateMultiview=null;
            ResourceCleanup cleanup=new ResourceCleanup(null);
            cleanup.close("PBR comparison batch",batch::endPbrComparison);
            if(a!=null)cleanup.close("PBR comparison single",()->GLES30.glDeleteProgram(a.id));
            if(b!=null)cleanup.close("PBR comparison multiview",()->GLES30.glDeleteProgram(b.id));
            cleanup.close("PBR comparison GL errors",()->checkGl("PBR comparison cleanup"));
            Throwable failure=cleanup.failure();
            if(failure instanceof Error)throw (Error)failure;
            if(failure instanceof RuntimeException)throw (RuntimeException)failure;
            if(failure!=null)throw new IllegalStateException(failure);
        }
    }
    /** Diagnostic only, on this scene's GL owner/current context. RG8 must already be really uploaded.
     * Retains exactly one extra RGBA ORM map; both draws share all other textures, CPU state and VBOs.
     */
    OrmComparison createOrmComparison(){
        if(specializedBatch||ormComparison!=null||pbrComparison!=null||!ormUploadPolicy.requested||!ormUploadPolicy.eligible||!ormRg8Uploaded||detailTextures[1]==0)
            throw new IllegalStateException("ORM comparison requires an actual eligible RG8 scene and no existing comparison");
        int candidate=detailTextures[1],reference=0;
        try{
            uploadMap(asset.ormMap(),2,false);reference=detailTextures[1];
            if(reference==0||reference==candidate)throw new IllegalStateException("ORM reference texture must be distinct");
            ormComparison=new OrmComparison(reference,candidate);return ormComparison;
        }catch(RuntimeException|Error failure){
            if(reference!=0&&reference!=candidate)try{GLES30.glDeleteTextures(1,new int[]{reference},0);checkGl("ORM reference creation cleanup");}
            catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }finally{detailTextures[1]=candidate;ormRg8Uploaded=true;}
    }
    final class OrmComparison implements AutoCloseable {
        private final int reference,candidate;
        private final Thread owner=Thread.currentThread();
        private final int[] bound=new int[1];
        private boolean selectedRg8=true,closed;
        private long rgbaBindings,rgBindings;
        private OrmComparison(int reference,int candidate){this.reference=reference;this.candidate=candidate;}
        void draw(float[] vp,int count,float aspect,boolean rg8,boolean batched){
            requireOwner();selectedRg8=rg8;detailTextures[1]=rg8?candidate:reference;ormRg8Uploaded=rg8;
            if(batched)drawBatched(vp,count,aspect);else AvatarGpuScene.this.draw(vp,count,aspect);
        }
        private void verifyBound(){
            requireOwner();GLES30.glActiveTexture(GLES30.GL_TEXTURE2);GLES30.glGetIntegerv(GLES30.GL_TEXTURE_BINDING_2D,bound,0);
            if(bound[0]!=(selectedRg8?candidate:reference))throw new IllegalStateException("ORM diagnostic texture binding differs from selection");
            checkGl("ORM diagnostic binding");if(selectedRg8)rgBindings++;else rgbaBindings++;
        }
        JSONObject status()throws Exception{return new JSONObject().put("reference_texture",reference).put("candidate_texture",candidate)
                .put("rgba8_binding_checks",rgbaBindings).put("rg8_binding_checks",rgBindings).put("closed",closed)
                .put("scope","actual GL_TEXTURE_2D binding on texture unit 2, checked within each scene bind immediately before drawing");}
        private void requireOwner(){if(closed||Thread.currentThread()!=owner)throw new IllegalStateException("ORM comparison requires live GL owner");}
        @Override public void close(){
            if(Thread.currentThread()!=owner)throw new IllegalStateException("ORM comparison close requires GL owner");
            if(closed)return;closed=true;detailTextures[1]=candidate;ormRg8Uploaded=true;ormComparison=null;
            GLES30.glDeleteTextures(1,new int[]{reference},0);checkGl("ORM reference cleanup");
        }
    }
    private void unbindMaps(){
        for(int i=2;i>=0;i--){GLES30.glActiveTexture(GLES30.GL_TEXTURE0+i);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0);}
    }
    private void uploadAtlas(AvatarAsset.AlbedoAtlas atlas){
        uploadMap(atlas,0);
    }
    /** Albedo is sRGB; normal/ORM are linear. Ownership is recorded before any upload can fail. */
    private void uploadMap(AvatarAsset.AlbedoAtlas atlas,int unit){
        uploadMap(atlas,unit,unit==2&&ormUploadPolicy.eligible);
    }
    private void uploadMap(AvatarAsset.AlbedoAtlas atlas,int unit,boolean rg8){
        Bitmap bitmap=null;int texture=0;
        try(InputStream input=atlas.openStream()){
            BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;options.inPremultiplied=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            bitmap=BitmapFactory.decodeStream(input,null,options);
            if(bitmap==null||bitmap.getWidth()!=atlas.width()||bitmap.getHeight()!=atlas.height()||bitmap.getConfig()!=Bitmap.Config.ARGB_8888)
                throw new IllegalStateException("Atlas PNG decode differs from validated RGB8 dimensions/config");
            int[] name=new int[1];GLES30.glGenTextures(1,name,0);texture=name[0];if(unit==0)atlasTexture=name[0];else detailTextures[unit-1]=name[0];
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0+unit);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,name[0]);
            boolean mip=asset.normalMap()!=null;
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,mip?GLES30.GL_LINEAR_MIPMAP_LINEAR:GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
            int levels=1;if(mip)for(int n=Math.max(atlas.width(),atlas.height());n>1;n>>=1)levels++;
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D,levels,unit==0?GLES30.GL_SRGB8_ALPHA8:rg8?GLES30.GL_RG8:GLES30.GL_RGBA8,atlas.width(),atlas.height());
            if(rg8)uploadRg8Pixels(bitmap);
            else GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D,0,0,0,bitmap,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE);
            if(mip)GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
            checkGl("avatar map upload unit "+unit);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0);GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            if(unit==2)ormRg8Uploaded=rg8;
        } catch(java.io.IOException|RuntimeException|Error failure){
            if(texture!=0){
                try{GLES30.glDeleteTextures(1,new int[]{texture},0);checkGl("failed avatar map deletion");}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}
                if(unit==0)atlasTexture=0;else detailTextures[unit-1]=0;
            }
            if(unit==2)ormRg8Uploaded=false;
            if(failure instanceof java.io.IOException)throw new IllegalStateException("Atlas stream failed",failure);
            if(failure instanceof RuntimeException)throw (RuntimeException)failure;
            throw (Error)failure;
        }
        finally {if(bitmap!=null)bitmap.recycle();}
    }
    /** Construction only, bounded 64-row staging. No asset buffers, pixel memory or shader changes. */
    private static void uploadRg8Pixels(Bitmap bitmap){
        int width=bitmap.getWidth(),height=bitmap.getHeight(),rows=Math.min(height,AvatarOrmUploadPolicy.STRIP_ROWS);
        int[] pixels=new int[width*rows];IntBuffer argb=IntBuffer.wrap(pixels).asReadOnlyBuffer();
        ByteBuffer rg=ByteBuffer.allocateDirect(width*rows*2);
        int[] fields={GLES30.GL_UNPACK_ALIGNMENT,GLES30.GL_UNPACK_ROW_LENGTH,GLES30.GL_UNPACK_SKIP_ROWS,GLES30.GL_UNPACK_SKIP_PIXELS};
        int[] saved=new int[fields.length],pbo=new int[1];
        for(int i=0;i<fields.length;i++)GLES30.glGetIntegerv(fields[i],saved,i);
        GLES30.glGetIntegerv(GLES30.GL_PIXEL_UNPACK_BUFFER_BINDING,pbo,0);checkGl("ORM unpack state");
        if(pbo[0]!=0)throw new IllegalStateException("ORM CPU upload requires no pixel unpack buffer");
        Throwable primary=null;
        try {
            for(int i=0;i<fields.length;i++)GLES30.glPixelStorei(fields[i],i==0?1:0);
            for(int y=0;y<height;y+=rows){
                int n=Math.min(rows,height-y),count=width*n;
                bitmap.getPixels(pixels,0,width,0,y,width,n);
                argb.position(0);argb.limit(count);rg.position(0);rg.limit(count*2);
                AvatarOrmUploadPolicy.packArgb(argb,rg,count);
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D,0,0,y,width,n,GLES30.GL_RG,GLES30.GL_UNSIGNED_BYTE,rg);
            }
            checkGl("ORM RG8 base upload");
        }catch(RuntimeException|Error failure){primary=failure;throw failure;}
        finally {
            try{
                for(int i=0;i<fields.length;i++)GLES30.glPixelStorei(fields[i],saved[i]);
                checkGl("ORM unpack restoration");
            }catch(RuntimeException|Error cleanup){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}
        }
    }
    static long mapGpuBytes(int width,int height,boolean mip){
        long bytes=0;while(true){bytes+=4L*width*height;if(!mip||(width==1&&height==1))return bytes;width=Math.max(1,width/2);height=Math.max(1,height/2);}
    }
    static String atlasVertex(String source){return source.replace("layout(location=2) in vec4 aColor;","layout(location=2) in vec4 aColor;\nlayout(location=4) in vec2 aUV;\nout vec2 vUV;").replace("void main(){","void main(){vUV=aUV;");}
    static String atlasFragment(String source){return source.replace("in vec3 vNormal;","in vec2 vUV;uniform sampler2D uAtlas;uniform float uUseTexture;\nin vec3 vNormal;")
            .replace("vec3 base=clamp(uColor.rgb*vColor.rgb,0.0,1.0);","vec3 albedo=uUseTexture>0.5?texture(uAtlas,vUV).rgb:vec3(1.0);\nvec3 base=clamp(uColor.rgb*vColor.rgb*albedo,0.0,1.0);\n"
                    +"if(uUnlit>0.5){color=vec4(pow(clamp(base,0.0,1.0),vec3(1.0/2.2)),1.0);return;}");}
    static int shader(int kind,String source){int id=GLES30.glCreateShader(kind);try{GLES30.glShaderSource(id,source);GLES30.glCompileShader(id);int[] ok=new int[1];GLES30.glGetShaderiv(id,GLES30.GL_COMPILE_STATUS,ok,0);if(ok[0]==0)throw new IllegalStateException("Avatar shader: "+GLES30.glGetShaderInfoLog(id));return id;}catch(RuntimeException|Error failure){GLES30.glDeleteShader(id);throw failure;}}
    private static void checkGl(String label){int code=GLES30.glGetError();if(code!=GLES30.GL_NO_ERROR)throw new IllegalStateException(label+" GL error "+code);}
    private static IntBuffer directInts(IntBuffer input){IntBuffer b=ByteBuffer.allocateDirect(input.remaining()*4).order(ByteOrder.nativeOrder()).asIntBuffer();b.put(input);b.flip();return b;}
    private static FloatBuffer directFloats(FloatBuffer input){FloatBuffer b=ByteBuffer.allocateDirect(input.remaining()*4).order(ByteOrder.nativeOrder()).asFloatBuffer();b.put(input);b.flip();return b;}
    private static byte[] readBounded(InputStream input,int max)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;while((n=input.read(block))!=-1){if(n==0)continue;if(out.size()>max-n)throw new IllegalArgumentException("Avatar file too large");out.write(block,0,n);}return out.toByteArray();}
    private static String sha256(byte[] bytes)throws Exception{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder result=new StringBuilder();for(byte b:digest)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();}
    static final String VERTEX="""
        #version 300 es
        precision highp float;
        layout(location=0) in vec3 aPosition;
        layout(location=1) in vec3 aNormal;
        layout(location=2) in vec4 aColor;
        uniform mat4 uViewProjection;
        uniform mat4 uWorld;
        uniform mat3 uNormal;
        out vec3 vNormal;out vec3 vPosition;out vec4 vColor;
        void main(){vec4 world=uWorld*vec4(aPosition,1.0);vNormal=uNormal*aNormal;vPosition=world.xyz;vColor=aColor;gl_Position=uViewProjection*world;}
        """;
    static final String FRAGMENT="""
        #version 300 es
        precision highp float;
        in vec3 vNormal;in vec3 vPosition;in vec4 vColor;
        uniform vec4 uColor;uniform float uUnlit;uniform float uRoughness;
        out vec4 color;
        void main(){
            vec3 base=clamp(uColor.rgb*vColor.rgb,0.0,1.0);
            vec3 n=normalize(vNormal),light=normalize(vec3(-0.45,0.65,1.0));
            float diffuse=max(dot(n,light),0.0);
            vec3 h=normalize(light+normalize(vec3(0.0,0.0,3.0)-vPosition));
            float spec=pow(max(dot(n,h),0.0),mix(64.0,8.0,uRoughness))*(1.0-uRoughness)*0.15;
            vec3 lit=base*(0.32+0.68*diffuse)+vec3(spec);
            color=vec4(pow(clamp(mix(lit,base,uUnlit),0.0,1.0),vec3(1.0/2.2)),1.0);
        }
        """;
    // Lightweight material path. Legacy assets retain their frozen v8 lighting above.
    static final String PBR_FRAGMENT="""
        #version 300 es
        precision highp float;
        in vec2 vUV;in vec3 vNormal;in vec3 vPosition;in vec4 vColor;
        uniform vec4 uColor;uniform float uUnlit;uniform float uRoughness;
        uniform sampler2D uAtlas;uniform float uUseTexture;
        uniform sampler2D uNormalMap;uniform sampler2D uOrmMap;
        uniform vec4 uPbrParams;
        out vec4 color;
        vec3 encodeSRGB(vec3 c){return mix(12.92*c,1.055*pow(max(c,vec3(0.0)),vec3(1.0/2.4))-0.055,step(vec3(0.0031308),c));}
        vec3 detailNormal(vec3 n){
            vec3 dp1=dFdx(vPosition),dp2=dFdy(vPosition);vec2 du1=dFdx(vUV),du2=dFdy(vUV);
            float det=du1.x*du2.y-du1.y*du2.x;
            if(abs(det)<1e-10)return n;
            vec3 t=(dp1*du2.y-dp2*du1.y)/det;
            vec3 b=(dp2*du1.x-dp1*du2.x)/det;
            t-=n*dot(n,t);float lengthT=length(t);if(lengthT<1e-8)return n;t/=lengthT;
            b=normalize(cross(n,t))*(dot(cross(n,t),b)<0.0?-1.0:1.0);
            vec3 mapped=texture(uNormalMap,vUV).rgb*2.0-1.0;mapped.xy*=uPbrParams.x;
            return normalize(mat3(t,b,n)*normalize(mapped));
        }
        void main(){
            vec3 albedo=uUseTexture>0.5?texture(uAtlas,vUV).rgb:vec3(1.0);
            vec3 base=clamp(uColor.rgb*vColor.rgb*albedo,0.0,1.0);
            if(uUnlit>0.5){color=vec4(encodeSRGB(base),1.0);return;}
            vec3 n=normalize(vNormal);float roughness=uRoughness,metallic=uPbrParams.z,ao=1.0;
            vec3 orm=vec3(1.0);
            if(uPbrParams.w>0.5){
                n=detailNormal(n);orm=texture(uOrmMap,vUV).rgb;
                roughness=roughness*orm.g;metallic=metallic*orm.b;ao=mix(1.0,orm.r,uPbrParams.y);
            }
            roughness=clamp(roughness,0.12,1.0);
            vec3 v=normalize(vec3(0.0,0.0,3.0)-vPosition),l=normalize(vec3(-0.45,0.65,1.0)),h=normalize(v+l);
            float nl=max(dot(n,l),0.0),nv=max(dot(n,v),0.001),nh=max(dot(n,h),0.0),vh=max(dot(v,h),0.0);
            vec3 f0=mix(vec3(0.04),base,metallic),f=f0+(1.0-f0)*pow(1.0-vh,5.0);
            float a=roughness*roughness,a2=a*a,den=nh*nh*(a2-1.0)+1.0;
            float d=a2/(3.14159265*den*den),k=(roughness+1.0)*(roughness+1.0)/8.0;
            float g=(nv/(nv*(1.0-k)+k))*(nl/(nl*(1.0-k)+k));
            vec3 spec=d*g*f/max(4.0*nv*nl,0.001);
            vec3 diffuse=(1.0-f)*(1.0-metallic)*base/3.14159265;
            vec3 direct=(diffuse+spec)*nl*vec3(2.35,2.20,2.05);
            float fill=max(dot(n,normalize(vec3(0.70,0.25,0.70))),0.0);
            vec3 ambient=base*(1.0-metallic)*(vec3(0.19,0.21,0.24)+vec3(0.10,0.09,0.08)*max(n.y,0.0))*ao;
            vec3 lit=direct+base*(1.0-metallic)*fill*vec3(0.10,0.13,0.18)+ambient;
            color=vec4(encodeSRGB(clamp(lit,0.0,1.0)),1.0);
        }
        """;
}
