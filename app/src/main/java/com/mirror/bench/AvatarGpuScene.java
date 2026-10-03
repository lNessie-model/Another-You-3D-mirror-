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
    private final List<Integer> allocatedBuffers=new ArrayList<>();
    private final float[] world=new float[16], fittedWorld=new float[16], fit=new float[16], inverse=new float[16], normal=new float[9];
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

    static AvatarGpuScene builtin(AssetManager assets,boolean multiview) throws Exception {
        return builtin(assets,multiview,false);
    }
    static AvatarGpuScene builtin(AssetManager assets,boolean multiview,boolean asynchronous) throws Exception {
        return builtin(assets,multiview,asynchronous,DrawMode.INDIVIDUAL);
    }
    static AvatarGpuScene builtin(AssetManager assets,boolean multiview,boolean asynchronous,DrawMode drawMode) throws Exception {
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
        return fromAsset(asset,new String(manifest,StandardCharsets.UTF_8),hash,multiview,asynchronous,drawMode);
    }
    /** Debug-only caller uses this fixed app-private diagnostic directory; never selects a stored avatar. */
    static AvatarGpuScene privateHeadCheck(java.io.File files,boolean multiview,DrawMode mode)throws Exception {
        return privateHeadCheck(files,multiview,false,mode);
    }
    static AvatarGpuScene privateHeadCheck(java.io.File files,boolean multiview,boolean asynchronous,DrawMode mode)throws Exception {
        java.io.File directory=new java.io.File(files,"tripo-head-check");byte[] manifest,glb;
        try(InputStream in=new java.io.FileInputStream(new java.io.File(directory,"avatar.json"))){manifest=readBounded(in,262_144);}
        JSONObject json=new JSONObject(new String(manifest,StandardCharsets.UTF_8));
        if(!"character.glb".equals(json.getString("model")))throw new IllegalArgumentException("Diagnostic head model filename");
        try(InputStream in=new java.io.FileInputStream(new java.io.File(directory,"character.glb"))){glb=readBounded(in,AvatarGlbLoader.MAX_FILE_BYTES);}
        String hash=sha256(glb);if(!hash.equalsIgnoreCase(json.getString("modelSha256")))throw new IllegalArgumentException("Diagnostic head digest mismatch");
        return fromAsset(AvatarGlbLoader.load(glb),new String(manifest,StandardCharsets.UTF_8),hash,multiview,asynchronous,mode);
    }
    /** Inputs are a validated CPU asset/manifest and its verified model digest; no package-store dependency. */
    static AvatarGpuScene fromAsset(AvatarAsset asset,String manifestJson,String modelSha256,boolean multiview,boolean asynchronous) throws Exception {
        return fromAsset(asset,manifestJson,modelSha256,multiview,asynchronous,DrawMode.INDIVIDUAL);
    }
    static AvatarGpuScene fromAsset(AvatarAsset asset,String manifestJson,String modelSha256,boolean multiview,boolean asynchronous,DrawMode drawMode) throws Exception {
        if(drawMode==null)throw new IllegalArgumentException("Avatar draw mode required");
        if(drawMode==DrawMode.VERIFY&&asynchronous)throw new IllegalArgumentException("Avatar pixel verification requires synchronous pose ownership");
        AvatarRig rig=new AvatarRig(asset,manifestJson);
        AvatarGpuScene scene=new AvatarGpuScene(asset,rig,modelSha256,multiview,drawMode);
        try {
            if(asynchronous)scene.poseWorker=new AvatarPoseWorker(asset,manifestJson);
            return scene;
        } catch(Exception|Error failure){scene.dispose();throw failure;}
    }
    @SuppressWarnings("unchecked")
    private AvatarGpuScene(AvatarAsset asset,AvatarRig rig,String hash,boolean multiview,DrawMode drawMode) {
        this.asset=asset;this.rig=rig;sha256=hash;this.drawMode=drawMode;
        displayedWorlds=new float[asset.nodes().size()*16];
        deformer=new AvatarDeformer(asset,rig.normalPolicy().equals("authored")?AvatarDeformer.NormalPolicy.AUTHORED:AvatarDeformer.NormalPolicy.RECOMPUTE_DEFORMED);
        weights=new float[asset.meshes().size()][];primitives=new List[weights.length];
        try {
        boolean atlas=asset.albedoAtlas()!=null;
        if(atlas)uploadAtlas(asset.albedoAtlas());
        if(drawMode!=DrawMode.BATCHED){program=new Program(false,atlas);multiviewProgram=multiview?new Program(true,atlas):null;}
        int count=0;
        for(int m=0;m<weights.length;m++) {
            weights[m]=new float[asset.meshes().get(m).targetCount()];
            primitives[m]=new ArrayList<>();int p=0;
            for(AvatarAsset.Primitive primitive:asset.meshes().get(m).primitives()) {
                primitives[m].add(new GpuPrimitive(primitive,deformer.primitives(m).get(p++)));count++;
            }
        }
        primitiveCount=count;
        if(drawMode!=DrawMode.INDIVIDUAL)batch=new AvatarBatchGpu(asset,rig,multiview);
        framing=AvatarGeometryBounds.fromAsset(asset,rig);
        prepare(new float[52],new float[3]);
        checkGl("avatar initialization");
        } catch(RuntimeException|Error failure) {dispose();throw failure;}
    }
    /** Call only while the owning EGL context is current. On context loss discard this object instead. */
    void dispose() {
        stopCpu();
        if(batch!=null){batch.dispose();batch=null;}
        for(int buffer:allocatedBuffers)GLES30.glDeleteBuffers(1,new int[]{buffer},0);
        allocatedBuffers.clear();
        if(atlasTexture!=0){GLES30.glDeleteTextures(1,new int[]{atlasTexture},0);atlasTexture=0;}
        if(program!=null){GLES30.glDeleteProgram(program.id);program=null;}
        if(multiviewProgram!=null){GLES30.glDeleteProgram(multiviewProgram.id);multiviewProgram=null;}
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
        if(drawMode==DrawMode.BATCHED){drawBatched(viewProjections,viewCount,aspect);return;}
        if((viewCount!=1&&viewCount!=4)||viewProjections.length<viewCount*16)throw new IllegalArgumentException("Invalid avatar view group");
        Program active=viewCount==4?multiviewProgram:program;
        if(active==null)throw new IllegalStateException("Avatar multiview program was not initialized");
        // Fixed envelope includes morphs and rigid joints; preserve framing throughout every head pose.
        // Uniform root coordinate scale is absorbed by this normalized automatic fit, not extra zoom.
        framing.copyFitMatrix(aspect,fit);
        GLES30.glUseProgram(active.id);
        if(atlasTexture!=0){bindAtlas();GLES30.glUniform1i(active.sampler,0);}
        GLES30.glUniformMatrix4fv(active.viewProjection,viewCount,false,viewProjections,0);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);GLES30.glEnable(GLES30.GL_CULL_FACE);
        GLES30.glFrontFace(GLES30.GL_CCW);GLES30.glCullFace(GLES30.GL_BACK);
        GLES30.glDisable(GLES30.GL_BLEND);
        for(int n=0;n<asset.nodes().size();n++)if(rig.activeNode(n)) {
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
            }
        }
        for(int i=0;i<3;i++)GLES30.glDisableVertexAttribArray(i);
        if(atlasTexture!=0){GLES30.glDisableVertexAttribArray(4);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0);}
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0);GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER,0);
    }
    /** VERIFY keeps independent legacy buffers; both draws share one prepared CPU pose. */
    void drawBatched(float[] viewProjections,int viewCount,float aspect) {
        if(batch==null)throw new IllegalStateException("Avatar batch backend not initialized");
        if(atlasTexture!=0)bindAtlas();
        batch.draw(viewProjections,viewCount,aspect,framing,displayedWorlds);
        if(atlasTexture!=0)GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0);
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
                .put("normal_policy",rig.normalPolicy()).put("deformation_scope","once per animation snapshot; all independent views share the same VBO")
                .put("draw_backend",drawMode.name().toLowerCase(java.util.Locale.ROOT));
        }
        AvatarBatchGpu currentBatch=batch;
        if(asset.albedoAtlas()!=null)value.put("albedo_atlas",new JSONObject().put("width",asset.albedoAtlas().width()).put("height",asset.albedoAtlas().height()).put("encoded_bytes",asset.albedoAtlas().encodedBytes()));
        if(currentBatch!=null)value.put("batch",currentBatch.status());
        if(poseWorker!=null) {
            var s=poseWorker.status();value.put("pose_worker",new JSONObject().put("submitted_inputs",s.submittedInputs)
                    .put("processed_inputs",s.processedInputs).put("published_frames",s.publishedFrames)
                    .put("dropped_pending_inputs",s.droppedInputs).put("superseded_ready_frames",s.droppedReadyFrames)
                    .put("pending_count",s.pendingCount).put("ready_count",s.readyCount).put("writing_count",s.writingCount)
                    .put("leased_count",s.leasedCount).put("max_pending_count",s.maxPendingCount).put("max_ready_count",s.maxReadyCount)
                    .put("output_slots",AvatarPoseWorker.OUTPUT_SLOTS).put("closed",s.closed).put("terminated",s.terminated));
        }
        return value;
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
        final int id,viewProjection,world,normal,color,unlit,roughness,sampler,useTexture;
        Program(boolean multiview,boolean atlas) {
            String vertex=atlas?atlasVertex(VERTEX):VERTEX;
            if(multiview)vertex=vertex.replace("#version 300 es","#version 300 es\n#extension GL_OVR_multiview2 : require\nlayout(num_views=4) in;")
                    .replace("uniform mat4 uViewProjection;","uniform mat4 uViewProjection[4];")
                    .replace("uViewProjection*", "uViewProjection[gl_ViewID_OVR]*");
            int vs=0,fs=0,created=0;
            try {
                vs=shader(GLES30.GL_VERTEX_SHADER,vertex);fs=shader(GLES30.GL_FRAGMENT_SHADER,atlas?atlasFragment(FRAGMENT):FRAGMENT);
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
            if(atlas&&(sampler<0||useTexture<0)){GLES30.glDeleteProgram(id);throw new IllegalStateException("Missing avatar atlas uniforms");}
        }
    }
    private void bindAtlas(){GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,atlasTexture);}
    private void uploadAtlas(AvatarAsset.AlbedoAtlas atlas){
        Bitmap bitmap=null;
        try(InputStream input=atlas.openStream()){
            BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;options.inPremultiplied=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            bitmap=BitmapFactory.decodeStream(input,null,options);
            if(bitmap==null||bitmap.getWidth()!=atlas.width()||bitmap.getHeight()!=atlas.height()||bitmap.getConfig()!=Bitmap.Config.ARGB_8888)
                throw new IllegalStateException("Atlas PNG decode differs from validated RGB8 dimensions/config");
            int[] name=new int[1];GLES30.glGenTextures(1,name,0);atlasTexture=name[0];bindAtlas();
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D,1,GLES30.GL_SRGB8_ALPHA8,atlas.width(),atlas.height());
            GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D,0,0,0,bitmap,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE);
            checkGl("avatar atlas upload");GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0);
        } catch(java.io.IOException e){throw new IllegalStateException("Atlas stream failed",e);}
        finally {if(bitmap!=null)bitmap.recycle();}
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
}
