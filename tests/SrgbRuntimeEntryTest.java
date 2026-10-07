package com.mirror.bench;
import android.content.res.AssetManager;
import android.opengl.GLES30;
import java.lang.reflect.*;
import java.nio.file.*;
public final class SrgbRuntimeEntryTest {
 static int checks;static void ok(boolean x,String label){checks++;if(!x)throw new AssertionError(label);}
 static Object field(Object x,String name)throws Exception{var f=x.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(x);}
 static void set(Object x,String name,Object v)throws Exception{var f=x.getClass().getDeclaredField(name);f.setAccessible(true);f.set(x,v);}
 static void invoke(Object x,String name)throws Exception{var m=x.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(x);}
 static InterlaceRenderer configured(boolean enabled){
  var r=new InterlaceRenderer(16,.3f,true);r.setRuntimeMode(true);
  // The ordinary MirrorActivity startup sequence, including the previously missed legacy LOD flag.
  r.setPipeline(true);r.setCull(true,false);r.setPreblend(true,false);r.setMultiview(true,false);r.setExplicitLod(true);r.setDiscardDepth(true,false);
  var assets=new AssetManager();r.setRuntimeAvatar(assets);r.setAvatarAsynchronous(true);r.setAvatarBatched(false);
  r.setSpecializedBatch(false);r.setConstantWhitePrimary(false);r.setReuseGroupUniforms(false);r.setSrgbViews(enabled);r.setPersistentMultiviewFbos(true);
  r.setCachedCameraVp(false);r.setGpuProfile(false);r.setOrmRg8(false);r.setPbrFastMath(false);r.setEmptyInterlace(false);r.setStaticBackgroundCache(false);
  r.setBundledRuntimeAvatar(assets,"avatars/catalog/geralt","geralt",SrgbViewPolicy.GERALT,"b4d324f64ea3655fe01615ce97081c275d60d50312fd0d2b066f8a78e668032e",true);
  return r;
 }
 public static void main(String[] args)throws Exception{
  AssetManager.root=Path.of(args[0]);
  for(boolean enabled:new boolean[]{false,true}){
   GLES30.reset();GLES30.write=true;GLES30.finalPrograms.clear();var r=configured(enabled);
   r.onSurfaceCreated(null,null);String error=r.runtimeStatus().getString("error");
   ok(error.isEmpty(),"Ordinary Activity flags rejected by actual onSurfaceCreated: "+error);
   ok((Boolean)field(r,"explicitLod"),"ordinary flag retained");
   r.onSurfaceChanged(null,1200,1920);ok(r.runtimeStatus().getString("error").isEmpty(),"actual resize readiness: "+r.runtimeStatus());
   ok(r.runtimeStatus().getInt("persistent_fbo_count")==4,"four actual groups");
   ok(r.runtimeStatus().getString("srgb_views_actual").equals(enabled?"srgb8_alpha8_skip_decode":"disabled"),"actual target qualified");
   invoke(r,"drawAvatarViews");ok(GLES30.write,"view state restored");
   var draw=InterlaceRenderer.class.getDeclaredMethod("drawInterlace",boolean.class);draw.setAccessible(true);draw.invoke(r,false);
   int background=(Integer)field(r,"runtimeBackgroundProgram"),legacy=(Integer)field(r,"explicitLodProgram");
   ok(background!=0&&legacy!=0&&background!=legacy,"both real linked programs distinct");ok(GLES30.finalPrograms.get(GLES30.finalPrograms.size()-1)==background,"actual final uses runtime background, not legacy explicitLOD");
   if(enabled){Object scene=field(r,"avatarScene");set(r,"avatarScene",null);int calls=GLES30.finalPrograms.size();try{draw.invoke(r,false);throw new AssertionError("candidate fallback accepted");}catch(InvocationTargetException e){ok(e.getCause() instanceof IllegalStateException,"invalid selected program refused");}ok(GLES30.finalPrograms.size()==calls,"no invalid final draw");set(r,"avatarScene",scene);}
   r.closeRuntimeAvatar();((AvatarGpuScene)field(r,"avatarScene")).dispose();
  }
  GLES30.reset();var bad=configured(true);bad.setAvatarBatched(true);bad.onSurfaceCreated(null,null);String first=bad.runtimeStatus().getString("error");ok(first.contains("requires exact ordinary"),"real first qualification error");int before=GLES30.framebufferBinds;
  bad.onSurfaceChanged(null,1200,1920);ok(bad.runtimeStatus().getString("error").equals(first),"resize preserves initial error");ok(GLES30.framebufferBinds==before,"failed init performs no resize GL");
  var fail=InterlaceRenderer.class.getDeclaredMethod("fail",Throwable.class);fail.setAccessible(true);fail.invoke(bad,new IllegalStateException("later error"));ok(bad.runtimeStatus().getString("error").equals(first),"later callback cannot overwrite first error");
  var missing=new InterlaceRenderer(16,.3f,true);missing.setRuntimeMode(true);missing.setSrgbViews(true);before=GLES30.framebufferBinds;
  missing.onSurfaceChanged(null,1200,1920);String contextError=missing.runtimeStatus().getString("error");ok(contextError.contains("sRGB context qualification missing"),"missing-context first error remains actionable");
  missing.onSurfaceChanged(null,1200,1920);fail.invoke(missing,new IllegalStateException("later resize fallout"));ok(missing.runtimeStatus().getString("error").equals(contextError),"missing-context first error also preserved");ok(GLES30.framebufferBinds==before,"uninitialized candidate never binds framebuffer");
  System.out.println("SrgbRuntimeEntryTest: "+checks+" GREEN; actual onSurfaceCreated/resize/ordinary flags/program dispatch, GL/Asset/native attach boundary only");
 }
}
