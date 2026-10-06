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
        Throwable primary=null;AvatarGpuScene scene=null;int[] textures=new int[3],fbos=new int[2];
        JSONArray fixtures=new JSONArray();var poses=AvatarPoseFixtures.regression();
        JSONObject out=new JSONObject().put("running",true).put("passed",false).put("performance_evidence",false)
                .put("scope","Original batch OVR4 versus per-entry specialized OVR4, same prepared pose, original textures and shared VBO/IBO; all 16 off-axis layers before interlacing")
                .put("views",VIEWS).put("view_width",W).put("view_height",H).put("expected_fixtures",poses.size())
                .put("expected_layer_comparisons",poses.size()*VIEWS).put("max_rgb_error_allowed",1).put("rmse_allowed",.1)
                .put("alpha_error_allowed",0).put("scene_settings",new JSONObject(saved.toMap())).put("fixtures",fixtures)
                .put("artwork_validated",false).put("all_possible_poses_validated",false);
        try {
            String extensions=GLES30.glGetString(GLES30.GL_EXTENSIONS);int[] max=new int[1];GLES30.glGetIntegerv(0x9631,max,0);
            if(extensions==null||!(" "+extensions+" ").contains(" GL_OVR_multiview2 ")||max[0]<4)throw new IllegalStateException("OVR4 required");
            out.put("gl_renderer",GLES30.glGetString(GLES30.GL_RENDERER)).put("gl_version",GLES30.glGetString(GLES30.GL_VERSION));
            var entry=BundledAvatarCatalog.find(BundledAvatarCatalog.read(assets),"geralt");
            scene=AvatarGpuScene.bundled(assets,entry.directory,entry.modelSha256,entry.manifestSha256,true,false,AvatarGpuScene.DrawMode.BATCHED);
            scene.setSceneView(saved);scene.beginSpecializedBatch();out.put("model_sha256",entry.modelSha256).put("manifest_sha256",entry.manifestSha256);
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
                if(scene.status().getLong("morph_updates")!=beforePose+1)throw new IllegalStateException("Expected one shared pose upload");
                var before=scene.status().getJSONObject("batch");
                for(int pass=0;pass<2;pass++){
                    scene.selectSpecializedBatch(pass==1);
                    for(int start=0;start<VIEWS;start+=4){
                        cancel(cancelled);GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbos[0]);
                        MultiviewGl.attach(GLES30.GL_COLOR_ATTACHMENT0,textures[pass],start,4);MultiviewGl.attach(GLES30.GL_DEPTH_ATTACHMENT,textures[2],start,4);complete();
                        GLES30.glViewport(0,0,W,H);GLES30.glClearColor(.035f,.045f,.06f,1);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);
                        for(int r=0;r<4;r++){
                            float eye=saved.eyeAt(start+r,VIEWS),shift=saved.frustumShift(eye);
                            Matrix.setLookAtM(view,0,eye,0,3,eye,0,0,0,1,0);Matrix.frustumM(projection,0,-.052f*ASPECT+shift,.052f*ASPECT+shift,-.052f,.052f,.1f,10);
                            Matrix.multiplyMM(vp,r*16,projection,0,view,0);
                        }
                        scene.draw(vp,4,ASPECT);
                        if(pass==1){
                            int[] bound=new int[1];GLES30.glGetIntegerv(GLES30.GL_CURRENT_PROGRAM,bound,0);
                            int expected=scene.status().getJSONObject("batch").getJSONObject("specialized").getInt("last_draw_program_id");
                            if(bound[0]!=expected||expected==0)throw new IllegalStateException("Candidate program binding mismatch");
                        }
                        GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER,1,new int[]{GLES30.GL_DEPTH_ATTACHMENT},0);checkGl("paired draw");
                    }
                }
                var after=scene.status().getJSONObject("batch");
                long originalCalls=after.getLong("reference_draw_calls")-before.getLong("reference_draw_calls");
                long specializedCalls=after.getJSONObject("specialized").getLong("draw_calls")-before.getJSONObject("specialized").getLong("draw_calls");
                if(originalCalls!=4||specializedCalls!=28)throw new IllegalStateException("Unexpected submitted draw counts");
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
                        .put("original_distinct_views",originalHashes.size()).put("candidate_distinct_views",candidateHashes.size()));
                out.put("completed_fixtures",fixtures.length()).put("completed_layer_comparisons",layers).put("observed_max_rgb_error",observedMax)
                        .put("observed_max_layer_rmse",observedRmse).put("alpha_mismatches",alpha).put("rgb_mismatches",rgb);progress.accept(out);
            }
            cancel(cancelled);out.put("scene_status",scene.status());scene.endSpecializedBatch();out.put("after_scope_status",scene.status());
            out.put("running",false).put("passed",passed&&layers==poses.size()*VIEWS);return out;
        }catch(Exception|Error failure){primary=failure;throw failure;}finally{
            ResourceCleanup cleanup=new ResourceCleanup(null);
            if(scene!=null){AvatarGpuScene owned=scene;cleanup.close("specialized scene",owned::dispose);}
            cleanup.close("diagnostic GL targets",()->{GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);GLES30.glDeleteFramebuffers(2,fbos,0);GLES30.glDeleteTextures(3,textures,0);checkGl("diagnostic cleanup");});
            if(cleanup.failure()!=null){if(primary!=null)primary.addSuppressed(cleanup.failure());else throw new IllegalStateException("Specialized diagnostic cleanup failed",cleanup.failure());}
        }
    }
    private static void read(int texture,int layer,int fbo,ByteBuffer pixels){
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo);GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,texture,0,layer);complete();
        pixels.clear();GLES30.glReadPixels(0,0,W,H,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,pixels);pixels.position(0);checkGl("layer readback");
    }
    private static void complete(){if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Specialized framebuffer incomplete");}
    private static void checkGl(String label){int e=GLES30.glGetError();if(e!=GLES30.GL_NO_ERROR)throw new IllegalStateException(label+" GL error "+e);}
    private static void cancel(BooleanSupplier cancelled){if(cancelled.getAsBoolean())throw new CancellationException("Specialized diagnostic cancelled");}
}