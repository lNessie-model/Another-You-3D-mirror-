package com.mirror.bench;

import android.content.res.AssetManager;
import android.opengl.GLES30;
import android.opengl.Matrix;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit blocking finite-pose pixel gate. Readback duration is never performance evidence. */
final class AvatarSpecializedCheck {
    private static final int W=400,H=640,VIEWS=16;
    private static final float ASPECT=1200f/1920f;
    static JSONObject run(AssetManager assets,SceneViewSettings saved,BooleanSupplier cancelled,Consumer<JSONObject> progress)throws Exception {
        return run(assets,saved,cancelled,progress,false);
    }
    static JSONObject run(AssetManager assets,SceneViewSettings saved,BooleanSupplier cancelled,Consumer<JSONObject> progress,boolean constantWhitePrimary)throws Exception {
        Throwable primary=null;AvatarGpuScene scene=null;int[] textures=new int[3],fbos=new int[2];
        JSONArray fixtures=new JSONArray();var poses=AvatarPoseFixtures.regression();
        JSONObject out=new JSONObject().put("running",true).put("passed",false).put("performance_evidence",false)
                .put("scope","Ordinary INDIVIDUAL OVR4 versus per-entry specialized OVR4; one CPU pose updates separate primitive and packed buffers, shared original textures; all 16 off-axis layers before interlacing")
                .put("reference_backend","individual_ovr4").put("candidate_backend",constantWhitePrimary?"per_entry_specialized_constant_white_ovr4":"per_entry_specialized_ovr4")
                .put("constant_white_primary_requested",constantWhitePrimary)
                .put("buffer_scope","separate individual and packed VBO/IBO; identical CPU deformation input, not shared VBO")
                .put("views",VIEWS).put("view_width",W).put("view_height",H).put("expected_fixtures",poses.size())
                .put("expected_layer_comparisons",poses.size()*VIEWS).put("max_rgb_error_allowed",1).put("rmse_allowed",.1)
                .put("alpha_error_allowed",0).put("scene_settings",new JSONObject(saved.toMap())).put("fixtures",fixtures)
                .put("artwork_validated",false).put("all_possible_poses_validated",false);
        try {
            String extensions=GLES30.glGetString(GLES30.GL_EXTENSIONS);int[] max=new int[1];GLES30.glGetIntegerv(0x9631,max,0);
            if(extensions==null||!(" "+extensions+" ").contains(" GL_OVR_multiview2 ")||max[0]<4)throw new IllegalStateException("OVR4 required");
            out.put("gl_renderer",GLES30.glGetString(GLES30.GL_RENDERER)).put("gl_version",GLES30.glGetString(GLES30.GL_VERSION));
            var entry=BundledAvatarCatalog.find(BundledAvatarCatalog.read(assets),"geralt");
            scene=AvatarGpuScene.bundled(assets,entry.directory,entry.modelSha256,entry.manifestSha256,true,false,AvatarGpuScene.DrawMode.VERIFY);
            scene.setSceneView(saved);scene.beginSpecializedBatch(constantWhitePrimary);out.put("model_sha256",entry.modelSha256).put("manifest_sha256",entry.manifestSha256);
            GLES30.glGenTextures(3,textures,0);GLES30.glGenFramebuffers(2,fbos,0);
            for(int i=0;i<2;i++){GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,textures[i]);GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY,1,GLES30.GL_RGBA8,W,H,VIEWS);}
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,textures[2]);GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY,1,GLES30.GL_DEPTH_COMPONENT16,W,H,VIEWS);
            GLES30.glDisable(GLES30.GL_DITHER);GLES30.glDisable(GLES30.GL_SCISSOR_TEST);GLES30.glColorMask(true,true,true,true);GLES30.glDepthMask(true);GLES30.glDepthFunc(GLES30.GL_LESS);
            float[] vp=new float[64],view=new float[16],projection=new float[16],raw=new float[52],angles=new float[3],mapped=new float[52],mappedAngles=new float[3];
            ByteBuffer reference=ByteBuffer.allocateDirect(W*H*4),candidate=ByteBuffer.allocateDirect(W*H*4);
            int layers=0,observedMax=0;double observedRmse=0;long alpha=0,rgb=0;boolean passed=true;
            for(var pose:poses){
                cancel(cancelled);pose.copyWeights(raw);pose.copyAngles(angles);FacePlayback.apply(raw,angles,saved.mirrorMotion,saved.expressionGain,mapped,mappedAngles);
                long beforePose=scene.status().getLong("morph_updates");scene.prepare(mapped,mappedAngles);
                if(scene.status().getLong("morph_updates")!=beforePose+1)throw new IllegalStateException("Expected one pose calculation updating both buffer sets");
                var before=scene.status();
                for(int pass=0;pass<2;pass++){
                    for(int start=0;start<VIEWS;start+=4){
                        cancel(cancelled);GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbos[0]);
                        MultiviewGl.attach(GLES30.GL_COLOR_ATTACHMENT0,textures[pass],start,4);MultiviewGl.attach(GLES30.GL_DEPTH_ATTACHMENT,textures[2],start,4);complete();
                        GLES30.glViewport(0,0,W,H);GLES30.glClearColor(.035f,.045f,.06f,1);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);
                        for(int r=0;r<4;r++){
                            float eye=saved.eyeAt(start+r,VIEWS),shift=saved.frustumShift(eye);
                            Matrix.setLookAtM(view,0,eye,0,3,eye,0,0,0,1,0);Matrix.frustumM(projection,0,-.052f*ASPECT+shift,.052f*ASPECT+shift,-.052f,.052f,.1f,10);
                            Matrix.multiplyMM(vp,r*16,projection,0,view,0);
                        }
                        drawVerifiedGroup(scene,vp,4,ASPECT,pass==1);
                        GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER,1,new int[]{GLES30.GL_DEPTH_ATTACHMENT},0);checkGl("paired draw");
                    }
                }
                var after=scene.status();
                long originalCalls=after.getLong("individual_draw_calls")-before.getLong("individual_draw_calls");
                long originalGroups=after.getLong("individual_view_groups")-before.getLong("individual_view_groups");
                JSONObject beforeBatch=before.getJSONObject("batch"),afterBatch=after.getJSONObject("batch");
                long specializedCalls=afterBatch.getJSONObject("specialized").getLong("draw_calls")-beforeBatch.getJSONObject("specialized").getLong("draw_calls");
                long specializedGroups=afterBatch.getJSONObject("specialized").getLong("multiview_groups")-beforeBatch.getJSONObject("specialized").getLong("multiview_groups");
                if(originalCalls!=28||specializedCalls!=28||originalGroups!=4||specializedGroups!=4
                        ||afterBatch.getLong("reference_draw_calls")!=beforeBatch.getLong("reference_draw_calls"))
                    throw new IllegalStateException("Expected individual and specialized 28 draws / 4 groups, no original packed-batch draw");
                JSONArray comparisons=new JSONArray();HashSet<String> originalHashes=new HashSet<>(),candidateHashes=new HashSet<>();
                for(int layer=0;layer<VIEWS;layer++){
                    cancel(cancelled);read(textures[0],layer,fbos[1],reference);read(textures[1],layer,fbos[1],candidate);
                    var s=AvatarPixelComparison.compare(reference,candidate,W,H);passed&=s.passed();layers++;
                    observedMax=Math.max(observedMax,s.maxRgbError);observedRmse=Math.max(observedRmse,s.rmse);alpha+=s.alphaMismatches;rgb+=s.rgbMismatches;
                    originalHashes.add(s.serialSha256);candidateHashes.add(s.candidateSha256);
                    comparisons.put(new JSONObject().put("view",layer).put("passed",s.passed()).put("max_rgb_error",s.maxRgbError).put("rmse",s.rmse)
                            .put("rgb_mismatches",s.rgbMismatches).put("alpha_mismatches",s.alphaMismatches).put("original_foreground",s.serialForeground)
                            .put("candidate_foreground",s.candidateForeground).put("original_sha256",s.serialSha256).put("candidate_sha256",s.candidateSha256));
                }
                boolean distinct=originalHashes.size()==VIEWS&&candidateHashes.size()==VIEWS;passed&=distinct;
                fixtures.put(new JSONObject().put("pose",pose.name()).put("layers",comparisons).put("original_draw_calls",originalCalls).put("candidate_draw_calls",specializedCalls)
                        .put("original_view_groups",originalGroups).put("candidate_view_groups",specializedGroups)
                        .put("original_program_binding_checks",4).put("candidate_program_binding_checks",4)
                        .put("original_distinct_views",originalHashes.size()).put("candidate_distinct_views",candidateHashes.size()));
                out.put("completed_fixtures",fixtures.length()).put("completed_layer_comparisons",layers).put("observed_max_rgb_error",observedMax)
                        .put("observed_max_layer_rmse",observedRmse).put("alpha_mismatches",alpha).put("rgb_mismatches",rgb);progress.accept(out);
            }
            cancel(cancelled);out.put("scene_status",scene.status());scene.endSpecializedBatch();out.put("after_scope_status",scene.status());
            out.put("running",false).put("passed",passed&&layers==poses.size()*VIEWS);return out;
        }catch(Exception|Error failure){primary=failure;throw failure;}finally{
            ResourceCleanup cleanup=new ResourceCleanup(null);
            if(scene!=null){AvatarGpuScene owned=scene;cleanup.close("specialized scene",owned::dispose);}
            cleanup.close("diagnostic default framebuffer",()->GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0));
            cleanup.close("diagnostic framebuffers",()->GLES30.glDeleteFramebuffers(2,fbos,0));
            cleanup.close("diagnostic textures",()->GLES30.glDeleteTextures(3,textures,0));
            cleanup.close("diagnostic GL errors",()->checkGl("diagnostic cleanup"));
            if(cleanup.failure()!=null){if(primary!=null)primary.addSuppressed(cleanup.failure());else throw new IllegalStateException("Specialized diagnostic cleanup failed",cleanup.failure());}
        }
    }
    /** Actual checker routing, also exercised by the host GL-boundary test. No pose preparation here. */
    static void drawVerifiedGroup(AvatarGpuScene scene,float[] vp,int count,float aspect,boolean specialized)throws Exception {
        scene.selectSpecializedBatch(specialized);
        if(specialized)scene.drawBatched(vp,count,aspect);else scene.draw(vp,count,aspect);
        int[] bound=new int[1];GLES30.glGetIntegerv(GLES30.GL_CURRENT_PROGRAM,bound,0);
        int expected=specialized?scene.status().getJSONObject("batch").getJSONObject("specialized").getInt("last_draw_program_id")
                :scene.individualProgramIdForVerification(count);
        if(bound[0]!=expected||expected==0)throw new IllegalStateException((specialized?"Candidate":"Individual reference")+" program binding mismatch");
    }
    private static void read(int texture,int layer,int fbo,ByteBuffer pixels){
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo);GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,texture,0,layer);complete();
        pixels.clear();GLES30.glReadPixels(0,0,W,H,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,pixels);pixels.position(0);checkGl("layer readback");
    }
    private static void complete(){if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Specialized framebuffer incomplete");}
    private static void checkGl(String label){int e=GLES30.glGetError();if(e!=GLES30.GL_NO_ERROR)throw new IllegalStateException(label+" GL error "+e);}
    private static void cancel(BooleanSupplier cancelled){if(cancelled.getAsBoolean())throw new CancellationException("Specialized diagnostic cancelled");}
}
