package com.mirror.bench;

import android.content.res.AssetManager;
import android.opengl.GLES30;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import static com.mirror.bench.AvatarMultiviewCheck.*;

/** Explicit GL-owner diagnostic, no timers. One synchronous Geralt, same prepared VBOs and textures per pose,
 * Original vs highp fast-math PBR are compared WITHIN each draw backend, not across backends.
 * Readback is deliberately blocking diagnostic work; never a runtime/performance measurement.
 */
public final class AvatarPbrFastMathCheck {
    private static final int W=400,H=640,VIEWS=16;
    private static final float ASPECT=1200f/1920f;
    private static final String MANIFEST_SHA="b4d324f64ea3655fe01615ce97081c275d60d50312fd0d2b066f8a78e668032e";
    private static final int[] DEPTH_ONLY={GLES30.GL_DEPTH_ATTACHMENT};
    private AvatarPbrFastMathCheck(){}
    static JSONObject profile()throws Exception{
        return new JSONObject().put("verification_profile","exact_geralt_pbr_reference_vs_highp_fast_math")
                .put("model_sha256",AvatarOrmUploadPolicy.GERALT_SHA256).put("manifest_sha256",MANIFEST_SHA)
                .put("view_width",W).put("view_height",H).put("views",VIEWS).put("physical_aspect",ASPECT)
                .put("expected_fixtures",9).put("expected_modes",2).put("expected_layer_comparisons",288)
                .put("expected_prepare_calls",9).put("max_rgb_error_allowed",1).put("rmse_allowed",0.1).put("alpha_error_allowed",0)
                .put("legacy_readback_key_scope","serial_* means original PBR reference; ovr_* means highp fast-math candidate, within the named backend")
                .put("scope","pre-interlace full Geralt reference PBR versus highp fast-math, within serial individual and within batched OVR4; same scene/pose/VBO/textures; numerical tolerance is not automatic artistic acceptance")
                .put("performance_evidence",false).put("optical_validation",false).put("artwork_validated",false);
    }
    static boolean pixelsMatch(AvatarPixelComparison.Stats stats){return stats.passed();}
    public static JSONObject run(AssetManager assets,java.io.File files,BooleanSupplier cancelled)throws Exception{
        if(assets==null||files==null)throw new IllegalArgumentException("APK assets and private files required");
        long start=System.nanoTime();JSONObject report=profile().put("passed",false).put("completed",false).put("cancelled",false);
        JSONArray poses=new JSONArray(),cleanupErrors=new JSONArray();report.put("poses",poses).put("cleanup_errors",cleanupErrors);
        SavedState saved=null;ExtraState extra=null;AvatarGpuScene scene=null;AvatarGpuScene.PbrComparison comparison=null;
        PersistentMultiviewFbos groups=null;int[] vao={0},fbos=new int[2],textures=new int[3],depth={0};
        int completed=0,comparisons=0,prepare=0,maxError=0;long mismatches=0,alpha=0;
        double maxRmse=0;int[] changedCenter=new int[2];String[] neutralCenter=new String[2];boolean passed=true;
        try{
            checkCancelled(cancelled);checkGl("PBR check entry");
            String extensions=GLES30.glGetString(GLES30.GL_EXTENSIONS);
            if(extensions==null||!(" "+extensions+" ").contains(" GL_OVR_multiview2 ")||integer(0x9631)<4)
                throw new IllegalStateException("Four-view GL_OVR_multiview2 required");
            if(integer(GLES30.GL_TRANSFORM_FEEDBACK_ACTIVE)!=0)throw new IllegalStateException("Active transform feedback unsupported");
            if(integer(GLES30.GL_MAX_ARRAY_TEXTURE_LAYERS)<16||integer(GLES30.GL_MAX_TEXTURE_SIZE)<2048)
                throw new IllegalStateException("Device texture limits insufficient");
            report.put("gl_vendor",GLES30.glGetString(GLES30.GL_VENDOR)).put("gl_renderer",GLES30.glGetString(GLES30.GL_RENDERER))
                    .put("gl_version",GLES30.glGetString(GLES30.GL_VERSION));
            extra=new ExtraState();saved=new SavedState();
            GLES30.glGenVertexArrays(1,vao,0);GLES30.glBindVertexArray(vao[0]);
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER,0);GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER,0);
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT,1);GLES30.glPixelStorei(GLES30.GL_PACK_ROW_LENGTH,0);
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_ROWS,0);GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_PIXELS,0);
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT,4);GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH,0);
            GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_ROWS,0);GLES30.glPixelStorei(GLES30.GL_UNPACK_SKIP_PIXELS,0);
            for(int cap:DISABLED)GLES30.glDisable(cap);
            GLES30.glColorMask(true,true,true,true);GLES30.glDepthMask(true);GLES30.glDepthFunc(GLES30.GL_LESS);
            GLES30.glDepthRangef(0,1);GLES30.glClearDepthf(1);GLES30.glViewport(0,0,W,H);
            scene=AvatarGpuScene.bundled(assets,"avatars/catalog/geralt",AvatarOrmUploadPolicy.GERALT_SHA256,MANIFEST_SHA,
                    true,false,AvatarGpuScene.DrawMode.VERIFY,false,false);
            JSONObject initial=scene.status();report.put("avatar_initial",initial);
            comparison=scene.createPbrComparison();
            GLES30.glGenFramebuffers(2,fbos,0);GLES30.glGenTextures(3,textures,0);GLES30.glGenRenderbuffers(1,depth,0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,textures[0]);GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D,1,GLES30.GL_RGBA8,W,H);
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER,depth[0]);GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER,GLES30.GL_DEPTH_COMPONENT16,W,H);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbos[0]);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,textures[0],0);
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER,GLES30.GL_DEPTH_ATTACHMENT,GLES30.GL_RENDERBUFFER,depth[0]);complete("PBR serial");
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,textures[1]);GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY,1,GLES30.GL_RGBA8,W,H,VIEWS);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,textures[2]);GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY,1,GLES30.GL_DEPTH_COMPONENT16,W,H,VIEWS);
            groups=PersistentMultiviewFbos.create(PersistentMultiviewGl.INSTANCE,1,VIEWS,textures[1],textures[2]);
            float[] vp=viewMatrices(VIEWS,ASPECT),four=new float[64],one=new float[16],weights=new float[52],angles=new float[3];
            JSONObject matrix=sixteenMatrixGate(vp);report.put("matrix_gate",matrix);
            if(!matrix.getBoolean("passed"))throw new IllegalStateException("Independent sixteen-camera matrix gate failed");
            ByteBuffer[] reference=new ByteBuffer[4];for(int i=0;i<4;i++)reference[i]=ByteBuffer.allocateDirect(W*H*4);
            ByteBuffer candidate=ByteBuffer.allocateDirect(W*H*4);
            var fixtures=fixtures();if(fixtures.size()!=9)throw new IllegalStateException("Expected 9 material diagnostic poses");
            for(var pose:fixtures){
                checkCancelled(cancelled);pose.copyWeights(weights);pose.copyAngles(angles);scene.prepare(weights,angles);prepare++;
                JSONObject row=new JSONObject().put("name",pose.name()).put("source_index",pose.sourceIndex())
                        .put("weights",new JSONArray(weights)).put("head_angles",new JSONArray(angles)).put("passed",false);
                JSONArray modes=new JSONArray();row.put("modes",modes);poses.put(row);boolean posePass=true;
                for(int mode=0;mode<2;mode++){
                    boolean batched=mode==1;String modeName=batched?"batched_ovr4":"individual_serial";
                    HashSet<String> referenceHashes=new HashSet<>(),candidateHashes=new HashSet<>();String center=null;
                    JSONArray layers=new JSONArray();JSONObject modeReport=new JSONObject().put("backend",modeName).put("layers",layers).put("passed",false);
                    modes.put(modeReport);boolean modePass=true;
                    String rk=batched?"reference_batched_binding_checks":"reference_individual_binding_checks";
                    String ck=batched?"candidate_batched_binding_checks":"candidate_individual_binding_checks";
                    JSONObject before=comparison.status();long referenceBefore=before.getLong(rk),candidateBefore=before.getLong(ck);
                    for(int base=0;base<VIEWS;base+=4){
                        checkCancelled(cancelled);System.arraycopy(vp,base*16,four,0,64);
                        if(batched){groups.bind(base,1);clear();comparison.draw(four,4,ASPECT,false,true);invalidate();}
                        for(int rel=0;rel<4;rel++){
                            if(batched)readLayer(fbos[1],textures[1],base+rel,reference[rel]);
                            else{System.arraycopy(vp,(base+rel)*16,one,0,16);serial(fbos[0],comparison,one,false,reference[rel]);}
                        }
                        if(batched){groups.bind(base,1);clear();comparison.draw(four,4,ASPECT,true,true);invalidate();}
                        for(int rel=0;rel<4;rel++){
                            checkCancelled(cancelled);int view=base+rel;
                            if(batched)readLayer(fbos[1],textures[1],view,candidate);
                            else{System.arraycopy(vp,view*16,one,0,16);serial(fbos[0],comparison,one,true,candidate);}
                            var stats=AvatarPixelComparison.compare(reference[rel],candidate,W,H);boolean equal=pixelsMatch(stats);
                            JSONObject s=toJson(stats).put("passed",equal).put("global_view",view).put("ovr_base",batched?base:-1)
                                    .put("reference_shader",AvatarPbrShaderVariant.REFERENCE).put("candidate_shader",AvatarPbrShaderVariant.FAST);
                            layers.put(s);comparisons++;modePass&=equal;maxError=Math.max(maxError,stats.maxRgbError);
                            mismatches+=stats.rgbMismatches;alpha+=stats.alphaMismatches;maxRmse=Math.max(maxRmse,stats.rmse);
                            referenceHashes.add(stats.serialSha256);candidateHashes.add(stats.candidateSha256);if(view==VIEWS/2){center=stats.serialSha256;modeReport.put("preview",savePair(files,modeName,pose.name(),reference[rel],candidate));}
                        }
                    }
                    JSONObject bound=comparison.status();long referenceDraws=bound.getLong(rk)-referenceBefore,candidateDraws=bound.getLong(ck)-candidateBefore;
                    modePass&=referenceHashes.size()==VIEWS&&candidateHashes.size()==VIEWS&&referenceDraws==(batched?4:16)&&candidateDraws==(batched?4:16);
                    if(neutralCenter[mode]==null)neutralCenter[mode]=center;else if(!neutralCenter[mode].equals(center))changedCenter[mode]++;
                    modeReport.put("reference_distinct_views",referenceHashes.size()).put("candidate_distinct_views",candidateHashes.size())
                            .put("reference_program_binding_checks",referenceDraws).put("candidate_program_binding_checks",candidateDraws)
                            .put("center_differs_from_neutral",!neutralCenter[mode].equals(center)).put("passed",modePass);
                    posePass&=modePass;
                }
                row.put("passed",posePass);passed&=posePass;completed++;
            }
            checkCancelled(cancelled);checkGl("PBR comparison complete");report.put("shader_comparison",comparison.status());
            passed&=completed==9&&comparisons==288&&prepare==9&&changedCenter[0]>0&&changedCenter[1]>0;
            report.put("completed",true).put("passed",passed);
        }catch(Exception|LinkageError failure){report.put("error",failure.toString()).put("cancelled",failure instanceof CancellationException).put("passed",false);}
        finally{
            if(comparison!=null)try{comparison.close();report.put("comparison_closed",true);}catch(Exception|LinkageError failure){cleanupErrors.put(failure.toString());}
            if(scene!=null){
                try{report.put("avatar_after_restore",scene.status());}catch(Exception|LinkageError failure){cleanupErrors.put(failure.toString());}
                try{scene.dispose();}catch(Exception|LinkageError failure){cleanupErrors.put(failure.toString());}
            }
            if(groups!=null)try{groups.close(1);}catch(Exception|LinkageError failure){cleanupErrors.put(failure.toString());}
            try{GLES30.glDeleteRenderbuffers(1,depth,0);GLES30.glDeleteTextures(3,textures,0);GLES30.glDeleteFramebuffers(2,fbos,0);GLES30.glDeleteVertexArrays(1,vao,0);}
            catch(Exception|LinkageError failure){cleanupErrors.put(failure.toString());}
            try{if(saved!=null)saved.restore();}catch(Exception|LinkageError failure){cleanupErrors.put(failure.toString());}
            try{if(extra!=null)extra.restore();checkGl("PBR state restoration");}
            catch(Exception|LinkageError failure){cleanupErrors.put(failure.toString());}
            if(cleanupErrors.length()!=0)report.put("passed",false);
            report.put("completed_fixtures",completed).put("layer_comparisons",comparisons).put("prepare_calls",prepare)
                    .put("max_rmse",maxRmse).put("max_rgb_error",maxError).put("rgb_byte_mismatches",mismatches).put("alpha_mismatches",alpha)
                    .put("serial_changed_center_poses",changedCenter[0]).put("batched_changed_center_poses",changedCenter[1])
                    .put("diagnostic_elapsed_ms",(System.nanoTime()-start)/1e6);
        }
        return report;
    }
    static java.util.List<AvatarPoseFixtures.Pose> fixtures(){
        String[] names={"neutral","source-eyeBlinkLeft","source-eyeBlinkRight","source-jawOpen","jaw-mouth-close-corrective","head-yaw-left","head-yaw-right","smile-blink-tilt","all-controls"};
        java.util.ArrayList<AvatarPoseFixtures.Pose> result=new java.util.ArrayList<>();var all=AvatarPoseFixtures.regression();
        for(String name:names)result.add(all.stream().filter(p->p.name().equals(name)).findFirst().orElseThrow());
        return java.util.Collections.unmodifiableList(result);
    }
    private static JSONObject savePair(java.io.File files,String backend,String pose,ByteBuffer reference,ByteBuffer candidate)throws Exception{
        String directory="avatar-pbr-fast-math-preview/"+backend;
        java.io.File folder=new java.io.File(files,directory);if(!folder.isDirectory()&&!folder.mkdirs())throw new IllegalStateException("Preview directory failed");
        return new JSONObject().put("view",8).put("reference",savePng(reference,new java.io.File(folder,pose+"-reference.png"),directory+"/"+pose+"-reference.png"))
                .put("candidate",savePng(candidate,new java.io.File(folder,pose+"-candidate.png"),directory+"/"+pose+"-candidate.png"));
    }
    private static JSONObject savePng(ByteBuffer pixels,java.io.File target,String relative)throws Exception{
        int[] argb=new int[W*H];
        for(int y=0;y<H;y++)for(int x=0;x<W;x++){int p=(y*W+x)*4;argb[(H-1-y)*W+x]=((pixels.get(p+3)&255)<<24)|((pixels.get(p)&255)<<16)|((pixels.get(p+1)&255)<<8)|(pixels.get(p+2)&255);}
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(argb,W,H,android.graphics.Bitmap.Config.ARGB_8888);
        android.util.AtomicFile atomic=new android.util.AtomicFile(target);java.io.FileOutputStream stream=null;
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
        try{stream=atomic.startWrite();var hashing=new java.security.DigestOutputStream(stream,digest);
            if(!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,hashing))throw new IllegalStateException("Preview PNG failed");
            hashing.flush();atomic.finishWrite(stream);stream=null;
            return new JSONObject().put("file",relative).put("sha256",hex(digest.digest())).put("bytes",target.length());
        }catch(Exception failure){if(stream!=null)atomic.failWrite(stream);throw failure;}finally{bitmap.recycle();}
    }
    private static String hex(byte[] bytes){char[] h="0123456789abcdef".toCharArray(),s=new char[bytes.length*2];for(int i=0;i<bytes.length;i++){s[i*2]=h[(bytes[i]&255)>>>4];s[i*2+1]=h[bytes[i]&15];}return new String(s);}
    private static void serial(int fbo,AvatarGpuScene.PbrComparison comparison,float[] vp,boolean fast,ByteBuffer destination){
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo);clear();comparison.draw(vp,1,ASPECT,fast,false);invalidate();read(destination);
    }
    private static void invalidate(){GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER,1,DEPTH_ONLY,0);checkGl("PBR draw");}
    private static void readLayer(int fbo,int texture,int layer,ByteBuffer destination){
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo);GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,texture,0,layer);
        complete("PBR single-layer read");read(destination);
    }
    private static void read(ByteBuffer destination){destination.clear();GLES30.glReadPixels(0,0,W,H,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,destination);checkGl("PBR pixels");}
    /** Legacy SavedState predates PBR: preserve the additional normal/ORM bindings and unpack state. */
    private static final class ExtraState{
        final int active=integer(GLES30.GL_ACTIVE_TEXTURE),unpack=integer(GLES30.GL_PIXEL_UNPACK_BUFFER_BINDING);
        final int[] names={GLES30.GL_UNPACK_ALIGNMENT,GLES30.GL_UNPACK_ROW_LENGTH,GLES30.GL_UNPACK_SKIP_ROWS,GLES30.GL_UNPACK_SKIP_PIXELS};
        final int[] values=new int[4],textures=new int[2];final float[] uv=new float[4];
        ExtraState(){for(int i=0;i<4;i++)values[i]=integer(names[i]);GLES30.glGetVertexAttribfv(4,GLES30.GL_CURRENT_VERTEX_ATTRIB,uv,0);
            for(int i=0;i<2;i++){GLES30.glActiveTexture(GLES30.GL_TEXTURE1+i);textures[i]=integer(GLES30.GL_TEXTURE_BINDING_2D);}GLES30.glActiveTexture(active);}
        void restore(){for(int i=0;i<2;i++){GLES30.glActiveTexture(GLES30.GL_TEXTURE1+i);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,textures[i]);}
            GLES30.glActiveTexture(active);GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER,unpack);for(int i=0;i<4;i++)GLES30.glPixelStorei(names[i],values[i]);
            GLES30.glVertexAttrib4f(4,uv[0],uv[1],uv[2],uv[3]);}
    }
}
