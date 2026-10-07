package com.mirror.bench;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.Matrix;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import static com.mirror.bench.AvatarMultiviewCheck.*;

/** Blocking, opt-in GL-owner readback diagnostic. No timer queries; never used by runtime rendering.
 * Each backend compares direct ordinary reference vs candidate on one prepared Scene/VBO/texture set.
 * Transparent diagnostic means explicitly no draw, not an absent face (runtime still draws neutral).
 */
final class AvatarSrgbCheck {
    static final int W=400,H=640,VIEWS=16,FW=1200,FH=1920;
    static final float ASPECT=1200f/1920f;
    static final String MANIFEST="b4d324f64ea3655fe01615ce97081c275d60d50312fd0d2b066f8a78e668032e";
    private static final int[] DEPTH_ONLY={GLES30.GL_DEPTH_ATTACHMENT};
    private AvatarSrgbCheck(){}
    interface Progress {void publish(JSONObject progress)throws Exception;}
    static boolean numericPass(AvatarPixelComparison.Stats s){return s.maxRgbError<=1&&s.rmse<=.1&&s.alphaMismatches==0;}
    static JSONObject run(AssetManager assets,File files,int width,int height,PanelCalibration panel,
                          SceneViewSettings settings,BooleanSupplier cancelled,Progress progress)throws Exception{
        JSONObject report=new JSONObject().put("passed",false).put("completed",false).put("performance_evidence",false)
                .put("optical_validation",false).put("artwork_validated",false).put("model_sha256",SrgbViewPolicy.GERALT)
                .put("manifest_sha256",MANIFEST).put("views",VIEWS).put("view_width",W).put("view_height",H)
                .put("expected_fixtures",69).put("expected_layer_comparisons",2208).put("expected_final_comparisons",48)
                .put("max_rgb_error_allowed",1).put("rmse_allowed",.1).put("alpha_error_allowed",0)
                .put("scope","Direct ordinary RGBA8/shader-encoded versus sRGB8-alpha8/linear output, within serial and within OVR4; same prepared geometry/textures. Final comparisons use actual production shader and default framebuffer; no face inference.")
                .put("legacy_stats_labels","serial=RGBA8 reference; candidate=sRGB target, within named backend; foreground fields target the old opaque diagnostic clear and are not used")
                .put("scene_view",new JSONObject(settings.toMap())).put("panel",panelJson(panel));
        JSONArray poses=new JSONArray(),finals=new JSONArray(),cleanupErrors=new JSONArray();report.put("poses",poses).put("final_comparisons",finals).put("cleanup_errors",cleanupErrors);
        long started=System.nanoTime();int completed=0,layers=0,prepares=0,max=0;long mismatches=0;boolean passed=true;
        SavedState saved=null;ExtraState extra=null;AvatarGpuScene scene=null;AvatarGpuScene.SrgbComparison comparison=null;
        SrgbViewGl guard=null;SceneBackgroundTexture backgrounds=null;PersistentMultiviewFbos[] groups=new PersistentMultiviewFbos[2];
        int[] textures=new int[3],fbos=new int[2],depth=new int[1],vao=new int[1];int finalProgram=0;
        String[] centers=new String[2];int[] changed=new int[2];
        try{
            checkCancelled(cancelled);checkGl("sRGB diagnostic entry");
            if(width!=FW||height!=FH)throw new IllegalArgumentException("Actual default framebuffer must be 1200x1920");
            if(settings.cameraSpan<=0)throw new IllegalArgumentException("Independent views require nonzero current camera span");
            String extensions=GLES30.glGetString(GLES30.GL_EXTENSIONS);SrgbViewPolicy.requireExtensions(extensions);
            if(!(" "+extensions+" ").contains(" GL_OVR_multiview2 ")||integer(0x9631)<4)throw new IllegalStateException("OVR4 required");
            if(integer(GLES30.GL_TRANSFORM_FEEDBACK_ACTIVE)!=0)throw new IllegalStateException("Active transform feedback unsupported");
            report.put("gl_vendor",GLES30.glGetString(GLES30.GL_VENDOR)).put("gl_renderer",GLES30.glGetString(GLES30.GL_RENDERER)).put("gl_version",GLES30.glGetString(GLES30.GL_VERSION));
            extra=new ExtraState();saved=new SavedState();report.put("window_srgb_write_initial",extra.write);
            for(int unit=0;unit<3;unit++){
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0+unit);
                if(integer(GLES30.GL_SAMPLER_BINDING)!=0)throw new IllegalStateException("Diagnostic requires unoverridden material/view samplers");
            }
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);int[] encoding=new int[1];
            GLES30.glGetFramebufferAttachmentParameteriv(GLES30.GL_FRAMEBUFFER,GLES30.GL_BACK,GLES30.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING,encoding,0);checkGl("window encoding");
            report.put("window_color_encoding",encoding[0]);
            GLES30.glGenVertexArrays(1,vao,0);GLES30.glBindVertexArray(vao[0]);
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER,0);GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER,0);
            for(int p:new int[]{GLES30.GL_PACK_ROW_LENGTH,GLES30.GL_PACK_SKIP_ROWS,GLES30.GL_PACK_SKIP_PIXELS,GLES30.GL_UNPACK_ROW_LENGTH,GLES30.GL_UNPACK_SKIP_ROWS,GLES30.GL_UNPACK_SKIP_PIXELS})GLES30.glPixelStorei(p,0);
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT,1);GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT,4);
            for(int cap:DISABLED)GLES30.glDisable(cap);
            GLES30.glColorMask(true,true,true,true);GLES30.glDepthMask(true);GLES30.glDepthFunc(GLES30.GL_LESS);GLES30.glDepthRangef(0,1);GLES30.glClearDepthf(1);
            scene=AvatarGpuScene.bundled(assets,"avatars/catalog/geralt",SrgbViewPolicy.GERALT,MANIFEST,true,false,AvatarGpuScene.DrawMode.INDIVIDUAL);
            scene.setSceneView(settings);report.put("avatar_initial",scene.status());comparison=scene.createSrgbComparison();
            GLES30.glGenTextures(3,textures,0);GLES30.glGenFramebuffers(2,fbos,0);GLES30.glGenRenderbuffers(1,depth,0);
            guard=new SrgbViewGl(1,16,extensions);GLES30.glActiveTexture(GLES30.GL_TEXTURE0);guard.requireSampling(1);
            for(int i=0;i<2;i++){
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,textures[i]);GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY,1,i==0?GLES30.GL_RGBA8:GLES30.GL_SRGB8_ALPHA8,W,H,VIEWS);
                for(int key:new int[]{GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_TEXTURE_MAG_FILTER})GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY,key,GLES30.GL_LINEAR);
                for(int key:new int[]{GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_TEXTURE_WRAP_T})GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY,key,GLES30.GL_CLAMP_TO_EDGE);
                if(i==1)guard.configureBoundArray(textures[i],1);
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,textures[2]);GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY,1,GLES30.GL_DEPTH_COMPONENT16,W,H,VIEWS);
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER,depth[0]);GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER,GLES30.GL_DEPTH_COMPONENT16,W,H);
            for(int i=0;i<2;i++)groups[i]=PersistentMultiviewFbos.create(PersistentMultiviewGl.INSTANCE,1,VIEWS,textures[i],textures[2]);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbos[0]);GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER,GLES30.GL_DEPTH_ATTACHMENT,GLES30.GL_RENDERBUFFER,depth[0]);
            for(int v=0;v<VIEWS;v++){GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,textures[1],0,v);guard.verifyBoundAttachment(textures[1],v,1,false,1);}
            for(int v=0;v<VIEWS;v+=4){groups[1].bind(v,1);guard.verifyBoundAttachment(textures[1],v,4,true,1);}guard.complete(true,1);
            report.put("candidate_actual",guard.state());
            finalProgram=program(InterlaceRenderer.runtimeInterlaceVertex(panel),InterlaceRenderer.runtimeInterlaceFragment(panel));
            report.put("final_fragment_sha256",hash(InterlaceRenderer.runtimeInterlaceFragment(panel).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            backgrounds=new SceneBackgroundTexture(assets);
            float[] vp=matrices(settings),four=new float[64],one=new float[16],weights=new float[52],angles=new float[3];
            HashSet<String> matrixHashes=new HashSet<>();for(int v=0;v<VIEWS;v++)matrixHashes.add(java.util.Arrays.toString(java.util.Arrays.copyOfRange(vp,v*16,v*16+16)));
            if(matrixHashes.size()!=16)throw new IllegalStateException("Duplicate actual camera matrix");report.put("distinct_actual_matrices",matrixHashes.size()).put("camera_vp",new JSONArray(vp));
            ByteBuffer reference=ByteBuffer.allocateDirect(W*H*4),candidate=ByteBuffer.allocateDirect(W*H*4);
            ByteBuffer finalReference=ByteBuffer.allocateDirect(FW*FH*4),finalCandidate=ByteBuffer.allocateDirect(FW*FH*4);
            var fixtures=AvatarPoseFixtures.regression();if(fixtures.size()!=69)throw new IllegalStateException("Expected 69 poses");
            for(var pose:fixtures){
                checkCancelled(cancelled);pose.copyWeights(weights);pose.copyAngles(angles);scene.prepare(weights,angles);prepares++;
                JSONObject row=new JSONObject().put("name",pose.name()).put("weights",new JSONArray(weights)).put("angles",new JSONArray(angles));JSONArray modes=new JSONArray();row.put("modes",modes);poses.put(row);boolean posePass=true;
                for(int mode=0;mode<2;mode++){
                    boolean multi=mode==1;JSONArray layerRows=new JSONArray();HashSet<String> refHashes=new HashSet<>(),candHashes=new HashSet<>();String center=null;
                    JSONObject before=comparison.status();long draws=scene.status().getLong("individual_draw_calls");
                    for(int pass=0;pass<2;pass++)renderViews(fbos[0],textures[pass],groups[pass],comparison,guard,vp,four,one,multi,pass==1,false,cancelled);
                    boolean modePass=true;
                    for(int v=0;v<VIEWS;v++){
                        checkCancelled(cancelled);readLayer(fbos[1],textures[0],v,reference);readLayer(fbos[1],textures[1],v,candidate);
                        var st=AvatarPixelComparison.compare(reference,candidate,W,H);boolean equal=numericPass(st)&&st.pixels-st.serialNonOpaque>=st.pixels/1000&&st.pixels-st.candidateNonOpaque>=st.pixels/1000;
                        layerRows.put(toJson(st).put("global_view",v).put("passed",equal));layers++;modePass&=equal;max=Math.max(max,st.maxRgbError);mismatches+=st.rgbMismatches;
                        refHashes.add(st.serialSha256);candHashes.add(st.candidateSha256);if(v==8)center=st.serialSha256;
                    }
                    JSONObject after=comparison.status();int expected=multi?4:16;
                    long refGroups=after.getLong("reference_groups")-before.getLong("reference_groups"),candGroups=after.getLong("candidate_groups")-before.getLong("candidate_groups");
                    long actualDraws=scene.status().getLong("individual_draw_calls")-draws;
                    modePass&=refHashes.size()==16&&candHashes.size()==16&&refGroups==expected&&candGroups==expected&&actualDraws==expected*14;
                    if(centers[mode]==null)centers[mode]=center;else if(!centers[mode].equals(center))changed[mode]++;
                    modes.put(new JSONObject().put("backend",multi?"ordinary_ovr4":"ordinary_serial").put("layers",layerRows).put("reference_distinct_views",refHashes.size()).put("candidate_distinct_views",candHashes.size()).put("reference_groups",refGroups).put("candidate_groups",candGroups).put("actual_draw_calls",actualDraws).put("passed",modePass));posePass&=modePass;
                    if(multi&&finalPose(pose.name()))passed&=finals(finals,finalProgram,textures,backgrounds,panel,guard,extra.write,pose.name(),finalReference,finalCandidate,files,cancelled);
                }
                row.put("passed",posePass);passed&=posePass;completed++;
                if(progress!=null)progress.publish(new JSONObject().put("running",true).put("passed",false).put("completed",false).put("performance_evidence",false)
                        .put("completed_fixtures",completed).put("layer_comparisons",layers).put("final_comparison_count",finals.length()).put("current_pose",pose.name()));
            }
            // This explicit no-draw diagnostic clears all layers; missing runtime faces still draw neutral.
            for(int pass=0;pass<2;pass++)renderViews(fbos[0],textures[pass],groups[pass],comparison,guard,vp,four,one,true,pass==1,true,cancelled);
            for(int pass=0;pass<2;pass++)for(int layer=0;layer<16;layer++){
                readLayer(fbos[1],textures[pass],layer,candidate);
                for(int i=0;i<candidate.capacity();i++)if(candidate.get(i)!=0)throw new IllegalStateException("Explicit no-draw layer not transparent black");
            }
            report.put("transparent_no_draw_verified_layers",32);
            passed&=finals(finals,finalProgram,textures,backgrounds,panel,guard,extra.write,"explicit-transparent-no-draw",finalReference,finalCandidate,files,cancelled);
            passed&=completed==69&&prepares==69&&layers==2208&&finals.length()==48&&changed[0]>0&&changed[1]>0;
            report.put("program_binding_evidence",comparison.status()).put("completed",true).put("passed",passed);
        }catch(Exception|LinkageError failure){report.put("passed",false).put("error",failure.toString()).put("cancelled",failure instanceof CancellationException);}
        finally{
            if(guard!=null)try{guard.endWrite();}catch(Exception|LinkageError e){cleanupErrors.put(e.toString());}
            if(comparison!=null)try{comparison.close();}catch(Exception|LinkageError e){cleanupErrors.put(e.toString());}
            if(scene!=null)try{scene.dispose();}catch(Exception|LinkageError e){cleanupErrors.put(e.toString());}
            if(backgrounds!=null)try{backgrounds.bind(0);}catch(Exception|LinkageError e){cleanupErrors.put(e.toString());}
            for(var group:groups)if(group!=null)try{group.close(1);}catch(Exception|LinkageError e){cleanupErrors.put(e.toString());}
            try{if(finalProgram!=0)GLES30.glDeleteProgram(finalProgram);}catch(Exception|LinkageError e){cleanupErrors.put(e.toString());}
            try{GLES30.glDeleteTextures(3,textures,0);}catch(Exception|LinkageError e){cleanupErrors.put(e.toString());}
            try{GLES30.glDeleteFramebuffers(2,fbos,0);}catch(Exception|LinkageError e){cleanupErrors.put(e.toString());}
            try{GLES30.glDeleteRenderbuffers(1,depth,0);}catch(Exception|LinkageError e){cleanupErrors.put(e.toString());}
            try{GLES30.glDeleteVertexArrays(1,vao,0);}catch(Exception|LinkageError e){cleanupErrors.put(e.toString());}
            try{if(saved!=null)saved.restore();}catch(Exception|LinkageError e){cleanupErrors.put(e.toString());}
            try{if(extra!=null)extra.restore();checkGl("sRGB state restored");}catch(Exception|LinkageError e){cleanupErrors.put(e.toString());}
            if(cleanupErrors.length()!=0)report.put("passed",false);
            report.put("completed_fixtures",completed).put("prepare_calls",prepares).put("layer_comparisons",layers).put("final_comparison_count",finals.length()).put("max_layer_rgb_error",max).put("layer_rgb_mismatches",mismatches).put("changed_serial_center_poses",changed[0]).put("changed_ovr_center_poses",changed[1]).put("diagnostic_elapsed_ms",(System.nanoTime()-started)/1e6);
        }
        return report;
    }
    private static boolean finalPose(String name){return name.equals("neutral")||name.equals("smile-blink-tilt")||name.equals("source-eyeBlinkLeft");}
    private static void renderViews(int fbo,int texture,PersistentMultiviewFbos groups,AvatarGpuScene.SrgbComparison comparison,SrgbViewGl guard,float[] vp,float[] four,float[] one,boolean multi,boolean candidate,boolean noDraw,BooleanSupplier cancelled){
        Throwable original=null;guard.beginWrite(candidate,1);
        try{
            GLES30.glViewport(0,0,W,H);GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
            for(int base=0;base<16;base+=multi?4:1){
                checkCancelled(cancelled);
                if(multi)groups.bind(base,1);else{GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo);GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,texture,0,base);complete("serial sRGB");}
                GLES30.glClearColor(0,0,0,0);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);
                if(!noDraw){float[] m=multi?four:one;System.arraycopy(vp,base*16,m,0,multi?64:16);comparison.draw(m,multi?4:1,ASPECT,candidate);}
                GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER,1,DEPTH_ONLY,0);
            }checkGl("sRGB view draw");
        }catch(RuntimeException|Error e){original=e;throw e;}
        finally{try{guard.endWrite();}catch(RuntimeException|Error e){if(original!=null)original.addSuppressed(e);else throw e;}}
    }
    private static void readLayer(int fbo,int texture,int layer,ByteBuffer out){GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo);GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,texture,0,layer);complete("sRGB read layer");out.clear();GLES30.glReadPixels(0,0,W,H,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,out);checkGl("sRGB read");}
    private static boolean finals(JSONArray rows,int program,int[] textures,SceneBackgroundTexture backgrounds,PanelCalibration panel,SrgbViewGl guard,boolean windowWrite,String pose,ByteBuffer ref,ByteBuffer cand,File files,BooleanSupplier cancelled)throws Exception{
        boolean pass=true;
        for(int bg=0;bg<SceneViewSettings.BACKGROUNDS.length;bg++){
            checkCancelled(cancelled);
            for(int p=0;p<2;p++){
                if(GLES30.glIsEnabled(SrgbViewGl.WRITE)!=windowWrite)throw new IllegalStateException("Window sRGB write leaked from view stage");
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);GLES30.glViewport(0,0,FW,FH);
                GLES30.glDisable(GLES30.GL_DEPTH_TEST);GLES30.glDisable(GLES30.GL_CULL_FACE);GLES30.glDisable(GLES30.GL_SCISSOR_TEST);GLES30.glUseProgram(program);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0);guard.requireSampling(1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,textures[p]);
                GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"uViews"),0);backgrounds.bind(bg);
                GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"uBackground"),bg);GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"uBackgroundImage"),1);
                GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"uCount"),16);GLES30.glUniform1f(GLES30.glGetUniformLocation(program,"uPitch"),panel.pitchSubpixels());GLES30.glUniform1f(GLES30.glGetUniformLocation(program,"uTilt"),panel.tiltSubpixelsPerPixel());
                GLES30.glUniform1f(GLES30.glGetUniformLocation(program,"uPhase"),panel.phaseCycles());GLES30.glUniform1f(GLES30.glGetUniformLocation(program,"uHeight"),FH);
                GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"uBgr"),panel.subpixelOrder()==PanelCalibration.SubpixelOrder.BGR?1:0);GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"uReverse"),panel.reverseViews()?1:0);GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"uTop"),panel.yOrigin()==PanelCalibration.YOrigin.TOP?1:0);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);ByteBuffer out=p==0?ref:cand;out.clear();GLES30.glReadPixels(0,0,FW,FH,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,out);checkGl("actual final framebuffer");
            }
            var stats=AvatarPixelComparison.compare(ref,cand,FW,FH);boolean equal=numericPass(stats)&&stats.serialNonOpaque==0&&stats.candidateNonOpaque==0;pass&=equal;
            JSONObject row=toJson(stats).put("pose",pose).put("background",bg).put("background_label",SceneViewSettings.BACKGROUNDS[bg]).put("passed",equal);rows.put(row);
            if(bg==9&&(pose.equals("neutral")||pose.equals("smile-blink-tilt")))row.put("png",savePair(files,pose,ref,cand));
        }return pass;
    }
    private static float[] matrices(SceneViewSettings settings){
        float[] result=new float[256],v=new float[16],p=new float[16];
        for(int i=0;i<16;i++){float eye=settings.eyeAt(i,16),shift=settings.frustumShift(eye);Matrix.setLookAtM(v,0,eye,0,3,eye,0,0,0,1,0);Matrix.frustumM(p,0,-.052f*ASPECT+shift,.052f*ASPECT+shift,-.052f,.052f,.1f,10);Matrix.multiplyMM(result,i*16,p,0,v,0);}return result;
    }
    private static JSONObject panelJson(PanelCalibration p)throws Exception{return new JSONObject().put("pitch",p.pitch()).put("tan",p.tan()).put("phase",p.phaseCycles()).put("pitch_units",p.pitchUnits()).put("subpixel_order",p.subpixelOrder()).put("reverse",p.reverseViews()).put("y_origin",p.yOrigin());}
    private static JSONObject savePair(File files,String pose,ByteBuffer a,ByteBuffer b)throws Exception{
        File dir=new File(files,"avatar-srgb-preview");if(!dir.isDirectory()&&!dir.mkdirs())throw new IllegalStateException("Cannot create sRGB diagnostic PNG directory");
        return new JSONObject().put("reference",savePng(new File(dir,pose+"-reference.png"),a)).put("candidate",savePng(new File(dir,pose+"-candidate.png"),b));
    }
    private static String savePng(File file,ByteBuffer pixels)throws Exception{
        int[] argb=new int[FW*FH];for(int y=0;y<FH;y++)for(int x=0;x<FW;x++){int pos=((FH-1-y)*FW+x)*4;argb[y*FW+x]=((pixels.get(pos+3)&255)<<24)|((pixels.get(pos)&255)<<16)|((pixels.get(pos+1)&255)<<8)|(pixels.get(pos+2)&255);}
        Bitmap image=Bitmap.createBitmap(argb,FW,FH,Bitmap.Config.ARGB_8888);try(FileOutputStream out=new FileOutputStream(file)){if(!image.compress(Bitmap.CompressFormat.PNG,100,out))throw new IllegalStateException("PNG write failed");}finally{image.recycle();}return file.getAbsolutePath();
    }
    private static String hash(byte[] b)throws Exception{byte[] d=MessageDigest.getInstance("SHA-256").digest(b);StringBuilder s=new StringBuilder();for(byte x:d)s.append(String.format(java.util.Locale.ROOT,"%02x",x&255));return s.toString();}
    private static int program(String vertex,String fragment){
        int program=GLES30.glCreateProgram();try{
            for(int type:new int[]{GLES30.GL_VERTEX_SHADER,GLES30.GL_FRAGMENT_SHADER}){int shader=GLES30.glCreateShader(type);try{GLES30.glShaderSource(shader,type==GLES30.GL_VERTEX_SHADER?vertex:fragment);GLES30.glCompileShader(shader);int[] ok=new int[1];GLES30.glGetShaderiv(shader,GLES30.GL_COMPILE_STATUS,ok,0);if(ok[0]==0)throw new IllegalStateException(GLES30.glGetShaderInfoLog(shader));GLES30.glAttachShader(program,shader);}finally{GLES30.glDeleteShader(shader);}}
            GLES30.glLinkProgram(program);int[] ok=new int[1];GLES30.glGetProgramiv(program,GLES30.GL_LINK_STATUS,ok,0);if(ok[0]==0)throw new IllegalStateException(GLES30.glGetProgramInfoLog(program));return program;
        }catch(RuntimeException|Error original){try{GLES30.glDeleteProgram(program);}catch(RuntimeException|Error cleanup){original.addSuppressed(cleanup);}throw original;}
    }
    private static final class ExtraState{
        final boolean write=GLES30.glIsEnabled(SrgbViewGl.WRITE);final int active=integer(GLES30.GL_ACTIVE_TEXTURE),unpack=integer(GLES30.GL_PIXEL_UNPACK_BUFFER_BINDING);
        final int[] keys={GLES30.GL_UNPACK_ALIGNMENT,GLES30.GL_UNPACK_ROW_LENGTH,GLES30.GL_UNPACK_SKIP_ROWS,GLES30.GL_UNPACK_SKIP_PIXELS};final int[] values=new int[4],textures=new int[2];final float[] uv=new float[4];
        ExtraState(){for(int i=0;i<4;i++)values[i]=integer(keys[i]);GLES30.glGetVertexAttribfv(4,GLES30.GL_CURRENT_VERTEX_ATTRIB,uv,0);for(int i=0;i<2;i++){GLES30.glActiveTexture(GLES30.GL_TEXTURE1+i);textures[i]=integer(GLES30.GL_TEXTURE_BINDING_2D);}GLES30.glActiveTexture(active);}
        void restore(){for(int i=0;i<2;i++){GLES30.glActiveTexture(GLES30.GL_TEXTURE1+i);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,textures[i]);}GLES30.glActiveTexture(active);GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER,unpack);for(int i=0;i<4;i++)GLES30.glPixelStorei(keys[i],values[i]);GLES30.glVertexAttrib4f(4,uv[0],uv[1],uv[2],uv[3]);if(write)GLES30.glEnable(SrgbViewGl.WRITE);else GLES30.glDisable(SrgbViewGl.WRITE);}
    }
}
