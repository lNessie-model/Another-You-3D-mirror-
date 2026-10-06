package com.mirror.bench;

import android.content.res.AssetManager;
import android.opengl.GLES30;
import android.opengl.Matrix;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Blocking 16-layer coverage estimate, not cache qualification, performance measurement or optical QA. */
final class AvatarMaterialCoverageCheck {
    private static final int W=400,H=640,VIEWS=16;
    private static final float ASPECT=1200f/1920f;
    private static final String MODEL="9381f452c53098314f97e1a1799ec55d2be37878e66bf205c7a26958afcef531";
    private AvatarMaterialCoverageCheck(){}
    static JSONObject run(AssetManager assets,File files,SceneViewSettings saved,
            BooleanSupplier cancelled,Consumer<JSONObject> progress)throws Exception {
        AvatarGpuScene scene=null;AvatarUvMaskEstimate mask=null;
        int[] textures=new int[3],fbos=new int[2];
        JSONObject out=new JSONObject().put("running",true).put("passed",false).put("performance_evidence",false)
                .put("material_cache_qualified",false).put("estimated_mask_hits_only",true)
                .put("model_sha256",MODEL).put("views",VIEWS).put("view_width",W).put("view_height",H)
                .put("physical_aspect",ASPECT).put("same_uploaded_pose_per_reference_probe_pair",true).put("dithering_enabled",false)
                .put("pose_source","Deterministic headSmoke canonical fixtures through current mirror/expression mapping; no live camera")
                .put("comparison_scope","All original opaque reference alpha pixels versus diagnostic IDs; reference RGB is not changed or compared to IDs")
                .put("lod_scope","floor(max-length derivative footprint at2048); estimate, not a queried sampler hardware LOD")
                .put("limitations","R8 mask is finite UV-center sampling with an example halo, not conservative geometry proof; material shading and screen/UV filtering are not interchangeable; no cache exists yet");
        JSONArray rows=new JSONArray();out.put("fixtures",rows);
        try {
            String extensions=GLES30.glGetString(GLES30.GL_EXTENSIONS);
            int[] max=new int[1];GLES30.glGetIntegerv(0x9631,max,0);
            if(extensions==null||!(" "+extensions+" ").contains(" GL_OVR_multiview2 ")||max[0]<4)
                throw new IllegalStateException("Four-view OVR diagnostic required");
            out.put("gl_renderer",GLES30.glGetString(GLES30.GL_RENDERER)).put("gl_version",GLES30.glGetString(GLES30.GL_VERSION));
            var entry=BundledAvatarCatalog.find(BundledAvatarCatalog.read(assets),"geralt");
            if(!entry.modelSha256.equals(MODEL))throw new IllegalStateException("Estimate belongs to a different installed Geralt");
            scene=AvatarGpuScene.bundled(assets,entry.directory,entry.modelSha256,entry.manifestSha256,true,false,AvatarGpuScene.DrawMode.BATCHED);
            out.put("manifest_sha256",entry.manifestSha256).put("items",scene.materialCoverageEntries());
            mask=new AvatarUvMaskEstimate(files,MODEL);out.put("mask_metadata",mask.metadata);
            GLES30.glGenTextures(3,textures,0);GLES30.glGenFramebuffers(2,fbos,0);
            for(int i=0;i<2;i++) { GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,textures[i]);
                GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY,1,GLES30.GL_RGBA8,W,H,VIEWS); }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,textures[2]);
            GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY,1,GLES30.GL_DEPTH_COMPONENT16,W,H,VIEWS);
            GLES30.glDisable(GLES30.GL_DITHER);GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
            GLES30.glColorMask(true,true,true,true);GLES30.glDepthMask(true);GLES30.glDepthFunc(GLES30.GL_LESS);
            GLES30.glViewport(0,0,W,H);checkGl("diagnostic storage/state");
            ByteBuffer reference=ByteBuffer.allocateDirect(W*H*4),probe=ByteBuffer.allocateDirect(W*H*4);
            int count=out.getJSONArray("items").length();AvatarMaterialCoverageStats total=new AvatarMaterialCoverageStats(count,0);
            float[] vp=new float[64],view=new float[16],projection=new float[16];
            float[] raw=new float[52],angles=new float[3],mapped=new float[52],mappedAngles=new float[3];
            SceneViewSettings[] settings={saved,SceneViewSettings.DEFAULT};String[] labels={"saved_scene","default_scene"};
            var fixtures=AvatarPoseFixtures.headSmoke();out.put("expected_fixtures",fixtures.size()*settings.length);
            for(int s=0;s<settings.length;s++) {
                SceneViewSettings current=settings[s];scene.setSceneView(current);
                for(var pose:fixtures) {
                    cancel(cancelled);pose.copyWeights(raw);pose.copyAngles(angles);
                    FacePlayback.apply(raw,angles,current.mirrorMotion,current.expressionGain,mapped,mappedAngles);
                    long before=scene.status().getLong("morph_updates");scene.prepare(mapped,mappedAngles);
                    if(scene.status().getLong("morph_updates")!=before+1)throw new IllegalStateException("Expected one shared uploaded pose");
                    for(int pass=0;pass<2;pass++) for(int start=0;start<VIEWS;start+=4) {
                        cancel(cancelled);GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbos[0]);
                        MultiviewGl.attach(GLES30.GL_COLOR_ATTACHMENT0,textures[pass],start,4);
                        MultiviewGl.attach(GLES30.GL_DEPTH_ATTACHMENT,textures[2],start,4);complete();
                        GLES30.glViewport(0,0,W,H);GLES30.glClearColor(0,0,0,0);
                        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);
                        for(int r=0;r<4;r++) {
                            float eye=current.eyeAt(start+r,VIEWS),near=.1f,half=.052f,shift=current.frustumShift(eye);
                            Matrix.setLookAtM(view,0,eye,0,3,eye,0,0,0,1,0);
                            Matrix.frustumM(projection,0,-half*ASPECT+shift,half*ASPECT+shift,-half,half,near,10);
                            Matrix.multiplyMM(vp,r*16,projection,0,view,0);
                        }
                        if(pass==0)scene.draw(vp,4,ASPECT);else scene.drawMaterialCoverage(vp,4,ASPECT,mask.texture);
                        GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER,1,new int[]{GLES30.GL_DEPTH_ATTACHMENT},0);
                    }
                    AvatarMaterialCoverageStats fixture=new AvatarMaterialCoverageStats(count,0);JSONArray layers=new JSONArray();
                    for(int layer=0;layer<VIEWS;layer++) {
                        cancel(cancelled);read(textures[0],layer,fbos[1],reference);read(textures[1],layer,fbos[1],probe);
                        AvatarMaterialCoverageStats item=new AvatarMaterialCoverageStats(count,0);item.add(reference,probe,W*H);
                        fixture.add(reference,probe,W*H);total.add(reference,probe,W*H);
                        layers.put(stats(item).put("view",layer).put("reference_rgba_sha256",hash(reference))
                                .put("diagnostic_rgba_sha256",hash(probe)).put("alpha_mismatches",0));
                    }
                    if(s==1&&(fixture.foreground<1000||fixture.visibleByItem[0]==0))
                        throw new IllegalStateException("Default reference must contain visible primary geometry");
                    rows.put(stats(fixture).put("scene",labels[s]).put("scene_settings",new JSONObject(current.toMap()))
                            .put("pose",pose.name()).put("layers",layers).put("alpha_mismatches",0));
                    checkGl("coverage fixture");progress.accept(out);
                }
            }
            cancel(cancelled);
            if(rows.length()!=fixtures.size()*settings.length||total.pixels!=(long)rows.length()*VIEWS*W*H)
                throw new IllegalStateException("Incomplete coverage accounting");
            out.put("totals",stats(total)).put("completed_fixtures",rows.length())
                    .put("completed_layer_pairs",rows.length()*VIEWS).put("alpha_mismatches",0)
                    .put("original_avatar_status",scene.status()).put("running",false).put("passed",true);
            return out;
        } finally {
            if(scene!=null)scene.dispose();if(mask!=null)mask.close();
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);GLES30.glDeleteFramebuffers(2,fbos,0);GLES30.glDeleteTextures(3,textures,0);
        }
    }
    private static JSONObject stats(AvatarMaterialCoverageStats s)throws Exception {
        JSONArray items=new JSONArray(),lod=new JSONArray();for(long n:s.visibleByItem)items.put(n);for(long n:s.primaryLodBins)lod.put(n);
        return new JSONObject().put("pixels",s.pixels).put("foreground",s.foreground).put("background",s.background)
                .put("visible_by_item",items).put("primary_lod_floor_bins",lod).put("primary_estimated_eligible",s.primaryEstimatedEligible)
                .put("estimated_eligible_fraction_of_foreground",s.foreground==0?0:s.primaryEstimatedEligible/(double)s.foreground)
                .put("estimated_eligible_fraction_of_primary",s.visibleByItem[0]==0?0:s.primaryEstimatedEligible/(double)s.visibleByItem[0]);
    }
    private static void read(int texture,int layer,int fbo,ByteBuffer destination) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo);
        GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,texture,0,layer);complete();
        destination.clear();GLES30.glReadPixels(0,0,W,H,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,destination);destination.position(0);
        checkGl("coverage layer readback");
    }
    private static void complete(){if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Coverage framebuffer incomplete");}
    private static String hash(ByteBuffer pixels)throws Exception {
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");digest.update(pixels.duplicate());
        StringBuilder out=new StringBuilder(64);for(byte b:digest.digest())out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return out.toString();
    }
    private static void checkGl(String label){int e=GLES30.glGetError();if(e!=GLES30.GL_NO_ERROR)throw new IllegalStateException(label+" GL error "+e);}
    private static void cancel(BooleanSupplier cancelled){if(cancelled.getAsBoolean())throw new CancellationException("Coverage diagnostic cancelled");}
}
