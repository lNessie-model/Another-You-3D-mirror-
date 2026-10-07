package com.mirror.bench;
import android.os.Bundle;
import java.lang.reflect.*;
public final class SrgbViewConfigTest {
 static int n;static Method read;static Field flag;
 static void ok(boolean b){n++;if(!b)throw new AssertionError("check "+n);}
 static Object parse(Bundle b,boolean d)throws Exception{return read.invoke(null,b,d,16);}
 static void reject(Bundle b,boolean d)throws Exception{try{parse(b,d);throw new AssertionError("accepted invalid");}catch(InvocationTargetException e){ok(e.getCause() instanceof IllegalArgumentException);}}
 public static void main(String[] args)throws Exception{
  Class<?> c=Class.forName("com.mirror.bench.MirrorActivity$InputOptions");read=c.getDeclaredMethod("read",Bundle.class,boolean.class,int.class);read.setAccessible(true);flag=c.getDeclaredField("srgbViews");flag.setAccessible(true);
  for(boolean debug:new boolean[]{true,false}){ok(!flag.getBoolean(parse(null,debug)));ok(!flag.getBoolean(parse(new Bundle(),debug)));}
  for(boolean v:new boolean[]{false,true}){Bundle b=new Bundle();b.putBoolean("test_srgb_views",v);ok(flag.getBoolean(parse(b,true))==v);reject(b,false);}
  Bundle b=new Bundle();b.putString("test_srgb_views",null);reject(b,true);b.putString("test_srgb_views","true");reject(b,true);b.putInt("test_srgb_views",1);reject(b,true);
  for(String k:new String[]{"test_avatar_batched","test_orm_rg8","test_pbr_fast_math","test_empty_interlace","test_specialized_batch","test_constant_white_primary","test_reuse_group_uniforms","test_static_background_cache","test_private_head"}){b=new Bundle();b.putBoolean("test_srgb_views",true);b.putBoolean(k,true);reject(b,true);}
  b=new Bundle();b.putBoolean("test_srgb_views",true);b.putInt("test_view_count",20);reject(b,true);b.putInt("test_view_count",16);ok(flag.getBoolean(parse(b,true)));
  InterlaceRenderer r=new InterlaceRenderer(16,.3f,true);ok(!r.runtimeStatus().getBoolean("srgb_views_requested"));r.setSrgbViews(true);ok(r.runtimeStatus().getBoolean("srgb_views_requested"));
  Field f=InterlaceRenderer.class.getDeclaredField("surfaceInitialized");f.setAccessible(true);f.setBoolean(r,true);try{r.setSrgbViews(false);throw new AssertionError("late config");}catch(IllegalStateException e){ok(true);}
  System.out.println("SrgbViewConfigTest: "+n+" checks GREEN; actual InputOptions and renderer");
 }
}
