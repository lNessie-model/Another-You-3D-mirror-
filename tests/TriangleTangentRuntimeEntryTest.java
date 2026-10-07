package com.mirror.bench;
import android.content.res.AssetManager;
import android.opengl.GLES30;
import java.lang.reflect.*;
import java.nio.file.*;
public final class TriangleTangentRuntimeEntryTest {
 static int checks;static void ok(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 static void invoke(Object o,String name)throws Exception{Method m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(o);}
 static InterlaceRenderer configured(boolean enabled){
  var r=new InterlaceRenderer(16,.3f,true);r.setRuntimeMode(true);r.setPipeline(true);r.setCull(true,false);r.setPreblend(true,false);r.setMultiview(true,false);r.setExplicitLod(true);r.setDiscardDepth(true,false);
  var a=new AssetManager();r.setRuntimeAvatar(a);r.setAvatarAsynchronous(true);r.setAvatarBatched(false);r.setSpecializedBatch(false);r.setConstantWhitePrimary(false);r.setReuseGroupUniforms(false);r.setSrgbViews(false);r.setTriangleTangent(enabled);r.setPersistentMultiviewFbos(true);r.setCachedCameraVp(false);r.setGpuProfile(false);r.setOrmRg8(false);r.setPbrFastMath(false);r.setEmptyInterlace(false);r.setStaticBackgroundCache(false);
  r.setBundledRuntimeAvatar(a,"avatars/catalog/geralt","geralt",SrgbViewPolicy.GERALT,"b4d324f64ea3655fe01615ce97081c275d60d50312fd0d2b066f8a78e668032e",true);return r;
 }
 public static void main(String[] args)throws Exception{
  AssetManager.root=Path.of(args[0]);
  for(boolean enabled:new boolean[]{false,true}){
   GLES30.reset();var r=configured(enabled);r.onSurfaceCreated(null,null);ok(r.runtimeStatus().getString("error").isEmpty(),"actual startup "+r.runtimeStatus());ok((Boolean)field(r,"explicitLod"),"ordinary legacy flag remains");
   var s=(AvatarGpuScene)field(r,"avatarScene");r.onSurfaceChanged(null,1200,1920);ok(r.runtimeStatus().getString("error").isEmpty(),"actual resize");
   ok(s.status().getString("pose_backend").equals("bounded_cpu_worker"),"real async retained through renderer");invoke(r,"drawAvatarViews");
   ok(s.status().getLong("individual_draw_calls")==28,"16 views x7 entries through4groups");
   ok(s.status().getJSONObject("triangle_tangent").getString("actual").equals(enabled?"triangle_tb_async":"disabled"),"actual only after candidate calls");
   if(enabled){ok(s.status().getJSONObject("triangle_tangent").getLong("runtime_candidate_primary_draws")==4,"four primary candidate draws");ok(s.status().getJSONObject("triangle_tangent").getLong("table_uploads")==1,"same first pose reused for four groups");
    AvatarPoseWorker old=TriangleTangentRuntimeSceneTest.worker(s);GLES30.reset();r.onSurfaceCreated(null,null);ok(r.runtimeStatus().getString("error").isEmpty(),"fresh context requalification");var next=(AvatarGpuScene)field(r,"avatarScene");ok(next!=s&&next.status().getJSONObject("triangle_tangent").getLong("table_uploads")==0,"new context owns new unbuilt table");long end=System.nanoTime()+3_000_000_000L;while(!old.isTerminated()&&System.nanoTime()<end)Thread.sleep(4);ok(old.isTerminated(),"lost-context CPU owner stopped without GL deletion");s=next;
   }
   TriangleTangentRuntimeSceneTest.stop(s);
  }
  GLES30.reset();var bad=configured(true);bad.setAvatarBatched(true);bad.onSurfaceCreated(null,null);String first=bad.runtimeStatus().getString("error");ok(!first.isEmpty(),"actual invalid runtime flags rejected");int calls=GLES30.framebufferBinds;bad.onSurfaceChanged(null,1200,1920);ok(first.equals(bad.runtimeStatus().getString("error"))&&calls==GLES30.framebufferBinds,"first qualifier error preserved with no resize calls");
  Method fail=InterlaceRenderer.class.getDeclaredMethod("fail",Throwable.class);fail.setAccessible(true);fail.invoke(bad,new IllegalStateException("second error"));ok(first.equals(bad.runtimeStatus().getString("error")),"first error retained");
  System.out.println("TriangleTangentRuntimeEntryTest: "+checks+" checks; actual renderer startup/resize/lost-context qualification");
 }
}
