package com.mirror.bench;
import android.opengl.GLES30;
import java.lang.reflect.*;
import java.nio.file.*;
public final class SrgbRendererBoundaryTest {
 static int n;static void ok(boolean x,String s){if(!x)throw new AssertionError(s);n++;}
 static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
 static Object call(Object o,String name)throws Exception{Method m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(o);}
 public static void main(String[] args)throws Exception{
  var asset=AvatarGlbLoader.load(Files.readAllBytes(Path.of(args[0])));String manifest=Files.readString(Path.of(args[1]));
  for(boolean enabled:new boolean[]{false,true})for(boolean before:new boolean[]{false,true}){
   GLES30.reset();GLES30.write=before;GLES30.writeAtDraw.clear();GLES30.writeAtClear.clear();GLES30.writeAtFinal.clear();
   var scene=AvatarGpuScene.fromAsset(asset,manifest,SrgbViewPolicy.GERALT,false,false,AvatarGpuScene.DrawMode.INDIVIDUAL,false,false,enabled);
   var r=new InterlaceRenderer(16,.3f,true);r.setRuntimeMode(true);r.setSrgbViews(enabled);set(r,"avatarScene",scene);set(r,"glContextGeneration",1L);
   if(enabled)set(r,"srgbViewGl",new SrgbViewGl(1,16,"GL_EXT_texture_sRGB_decode GL_EXT_sRGB_write_control"));
   r.onSurfaceChanged(null,1200,1920);
   ok(r.runtimeStatus().getString("error").isEmpty(),"setup failed: "+r.runtimeStatus());ok(GLES30.write==before,"init write restored");ok(GLES30.writeAtClear.size()==16,"same init clear count");
   for(boolean state:GLES30.writeAtClear)ok(state==(enabled?false:before),"encoded init clear preserved");
   ok(GLES30.format==(enabled?0x8C43:0x8058),"paired target format");GLES30.writeAtClear.clear();
   call(r,"drawAvatarViews");ok(GLES30.write==before,"view scope restored before final");ok(GLES30.writeAtDraw.size()==112,"16x7 same real draws");
   for(boolean state:GLES30.writeAtDraw)ok(state==(enabled||before),"view shader linear/write pairing");
   int program=GLES30.draws.get(0).program();set(r,"runtimeBackgroundProgram",program);set(r,"screenBackground",new SceneBackgroundTexture(null));
   Method finalDraw=InterlaceRenderer.class.getDeclaredMethod("drawInterlace",boolean.class);finalDraw.setAccessible(true);finalDraw.invoke(r,false);
   ok(GLES30.writeAtFinal.size()==1&&GLES30.writeAtFinal.get(0)==before,"actual final pass has original window state");
   GLES30.failDraw=GLES30.draws.size()+2;
   Field timing=InterlaceRenderer.class.getDeclaredField("runtimeViewTiming");timing.setAccessible(true);((ViewSubmissionTiming)timing.get(r)).reset();
   try{call(r,"drawAvatarViews");throw new AssertionError("expected draw failure");}catch(InvocationTargetException e){ok(e.getCause().getMessage().contains("injected draw failure"),"original exception retained: "+e.getCause());}
   ok(GLES30.write==before,"failure restores write before caller");GLES30.failDraw=0;
   if(enabled){r.onSurfaceChanged(null,1200,1920);ok(r.runtimeStatus().getString("error").isEmpty(),"resize requalified");ok(r.runtimeStatus().getString("srgb_views_actual").equals("srgb8_alpha8_skip_decode"),"actual only after complete");}
   scene.dispose();
  }
  System.out.println("SrgbRendererBoundaryTest: "+n+" checks GREEN; actual serial production init/view/final/resize/failure calls, no rasterization");
 }
}
