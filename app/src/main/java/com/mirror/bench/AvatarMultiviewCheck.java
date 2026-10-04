package com.mirror.bench;

import android.content.res.AssetManager;
import android.opengl.GLES30;
import android.opengl.Matrix;
import java.nio.ByteBuffer;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Explicit diagnostic only. Call on a current GLES3 GL thread, outside active queries/transform
 * feedback. Owns a synchronous scene and private GL objects; it never touches the runtime scene.
 * Serial and OVR renders share ONE prepared VBO/world pose. Every global array layer is read back.
 * Does not test interlacing, asynchronous scheduling, physical optics, artwork, or FPS.
 */
public final class AvatarMultiviewCheck {
    private AvatarMultiviewCheck(){}
    public static JSONObject run(AssetManager assets,int width,int height,int views,float physicalAspect,
                                 BooleanSupplier cancelled)throws Exception {
        return runComparison(assets,width,height,views,physicalAspect,cancelled,false);
    }
    /** Explicit sixteen-view profile: strict RGBA, independent serial shader versus four-view OVR. */
    public static JSONObject runSixteen(AssetManager assets,BooleanSupplier cancelled)throws Exception {
        return runSixteen(assets,400,720,cancelled);
    }
    public static JSONObject runSixteen(AssetManager assets,int width,int height,BooleanSupplier cancelled)throws Exception {
        if(width!=400||(height!=640&&height!=720))throw new IllegalArgumentException("Strict sixteen-view diagnostic requires 400x640 or 400x720");
        return runComparison(assets,width,height,16,1200f/1920f,cancelled,false,false,false,0,0,true);
    }
    /** Fixed app-private head diagnostic; no stored-avatar selection or performance claims. */
    static JSONObject runPrivateHead(java.io.File files,BooleanSupplier cancelled,boolean batch)throws Exception {
        if(files==null)throw new IllegalArgumentException("App-private files directory required");
        return runComparison(null,400,640,16,1200f/1920f,cancelled,batch,false,false,0,0,!batch,files);
    }
    /** Full pose gate: ordinary OVR4 versus cached OVR4, independently read every global color layer. */
    static JSONObject runPrivateBackground(java.io.File files,int views,BooleanSupplier cancelled)throws Exception {
        backgroundProfile(views);
        if(files==null)throw new IllegalArgumentException("App-private files directory required");
        return runComparison(null,400,640,views,1200f/1920f,cancelled,false,false,false,0,0,false,files,true);
    }
    static JSONObject backgroundProfile(int views)throws Exception {
        if(views!=16&&views!=20)throw new IllegalArgumentException("Background qualification requires 16 or 20 views");
        return new JSONObject().put("verification_profile","private_head_background_cache_69poses")
                .put("views",views).put("view_width",400).put("view_height",640).put("expected_fixtures",69)
                .put("expected_layer_comparisons",69*views).put("max_rgb_error_allowed",0).put("rmse_allowed",0)
                .put("alpha_error_allowed",0).put("require_distinct_view_hashes",true).put("performance_evidence",false);
    }
    static JSONObject sixteenProfile()throws Exception {
        return new JSONObject().put("verification_profile","avatar_16views_strict").put("views",16)
                .put("expected_layer_comparisons",69*16).put("reference_views_per_draw",1)
                .put("candidate_views_per_draw",4).put("candidate_view_groups",4)
                .put("max_rgb_error_allowed",0).put("rmse_allowed",0).put("alpha_error_allowed",0)
                .put("physical_width",1200).put("physical_height",1920);
    }
    static boolean strictPixelsMatch(AvatarPixelComparison.Stats stats) {
        return stats.passed()&&stats.rgbMismatches==0&&stats.alphaMismatches==0;
    }
    static JSONObject runComparison(AssetManager assets,int width,int height,int views,float physicalAspect,
                                    BooleanSupplier cancelled,boolean batchComparison)throws Exception {
        return runComparison(assets,width,height,views,physicalAspect,cancelled,batchComparison,false);
    }
    static JSONObject runComparison(AssetManager assets,int width,int height,int views,float physicalAspect,
                                    BooleanSupplier cancelled,boolean batchComparison,boolean persistentComparison)throws Exception {
        return runComparison(assets,width,height,views,physicalAspect,cancelled,batchComparison,persistentComparison,false,0,0,false);
    }
    static JSONObject runCameraComparison(AssetManager assets,int width,int height,int views,int physicalWidth,int physicalHeight,
                                         BooleanSupplier cancelled)throws Exception {
        if(physicalWidth<=0||physicalHeight<=0)throw new IllegalArgumentException("Positive physical dimensions required");
        return runComparison(assets,width,height,views,(float)physicalWidth/physicalHeight,cancelled,false,false,true,physicalWidth,physicalHeight,false);
    }
    private static JSONObject runComparison(AssetManager assets,int width,int height,int views,float physicalAspect,
                                    BooleanSupplier cancelled,boolean batchComparison,boolean persistentComparison,boolean cameraComparison,
                                    int physicalWidth,int physicalHeight,boolean strictSixteen)throws Exception {
        return runComparison(assets,width,height,views,physicalAspect,cancelled,batchComparison,persistentComparison,cameraComparison,physicalWidth,physicalHeight,strictSixteen,null);
    }
    private static JSONObject runComparison(AssetManager assets,int width,int height,int views,float physicalAspect,
                                    BooleanSupplier cancelled,boolean batchComparison,boolean persistentComparison,boolean cameraComparison,
                                    int physicalWidth,int physicalHeight,boolean strictSixteen,java.io.File privateHeadFiles)throws Exception {
        return runComparison(assets,width,height,views,physicalAspect,cancelled,batchComparison,persistentComparison,cameraComparison,
                physicalWidth,physicalHeight,strictSixteen,privateHeadFiles,false);
    }
    private static JSONObject runComparison(AssetManager assets,int width,int height,int views,float physicalAspect,
                                    BooleanSupplier cancelled,boolean batchComparison,boolean persistentComparison,boolean cameraComparison,
                                    int physicalWidth,int physicalHeight,boolean strictSixteen,java.io.File privateHeadFiles,boolean backgroundComparison)throws Exception {
        if(batchComparison&&persistentComparison)throw new IllegalArgumentException("Select one comparison");
        boolean pairedOvr=persistentComparison||cameraComparison||backgroundComparison;
        if((assets==null&&privateHeadFiles==null)||width<64||height<64||width>2048||height>2048||views<4||views>32||views%4!=0
                ||(long)width*height*views>8_388_608||!Float.isFinite(physicalAspect)||physicalAspect<=0)
            throw new IllegalArgumentException("Require 64..2048 view dimensions, 4..32 views divisible by four, <=8M layer pixels, and positive physical aspect");
        long started=System.nanoTime();JSONArray poseReports=new JSONArray();
        JSONObject report=new JSONObject().put("passed",false).put("completed",false).put("cancelled",false)
                .put("scope",backgroundComparison?"actual imported head and trailing unlit props, same prepared pose and persistent OVR4 targets; ordinary complete geometry versus dynamic geometry plus cached RGBA8/DEPTH16 restore; every global layer before interlacing":cameraComparison?"actual avatar, same prepared VBO/world pose/shader, identical persistent OVR4 paths; original per-group camera matrices vs cached camera matrices; all layers before interlacing":persistentComparison?"actual avatar, same prepared VBO/world pose/shader, legacy OVR4 vs persistent OVR4 framebuffer groups; all layers before interlacing":batchComparison?"actual avatar, one CPU pose uploaded to independent original and packed buffers; original single-view vs batched OVR4, all layers before interlacing":
                        "actual avatar, synchronous shared CPU pose, serial single-view vs OVR4; every off-axis array layer before interlacing")
                .put("comparison_backend",backgroundComparison?"ordinary_ovr4_vs_cached_background_ovr4":cameraComparison?"per_group_camera_vp_vs_cached_camera_vp":persistentComparison?"legacy_ovr4_vs_persistent_ovr4":batchComparison?"individual_serial_vs_batched_ovr4":"individual_serial_vs_individual_ovr4")
                .put("input_schema",BlendshapeSchema.ID).put("view_width",width).put("view_height",height)
                .put("views",views).put("physical_aspect",physicalAspect).put("max_rgb_error_allowed",pairedOvr?0:1).put("rmse_allowed",pairedOvr?0:.1)
                .put("alpha_error_allowed",0)
                .put("poses",poseReports).put("artwork_validated",false).put("performance_evidence",false);
        if(strictSixteen){JSONObject profile=sixteenProfile();for(var keys=profile.keys();keys.hasNext();){String key=keys.next();report.put(key,profile.get(key));}}
        if(backgroundComparison){JSONObject profile=backgroundProfile(views);for(var keys=profile.keys();keys.hasNext();){String key=keys.next();report.put(key,profile.get(key));}}
        SavedState saved=null;AvatarGpuScene scene=null;
        AvatarBackgroundCache background=null;float[] backgroundState=null;
        PersistentMultiviewFbos persistent=null,referencePersistent=null;
        AvatarCameraProjectionCache cameraCache=cameraComparison?new AvatarCameraProjectionCache():null;
        int[] vao={0},fbos=new int[pairedOvr?4:3],textures=new int[pairedOvr?5:3],depth={0};
        int completed=0,comparisons=0,prepareCalls=0,changedCenter=0;boolean pass=true;
        try {
            checkCancelled(cancelled);checkGl("entry requires a clean GL error state");
            if(cameraComparison){
                JSONObject matrixGate=AvatarCameraProjectionCheck.matrixGate();report.put("matrix_gate",matrixGate)
                        .put("physical_width",physicalWidth).put("physical_height",physicalHeight);
                if(!matrixGate.getBoolean("passed"))throw new IllegalStateException("Camera matrix bitwise gate failed");
                cameraCache.prepare(views,physicalWidth,physicalHeight);
            }
            String extensions=GLES30.glGetString(GLES30.GL_EXTENSIONS);
            if(extensions==null||!(" "+extensions+" ").contains(" GL_OVR_multiview2 "))throw new IllegalStateException("GL_OVR_multiview2 unavailable");
            int maxViews=integer(0x9631);if(maxViews<4)throw new IllegalStateException("Four OVR views unsupported");
            if(integer(GLES30.GL_TRANSFORM_FEEDBACK_ACTIVE)!=0)throw new IllegalStateException("Diagnostic cannot run during active transform feedback");
            if(Math.max(width,height)>integer(GLES30.GL_MAX_TEXTURE_SIZE)||views>integer(GLES30.GL_MAX_ARRAY_TEXTURE_LAYERS))
                throw new IllegalStateException("Diagnostic textures exceed device limits");
            report.put("gl_vendor",GLES30.glGetString(GLES30.GL_VENDOR)).put("gl_renderer",GLES30.glGetString(GLES30.GL_RENDERER))
                    .put("gl_version",GLES30.glGetString(GLES30.GL_VERSION)).put("max_ovr_views",maxViews);
            saved=new SavedState();
            GLES30.glGenVertexArrays(1,vao,0);GLES30.glBindVertexArray(vao[0]);
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER,0);GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT,1);
            GLES30.glPixelStorei(GLES30.GL_PACK_ROW_LENGTH,0);GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_ROWS,0);GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_PIXELS,0);
            for(int cap:DISABLED)GLES30.glDisable(cap);
            GLES30.glColorMask(true,true,true,true);GLES30.glDepthMask(true);GLES30.glDepthFunc(GLES30.GL_LESS);
            GLES30.glDepthRangef(0,1);GLES30.glClearDepthf(1);GLES30.glViewport(0,0,width,height);
            // Construct after binding our VAO: constructor element-buffer bindings must not touch caller VAO state.
            AvatarGpuScene.DrawMode mode=batchComparison?AvatarGpuScene.DrawMode.VERIFY:AvatarGpuScene.DrawMode.INDIVIDUAL;
            scene=privateHeadFiles==null?AvatarGpuScene.builtin(assets,true,false,mode):AvatarGpuScene.privateHeadCheck(privateHeadFiles,true,mode);
            if(backgroundComparison){
                background=new AvatarBackgroundCache(new AvatarBackgroundGl(scene),1,width,height,views,4);
                backgroundState=new float[scene.staticBackgroundStateFloats()];
            }
            report.put("avatar",scene.status());
            GLES30.glGenFramebuffers(fbos.length,fbos,0);GLES30.glGenTextures(textures.length,textures,0);GLES30.glGenRenderbuffers(1,depth,0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,textures[0]);GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D,1,GLES30.GL_RGBA8,width,height);
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER,depth[0]);GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER,GLES30.GL_DEPTH_COMPONENT16,width,height);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbos[0]);
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,textures[0],0);
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER,GLES30.GL_DEPTH_ATTACHMENT,GLES30.GL_RENDERBUFFER,depth[0]);complete("serial");
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,textures[1]);GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY,1,GLES30.GL_RGBA8,width,height,views);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,textures[2]);GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY,1,GLES30.GL_DEPTH_COMPONENT16,width,height,views);
            if(pairedOvr) {
                // Diagnostic-only independent baseline storage. Runtime candidate reuses its original arrays.
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,textures[3]);GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY,1,GLES30.GL_RGBA8,width,height,views);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,textures[4]);GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY,1,GLES30.GL_DEPTH_COMPONENT16,width,height,views);
                persistent=PersistentMultiviewFbos.create(PersistentMultiviewGl.INSTANCE,1,views,textures[1],textures[2]);
                if(cameraComparison||backgroundComparison)referencePersistent=PersistentMultiviewFbos.create(PersistentMultiviewGl.INSTANCE,1,views,textures[3],textures[4]);
                report.put("persistent_groups",persistent.groups()).put("depth_clear_and_invalidate_both_paths",true)
                        .put("reference_label",backgroundComparison?"ordinary complete scene OVR4; serial_* keys name the reference only":cameraComparison?"per-group Matrix operations, persistent OVR4; serial_* keys name the reference only":"legacy_ovr4; legacy report serial_* keys name the reference only");
                if(cameraComparison||backgroundComparison)report.put("reference_persistent_groups",referencePersistent.groups());
            }
            checkGl("private diagnostic objects");
            int bytes=width*height*4;
            ByteBuffer serial=ByteBuffer.allocateDirect(bytes),candidate=ByteBuffer.allocateDirect(bytes);
            ByteBuffer firstSerial=ByteBuffer.allocateDirect(bytes),firstCandidate=ByteBuffer.allocateDirect(bytes);
            float[] matrices=viewMatrices(views,physicalAspect),four=new float[64],one=new float[16];
            if(strictSixteen){JSONObject gate=sixteenMatrixGate(matrices);report.put("matrix_gate",gate);
                if(!gate.getBoolean("passed"))throw new IllegalStateException("Sixteen-view matrix gate failed");}
            float[] cameraView=cameraComparison?new float[16]:null,cameraProjection=cameraComparison?new float[16]:null,cameraMvp=cameraComparison?new float[16]:null;
            float[] coefficients=new float[52],angles=new float[3];String neutralCenter=null;
            var fixtures=privateHeadFiles==null||backgroundComparison?AvatarPoseFixtures.regression():AvatarPoseFixtures.headSmoke();
            if(backgroundComparison&&fixtures.size()!=69)throw new IllegalStateException("Background gate requires all 69 regression poses");
            int isolated=0;for(var fixture:fixtures)if(fixture.sourceIndex()>=0)isolated++;
            report.put("expected_fixtures",fixtures.size()).put("isolated_source_fixtures",isolated)
                    .put("fixture_scope",backgroundComparison?"private_head_69_pose_background_regression":privateHeadFiles==null?"full_69_pose_regression":"private_head_9_pose_smoke")
                    .put("expected_layer_comparisons",fixtures.size()*views);
            for(var pose:fixtures) {
                checkCancelled(cancelled);long poseStarted=System.nanoTime();
                pose.copyWeights(coefficients);pose.copyAngles(angles);
                JSONArray perView=new JSONArray();JSONObject poseReport=new JSONObject().put("name",pose.name()).put("source_index",pose.sourceIndex())
                        .put("weights",new JSONArray(coefficients)).put("head_angles",new JSONArray(angles)).put("views",perView).put("passed",false);
                poseReports.put(poseReport);
                scene.prepare(coefficients,angles);prepareCalls++;checkGl("prepare "+pose.name());
                if(backgroundComparison){
                    scene.copyStaticBackgroundState(physicalAspect,backgroundState);
                    background.prepare(1,scene,scene.modelSha256(),physicalAspect,backgroundState,matrices);
                }
                boolean posePass=true;String centerHash=null;
                java.util.Set<String> serialHashes=strictSixteen||backgroundComparison?new java.util.HashSet<>():null;
                java.util.Set<String> candidateHashes=strictSixteen||backgroundComparison?new java.util.HashSet<>():null;
                for(int base=0;base<views;base+=4) {
                    checkCancelled(cancelled);
                    // Real global base indices 0/4/... are exercised, not a reused four-layer scratch texture.
                    System.arraycopy(matrices,base*16,four,0,64);
                    if(pairedOvr) {
                        if(cameraComparison){
                            AvatarCameraProjectionCheck.originalGroup(views,physicalWidth,physicalHeight,base,4,four,cameraView,cameraProjection,cameraMvp);
                            referencePersistent.bind(base,1);
                        } else if(backgroundComparison){
                            referencePersistent.bind(base,1);
                        } else {
                            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbos[3]);
                            MultiviewGl.attach(GLES30.GL_COLOR_ATTACHMENT0,textures[3],base,4);
                            MultiviewGl.attach(GLES30.GL_DEPTH_ATTACHMENT,textures[4],base,4);complete("legacy OVR batch "+base);
                        }
                        clear();scene.draw(four,4,physicalAspect);
                        GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER,1,DEPTH_ATTACHMENT,0);checkGl("legacy OVR draw "+base);
                        persistent.bind(base,1);
                        if(cameraComparison)cameraCache.copyGroup(views,physicalWidth,physicalHeight,base,4,four);
                    } else {
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbos[1]);
                        MultiviewGl.attach(GLES30.GL_COLOR_ATTACHMENT0,textures[1],base,4);
                        MultiviewGl.attach(GLES30.GL_DEPTH_ATTACHMENT,textures[2],base,4);complete("OVR batch "+base);
                    }
                    clear();
                    if(backgroundComparison){scene.drawDynamic(four,4,physicalAspect);background.restoreGroup(1,base);}
                    else if(batchComparison)scene.drawBatched(four,4,physicalAspect);else scene.draw(four,4,physicalAspect);
                    if(pairedOvr)GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER,1,DEPTH_ATTACHMENT,0);
                    checkGl("OVR draw "+base);
                    for(int relative=0;relative<4;relative++) {
                        int view=base+relative;checkCancelled(cancelled);
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbos[0]);
                        if(pairedOvr) {
                            GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,textures[3],0,view);complete("legacy read layer "+view);
                        } else {
                            clear();System.arraycopy(matrices,view*16,one,0,16);scene.draw(one,1,physicalAspect);checkGl("serial draw "+view);
                        }
                        serial.clear();GLES30.glReadPixels(0,0,width,height,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,serial);checkGl("serial read "+view);
                        // OVR forbids ReadPixels from a multi-view attachment. Attach exactly one layer to a separate FBO.
                        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbos[2]);
                        GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,textures[1],0,view);complete("read layer "+view);
                        candidate.clear();GLES30.glReadPixels(0,0,width,height,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,candidate);checkGl("layer read "+view);
                        AvatarPixelComparison.Stats stats=AvatarPixelComparison.compare(serial,candidate,width,height);comparisons++;
                        boolean pixelsPass=strictSixteen||backgroundComparison?strictPixelsMatch(stats):cameraComparison?AvatarCameraProjectionCheck.pixelsMatch(stats):persistentComparison?AvatarPersistentFboCheck.pixelsMatch(stats):stats.passed();
                        if(strictSixteen||backgroundComparison){serialHashes.add(stats.serialSha256);candidateHashes.add(stats.candidateSha256);}
                        perView.put(toJson(stats).put("passed",pixelsPass).put("global_view",view).put("ovr_base",base).put("ovr_relative",relative)
                                .put("eye_x",(view/(float)(views-1)-.5f)*.4f));
                        posePass&=pixelsPass;
                        if(view==0){copyPixels(serial,firstSerial);copyPixels(candidate,firstCandidate);}
                        if(view==views/2)centerHash=stats.serialSha256;
                    }
                }
                var serialVariation=AvatarPixelComparison.compare(firstSerial,serial,width,height);
                var candidateVariation=AvatarPixelComparison.compare(firstCandidate,candidate,width,height);
                boolean independentViews=serialVariation.rgbMismatches>0&&candidateVariation.rgbMismatches>0;
                if(strictSixteen||backgroundComparison){independentViews&=serialHashes.size()==views&&candidateHashes.size()==views;
                    poseReport.put("serial_distinct_view_hashes",serialHashes.size()).put("ovr_distinct_view_hashes",candidateHashes.size());}
                posePass&=independentViews;
                if(neutralCenter==null)neutralCenter=centerHash;
                boolean differs=!neutralCenter.equals(centerHash);if(differs)changedCenter++;
                poseReport.put("serial_cross_view_rgb_mismatches",serialVariation.rgbMismatches)
                        .put("ovr_cross_view_rgb_mismatches",candidateVariation.rgbMismatches)
                        .put("independent_views_visible",independentViews).put("center_differs_from_neutral",differs)
                        .put("diagnostic_elapsed_ms",(System.nanoTime()-poseStarted)/1e6).put("passed",posePass);
                pass&=posePass;completed++;
            }
            checkCancelled(cancelled);checkGl("completed avatar multiview check");
            // Head fixtures alone must change output; otherwise a fixed neutral pose could falsely pass both programs.
            pass&=changedCenter>0;
            if(backgroundComparison){
                boolean reused=background.builds()==1;pass&=reused&&completed==69&&comparisons==69*views;
                report.put("background_cache_builds",background.builds()).put("background_cache_reused_across_poses",reused)
                        .put("background_cache_bytes",background.storageBytes());
            }
            report.put("completed",true).put("passed",pass).put("fixtures_with_changed_center",changedCenter)
                    .put("pose_variation_visible",changedCenter>0).put("avatar",scene.status());
        } catch(Exception|LinkageError failure) {
            report.put("passed",false).put("error",failure.toString()).put("cancelled",failure instanceof CancellationException);
        } finally {
            try{if(background!=null)background.close(1);}
            catch(Exception|LinkageError cleanup){report.put("passed",false).put("background_cleanup_error",cleanup.toString());}
            try {
                if(scene!=null)scene.dispose();
                if(persistent!=null)persistent.close(1);
                if(referencePersistent!=null)referencePersistent.close(1);
                GLES30.glDeleteRenderbuffers(1,depth,0);GLES30.glDeleteTextures(textures.length,textures,0);
                GLES30.glDeleteFramebuffers(fbos.length,fbos,0);GLES30.glDeleteVertexArrays(1,vao,0);
                if(saved!=null){saved.restore();checkGl("diagnostic cleanup/state restore");}
            } catch(Exception|LinkageError cleanup) {report.put("passed",false).put("cleanup_error",cleanup.toString());}
            report.put("completed_fixtures",completed).put("layer_comparisons",comparisons).put("prepare_calls",prepareCalls)
                    .put("diagnostic_elapsed_ms",(System.nanoTime()-started)/1e6);
            if(backgroundComparison)report.put("background_cache_qualified",report.optBoolean("passed")&&report.optBoolean("completed"));
        }
        return report;
    }
    private static JSONObject toJson(AvatarPixelComparison.Stats s)throws Exception {
        return new JSONObject().put("passed",s.passed()).put("max_rgb_error",s.maxRgbError).put("rgb_byte_mismatches",s.rgbMismatches)
                .put("rmse",s.rmse).put("alpha_mismatches",s.alphaMismatches).put("serial_foreground",s.serialForeground)
                .put("ovr_foreground",s.candidateForeground).put("serial_nonopaque",s.serialNonOpaque).put("ovr_nonopaque",s.candidateNonOpaque)
                .put("serial_sha256",s.serialSha256).put("ovr_sha256",s.candidateSha256);
    }
    private static float[] viewMatrices(int views,float aspect) {
        float[] all=new float[views*16],view=new float[16],projection=new float[16];
        for(int i=0;i<views;i++) {
            float eye=(i/(float)(views-1)-.5f)*.4f,shift=-eye*.1f/3;
            Matrix.setLookAtM(view,0,eye,0,3,eye,0,0,0,1,0);
            Matrix.frustumM(projection,0,-.052f*aspect+shift,.052f*aspect+shift,-.052f,.052f,.1f,10);
            Matrix.multiplyMM(all,i*16,projection,0,view,0);
        }return all;
    }
    /** Validate actual matrices consumed by both shader paths against the independent renderer expressions. */
    static JSONObject sixteenMatrixGate(float[] actual)throws Exception {
        if(actual==null||actual.length!=16*16)throw new IllegalArgumentException("Sixteen view matrices required");
        float[] expected=new float[64],view=new float[16],projection=new float[16],mvp=new float[16];
        int mismatches=0;boolean monotonic=true;java.util.Set<String> unique=new java.util.HashSet<>();
        for(int base=0;base<16;base+=4){
            AvatarCameraProjectionCheck.originalGroup(16,1200,1920,base,4,expected,view,projection,mvp);
            for(int i=0;i<64;i++)if(!Float.isFinite(actual[base*16+i])
                    ||Float.floatToRawIntBits(actual[base*16+i])!=Float.floatToRawIntBits(expected[i]))mismatches++;
        }
        // For this parallel off-axis camera, VP[8] decreases strictly as eyeX goes from -.2 to +.2.
        for(int index=0;index<16;index++){
            unique.add(java.util.Arrays.toString(java.util.Arrays.copyOfRange(actual,index*16,index*16+16)));
            if(index>0)monotonic&=actual[index*16+8]<actual[(index-1)*16+8];
        }
        return new JSONObject().put("passed",mismatches==0&&monotonic&&unique.size()==16)
                .put("compared_float_values",256).put("bit_mismatches",mismatches).put("distinct_actual_matrices",unique.size())
                .put("actual_projection_order_monotonic",monotonic).put("eye_min",-.2f).put("eye_max",.2f)
                .put("scope","actual diagnostic VP matrices versus independent original renderer expressions for 16 evenly spaced eyes; no reused middle camera; platform Android Matrix");
    }
    private static void clear(){GLES30.glClearColor(.035f,.045f,.06f,1);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);}
    private static final int[] DEPTH_ATTACHMENT={GLES30.GL_DEPTH_ATTACHMENT};
    private static void copyPixels(ByteBuffer source,ByteBuffer target){source.clear();target.clear();target.put(source);target.flip();source.clear();}
    private static void complete(String label){if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException(label+" framebuffer incomplete");checkGl(label);}
    private static void checkCancelled(BooleanSupplier cancelled){if(cancelled!=null&&cancelled.getAsBoolean())throw new CancellationException("Avatar multiview check cancelled");}
    private static void checkGl(String operation){int code=GLES30.glGetError();if(code!=GLES30.GL_NO_ERROR)throw new IllegalStateException(operation+" GL error "+code);}
    private static int integer(int name){int[] value=new int[1];GLES30.glGetIntegerv(name,value,0);return value[0];}
    private static final int[] DISABLED={GLES30.GL_SCISSOR_TEST,GLES30.GL_BLEND,GLES30.GL_DITHER,GLES30.GL_STENCIL_TEST,
            GLES30.GL_RASTERIZER_DISCARD,GLES30.GL_SAMPLE_COVERAGE,GLES30.GL_SAMPLE_ALPHA_TO_COVERAGE,GLES30.GL_POLYGON_OFFSET_FILL};
    /** Restore every mutable context state this checker/AvatarGpuScene touches; private VAO isolates attribute pointers. */
    private static final class SavedState {
        final int drawFbo=integer(GLES30.GL_DRAW_FRAMEBUFFER_BINDING),readFbo=integer(GLES30.GL_READ_FRAMEBUFFER_BINDING);
        final int vao=integer(GLES30.GL_VERTEX_ARRAY_BINDING),arrayBuffer=integer(GLES30.GL_ARRAY_BUFFER_BINDING);
        final int packBuffer=integer(GLES30.GL_PIXEL_PACK_BUFFER_BINDING),renderbuffer=integer(GLES30.GL_RENDERBUFFER_BINDING),program=integer(GLES30.GL_CURRENT_PROGRAM);
        final int activeTexture=integer(GLES30.GL_ACTIVE_TEXTURE),texture2d,textureArray,texture1Array;
        final int depthFunc=integer(GLES30.GL_DEPTH_FUNC),depthMask=integer(GLES30.GL_DEPTH_WRITEMASK);
        final int cullFace=integer(GLES30.GL_CULL_FACE_MODE),frontFace=integer(GLES30.GL_FRONT_FACE);
        final int packAlignment=integer(GLES30.GL_PACK_ALIGNMENT),packRows=integer(GLES30.GL_PACK_ROW_LENGTH),skipRows=integer(GLES30.GL_PACK_SKIP_ROWS),skipPixels=integer(GLES30.GL_PACK_SKIP_PIXELS);
        final int[] viewport=new int[4],colorMask=new int[4];final float[] clear=new float[4],depthRange=new float[2],depthClear=new float[1],attributeColor=new float[4];
        final boolean[] enables=new boolean[DISABLED.length];final boolean depth=GLES30.glIsEnabled(GLES30.GL_DEPTH_TEST),cull=GLES30.glIsEnabled(GLES30.GL_CULL_FACE);
        SavedState() {
            GLES30.glGetIntegerv(GLES30.GL_VIEWPORT,viewport,0);GLES30.glGetIntegerv(GLES30.GL_COLOR_WRITEMASK,colorMask,0);
            GLES30.glGetFloatv(GLES30.GL_COLOR_CLEAR_VALUE,clear,0);GLES30.glGetFloatv(GLES30.GL_DEPTH_RANGE,depthRange,0);
            GLES30.glGetFloatv(GLES30.GL_DEPTH_CLEAR_VALUE,depthClear,0);GLES30.glGetVertexAttribfv(2,GLES30.GL_CURRENT_VERTEX_ATTRIB,attributeColor,0);
            for(int i=0;i<DISABLED.length;i++)enables[i]=GLES30.glIsEnabled(DISABLED[i]);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);texture2d=integer(GLES30.GL_TEXTURE_BINDING_2D);textureArray=integer(GLES30.GL_TEXTURE_BINDING_2D_ARRAY);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1);texture1Array=integer(GLES30.GL_TEXTURE_BINDING_2D_ARRAY);GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        }
        void restore() {
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER,drawFbo);GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER,readFbo);
            GLES30.glBindVertexArray(vao);GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,arrayBuffer);
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER,packBuffer);GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER,renderbuffer);GLES30.glUseProgram(program);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture2d);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,textureArray);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,texture1Array);GLES30.glActiveTexture(activeTexture);
            GLES30.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);GLES30.glColorMask(colorMask[0]!=0,colorMask[1]!=0,colorMask[2]!=0,colorMask[3]!=0);
            GLES30.glClearColor(clear[0],clear[1],clear[2],clear[3]);GLES30.glDepthMask(depthMask!=0);GLES30.glDepthFunc(depthFunc);
            GLES30.glDepthRangef(depthRange[0],depthRange[1]);GLES30.glClearDepthf(depthClear[0]);GLES30.glCullFace(cullFace);GLES30.glFrontFace(frontFace);
            GLES30.glVertexAttrib4f(2,attributeColor[0],attributeColor[1],attributeColor[2],attributeColor[3]);
            for(int i=0;i<DISABLED.length;i++)set(DISABLED[i],enables[i]);set(GLES30.GL_DEPTH_TEST,depth);set(GLES30.GL_CULL_FACE,cull);
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT,packAlignment);GLES30.glPixelStorei(GLES30.GL_PACK_ROW_LENGTH,packRows);
            GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_ROWS,skipRows);GLES30.glPixelStorei(GLES30.GL_PACK_SKIP_PIXELS,skipPixels);
        }
        private static void set(int capability,boolean enabled){if(enabled)GLES30.glEnable(capability);else GLES30.glDisable(capability);}
    }
}
