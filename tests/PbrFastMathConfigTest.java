package com.mirror.bench;

import android.os.Bundle;
import java.lang.reflect.*;

/** Actual built InputOptions contract; this does not emulate a GPU. */
public final class PbrFastMathConfigTest {
    private static int checks;
    private static Method read;
    private static Field flag;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    private static Object parsed(Bundle bundle,boolean debug)throws Exception{return read.invoke(null,bundle,debug,16);}
    private static boolean enabled(Bundle bundle,boolean debug)throws Exception{return flag.getBoolean(parsed(bundle,debug));}
    private static void rejected(Bundle bundle,boolean debug)throws Exception{
        try{parsed(bundle,debug);throw new AssertionError("Invalid PBR fast-math override accepted");}
        catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalArgumentException);}
    }
    public static void main(String[] args)throws Exception{
        Class<?> type=Class.forName("com.mirror.bench.MirrorActivity$InputOptions");
        read=type.getDeclaredMethod("read",Bundle.class,boolean.class,int.class);read.setAccessible(true);
        flag=type.getDeclaredField("pbrFastMath");flag.setAccessible(true);
        check(!enabled(null,true));check(!enabled(null,false));
        check(!enabled(new Bundle(),true));check(!enabled(new Bundle(),false));
        Bundle bundle=new Bundle();bundle.putString("product_action","scene");check(!enabled(bundle,true));
        for(boolean value:new boolean[]{false,true}){
            bundle.putBoolean("test_pbr_fast_math",value);check(enabled(bundle,true)==value);rejected(bundle,false);
        }
        bundle.putString("test_pbr_fast_math",null);rejected(bundle,true);rejected(bundle,false);
        bundle.putString("test_pbr_fast_math","true");rejected(bundle,true);
        bundle.putInt("test_pbr_fast_math",1);rejected(bundle,true);
        bundle.putLong("test_pbr_fast_math",1L);rejected(bundle,true);
        bundle.putFloat("test_pbr_fast_math",1f);rejected(bundle,true);
        Bundle clean=new Bundle();clean.putBoolean("test_pbr_fast_math",true);Object opts=parsed(clean,true);
        for(String name:new String[]{"persistentFbos","npuBlendshapes"}){
            Field field=type.getDeclaredField(name);field.setAccessible(true);check(field.getBoolean(opts));
        }
        Field views=type.getDeclaredField("viewCount");views.setAccessible(true);check(views.getInt(opts)==16);
        InterlaceRenderer renderer=new InterlaceRenderer(16,.3f,true);renderer.setRuntimeMode(true);
        check(!renderer.runtimeStatus().getBoolean("pbr_fast_math_requested"));
        Method setter=InterlaceRenderer.class.getDeclaredMethod("setPbrFastMath",boolean.class);setter.setAccessible(true);
        setter.invoke(renderer,true);check(renderer.runtimeStatus().getBoolean("pbr_fast_math_requested"));
        Field scene=InterlaceRenderer.class.getDeclaredField("avatarScene");scene.setAccessible(true);check(scene.get(renderer)==null);
        Field init=InterlaceRenderer.class.getDeclaredField("surfaceInitialized");init.setAccessible(true);init.setBoolean(renderer,true);
        try{setter.invoke(renderer,false);throw new AssertionError("Changed upload mode after GL initialization");}
        catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalStateException);}
        System.out.println("PbrFastMathConfigTest: "+checks+" checks passed; actual options/renderer, no GPU evidence");
    }
}
