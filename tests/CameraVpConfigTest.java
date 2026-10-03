package com.mirror.bench;

import android.os.Bundle;
import java.lang.reflect.*;
import org.json.JSONObject;

public final class CameraVpConfigTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    public static void main(String[] args)throws Exception {
        Class<?> type=Class.forName("com.mirror.bench.MirrorActivity$InputOptions");
        Method read=type.getDeclaredMethod("read",Bundle.class,boolean.class);read.setAccessible(true);
        Field enabled=type.getDeclaredField("cachedCameraVp");enabled.setAccessible(true);
        check(!enabled.getBoolean(read.invoke(null,null,true)));Bundle extras=new Bundle();
        extras.putBoolean("test_cached_camera_vp",true);check(enabled.getBoolean(read.invoke(null,extras,true)));
        try{read.invoke(null,extras,false);throw new AssertionError("Release accepted cache override");}catch(InvocationTargetException e){check(e.getCause() instanceof IllegalArgumentException);}
        extras.putBoolean("test_cached_camera_vp",false);check(!enabled.getBoolean(read.invoke(null,extras,true)));
        extras.putString("test_cached_camera_vp","true");try{read.invoke(null,extras,true);throw new AssertionError("Accepted String");}catch(InvocationTargetException e){check(e.getCause() instanceof IllegalArgumentException);}
        InterlaceRenderer renderer=new InterlaceRenderer(20,.3f,true);renderer.setRuntimeMode(true);
        check(renderer.runtimeStatus().getString("camera_vp_requested").equals("per_frame"));
        check(renderer.runtimeStatus().getString("camera_vp_actual").equals("uninitialized"));renderer.setCachedCameraVp(true);
        check(renderer.runtimeStatus().getString("camera_vp_requested").equals("cached"));
        check(renderer.runtimeStatus().getString("camera_vp_actual").equals("uninitialized"));
        Field initialized=InterlaceRenderer.class.getDeclaredField("surfaceInitialized");initialized.setAccessible(true);initialized.setBoolean(renderer,true);
        try{renderer.setCachedCameraVp(false);throw new AssertionError("Changed cache mode after init");}catch(IllegalStateException expected){checks++;}
        JSONObject gate=AvatarCameraProjectionCheck.matrixGate();check(gate.getBoolean("passed"));check(gate.getLong("bit_mismatches")==0);
        check(gate.getLong("compared_float_values")==7200);check(gate.getJSONArray("cases").length()==25);check(gate.getInt("invalidation_checks")==25);
        java.nio.ByteBuffer a=java.nio.ByteBuffer.allocateDirect(400),b=java.nio.ByteBuffer.allocateDirect(400);
        for(int i=0;i<100;i++){a.putInt(i*4,0x503020ff);b.putInt(i*4,0x503020ff);}
        check(AvatarCameraProjectionCheck.pixelsMatch(AvatarPixelComparison.compare(a,b,10,10)));
        b.put(0,(byte)0x51);var one=AvatarPixelComparison.compare(a,b,10,10);check(one.passed());check(!AvatarCameraProjectionCheck.pixelsMatch(one));
        b.put(0,a.get(0));b.put(3,(byte)254);check(!AvatarCameraProjectionCheck.pixelsMatch(AvatarPixelComparison.compare(a,b,10,10)));
        System.out.println("CameraVpConfigTest: "+checks+" checks passed; Matrix is a host fixture, device native bit/pixel gate pending");
    }
}
