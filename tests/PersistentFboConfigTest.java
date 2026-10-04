package com.mirror.bench;

import android.os.Bundle;
import java.lang.reflect.*;

/** Actual Activity option parser and renderer config/status; deliberately no GL lifecycle calls. */
public final class PersistentFboConfigTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    public static void main(String[] args)throws Exception {
        Class<?> type=Class.forName("com.mirror.bench.MirrorActivity$InputOptions");
        Method read=type.getDeclaredMethod("read",Bundle.class,boolean.class);read.setAccessible(true);
        Field enabled=type.getDeclaredField("persistentFbos");enabled.setAccessible(true);
        // Product defaults were already persistent before the background-cache experiment (669287b).
        check(enabled.getBoolean(read.invoke(null,null,true)));
        check(enabled.getBoolean(read.invoke(null,null,false)));
        check(enabled.getBoolean(read.invoke(null,new Bundle(),false)));
        Bundle extras=new Bundle();extras.putBoolean("test_persistent_fbos",true);check(enabled.getBoolean(read.invoke(null,extras,true)));
        try{read.invoke(null,extras,false);throw new AssertionError("Release accepted override");}catch(InvocationTargetException e){check(e.getCause() instanceof IllegalArgumentException);}
        extras.putBoolean("test_persistent_fbos",false);check(!enabled.getBoolean(read.invoke(null,extras,true)));
        extras.putString("test_persistent_fbos","true");try{read.invoke(null,extras,true);throw new AssertionError("String accepted");}catch(InvocationTargetException e){check(e.getCause() instanceof IllegalArgumentException);}
        InterlaceRenderer renderer=new InterlaceRenderer(20,.3f,true);renderer.setRuntimeMode(true);
        check(renderer.runtimeStatus().getString("multiview_fbo_requested").equals("legacy"));
        check(renderer.runtimeStatus().getString("multiview_fbo_actual").equals("uninitialized"));
        renderer.setPersistentMultiviewFbos(true);
        check(renderer.runtimeStatus().getString("multiview_fbo_requested").equals("persistent_groups"));
        check(renderer.runtimeStatus().getString("multiview_fbo_actual").equals("uninitialized"));
        check(renderer.runtimeStatus().getInt("persistent_fbo_count")==0);
        Field initialized=InterlaceRenderer.class.getDeclaredField("surfaceInitialized");initialized.setAccessible(true);initialized.setBoolean(renderer,true);
        try{renderer.setPersistentMultiviewFbos(false);throw new AssertionError("Changed backend after GL init");}catch(IllegalStateException expected){checks++;}
        java.nio.ByteBuffer a=java.nio.ByteBuffer.allocateDirect(400),b=java.nio.ByteBuffer.allocateDirect(400);
        for(int i=0;i<100;i++){a.putInt(i*4,0x503020ff);b.putInt(i*4,0x503020ff);}
        check(AvatarPersistentFboCheck.pixelsMatch(AvatarPixelComparison.compare(a,b,10,10)));
        b.put(0,(byte)0x51);AvatarPixelComparison.Stats oneByte=AvatarPixelComparison.compare(a,b,10,10);
        check(oneByte.passed());check(!AvatarPersistentFboCheck.pixelsMatch(oneByte));
        b.put(0,a.get(0));b.put(3,(byte)254);check(!AvatarPersistentFboCheck.pixelsMatch(AvatarPixelComparison.compare(a,b,10,10)));
        System.out.println("PersistentFboConfigTest: "+checks+" checks passed");
    }
}
