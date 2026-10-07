package com.mirror.bench;
import android.opengl.GLES30;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
/** Runs unchanged old public/package API against parent bytes and candidate classes. */
public final class SrgbDefaultTraceTest {
 static void set(Object x,String n,Object v)throws Exception{var f=x.getClass().getDeclaredField(n);f.setAccessible(true);f.set(x,v);}
 public static void main(String[] args)throws Exception{
  GLES30.reset();GLES30.write=true;
  var asset=AvatarGlbLoader.load(Files.readAllBytes(Path.of(args[0])));var scene=AvatarGpuScene.fromAsset(asset,Files.readString(Path.of(args[1])),"9381f452c53098314f97e1a1799ec55d2be37878e66bf205c7a26958afcef531",false,false,AvatarGpuScene.DrawMode.INDIVIDUAL);
  var r=new InterlaceRenderer(16,.3f,true);r.setRuntimeMode(true);set(r,"avatarScene",scene);r.onSurfaceChanged(null,1200,1920);
  Method draw=InterlaceRenderer.class.getDeclaredMethod("drawAvatarViews");draw.setAccessible(true);draw.invoke(r);
  StringBuilder trace=new StringBuilder();new TreeMap<>(GLES30.linkedSources).forEach((id,source)->trace.append(id).append(':').append(source));
  trace.append(GLES30.writeAtClear).append(GLES30.writeAtDraw).append(GLES30.format).append(GLES30.finish);
  for(var d:GLES30.draws)trace.append(d.program()).append(':').append(d.count()).append(':').append(d.indexBuffer()).append(':').append(new TreeMap<>(d.attributes())).append(Arrays.toString(d.vp())).append(Arrays.toString(d.world())).append(Arrays.toString(d.normal()));
  var digest=MessageDigest.getInstance("SHA-256").digest(trace.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(Locale.ROOT,"%02x",b&255));
  scene.dispose();System.out.println(hex);
 }
}
