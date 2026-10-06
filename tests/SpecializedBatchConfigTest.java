package com.mirror.bench;

import android.os.Bundle;
import java.lang.reflect.*;

/** Actual built InputOptions contract; this does not emulate a GPU. */
public final class SpecializedBatchConfigTest {
    private static int checks;
    private static Method read;
    private static Field flag;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    private static Object parsed(Bundle bundle,boolean debug)throws Exception{return read.invoke(null,bundle,debug,16);}
    private static boolean enabled(Bundle bundle,boolean debug)throws Exception{return flag.getBoolean(parsed(bundle,debug));}
    private static boolean primary(Bundle bundle,boolean debug)throws Exception{
        Field field=parsed(bundle,debug).getClass().getDeclaredField("constantWhitePrimary");field.setAccessible(true);
        return field.getBoolean(parsed(bundle,debug));
    }
    private static void rejected(Bundle bundle,boolean debug)throws Exception{
        try{parsed(bundle,debug);throw new AssertionError("Invalid Specialized batch override accepted");}
        catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalArgumentException);}
    }
    public static void main(String[] args)throws Exception{
        Class<?> type=Class.forName("com.mirror.bench.MirrorActivity$InputOptions");
        read=type.getDeclaredMethod("read",Bundle.class,boolean.class,int.class);read.setAccessible(true);
        flag=type.getDeclaredField("specializedBatch");flag.setAccessible(true);
        check(!enabled(null,true));check(!enabled(null,false));
        check(!enabled(new Bundle(),true));check(!enabled(new Bundle(),false));
        Bundle bundle=new Bundle();bundle.putString("product_action","scene");check(!enabled(bundle,true));
        bundle.putBoolean("test_avatar_batched",true);
        for(boolean value:new boolean[]{false,true}){
            bundle.putBoolean("test_specialized_batch",value);check(enabled(bundle,true)==value);rejected(bundle,false);
        }
        bundle.putString("test_specialized_batch",null);rejected(bundle,true);rejected(bundle,false);
        bundle.putString("test_specialized_batch","true");rejected(bundle,true);
        bundle.putInt("test_specialized_batch",1);rejected(bundle,true);
        bundle.putLong("test_specialized_batch",1L);rejected(bundle,true);
        bundle.putFloat("test_specialized_batch",1f);rejected(bundle,true);
        Bundle clean=new Bundle();clean.putBoolean("test_avatar_batched",true);clean.putBoolean("test_specialized_batch",true);Object opts=parsed(clean,true);
        for(String name:new String[]{"persistentFbos","npuBlendshapes"}){
            Field field=type.getDeclaredField(name);field.setAccessible(true);check(field.getBoolean(opts));
        }
        Field views=type.getDeclaredField("viewCount");views.setAccessible(true);check(views.getInt(opts)==16);
        Bundle missing=new Bundle();missing.putBoolean("test_specialized_batch",true);rejected(missing,true);
        for(String incompatible:new String[]{"test_pbr_fast_math","test_orm_rg8","test_static_background_cache","test_empty_interlace"}){
            Bundle mixed=new Bundle();mixed.putBoolean("test_avatar_batched",true);mixed.putBoolean("test_specialized_batch",true);mixed.putBoolean(incompatible,true);rejected(mixed,true);
        }
        InterlaceRenderer renderer=new InterlaceRenderer(16,.3f,true);renderer.setRuntimeMode(true);
        check(!renderer.runtimeStatus().getBoolean("specialized_batch_requested"));
        Method setter=InterlaceRenderer.class.getDeclaredMethod("setSpecializedBatch",boolean.class);setter.setAccessible(true);
        setter.invoke(renderer,true);check(renderer.runtimeStatus().getBoolean("specialized_batch_requested"));
        Field scene=InterlaceRenderer.class.getDeclaredField("avatarScene");scene.setAccessible(true);check(scene.get(renderer)==null);
        Field init=InterlaceRenderer.class.getDeclaredField("surfaceInitialized");init.setAccessible(true);init.setBoolean(renderer,true);
        try{setter.invoke(renderer,false);throw new AssertionError("Changed upload mode after GL initialization");}
        catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalStateException);}
        check(!primary(null,true));check(!primary(null,false));check(!primary(new Bundle(),true));check(!primary(new Bundle(),false));
        Bundle color=new Bundle();color.putBoolean("test_avatar_batched",true);color.putBoolean("test_specialized_batch",true);
        for(boolean value:new boolean[]{false,true}){
            color.putBoolean("test_constant_white_primary",value);check(primary(color,true)==value);rejected(color,false);
        }
        color.putString("test_constant_white_primary",null);rejected(color,true);rejected(color,false);
        color.putString("test_constant_white_primary","true");rejected(color,true);
        color.putInt("test_constant_white_primary",1);rejected(color,true);
        color.putLong("test_constant_white_primary",1L);rejected(color,true);
        color.putFloat("test_constant_white_primary",1f);rejected(color,true);
        Bundle noSpecialized=new Bundle();noSpecialized.putBoolean("test_constant_white_primary",true);noSpecialized.putBoolean("test_avatar_batched",true);rejected(noSpecialized,true);
        noSpecialized.putBoolean("test_specialized_batch",false);rejected(noSpecialized,true);
        noSpecialized.putBoolean("test_specialized_batch",true);noSpecialized.putBoolean("test_avatar_batched",false);rejected(noSpecialized,true);
        Bundle explicitOff=new Bundle();explicitOff.putBoolean("test_constant_white_primary",false);check(!primary(explicitOff,true));
        for(String incompatible:new String[]{"test_pbr_fast_math","test_orm_rg8","test_static_background_cache","test_empty_interlace"}){
            Bundle mixed=new Bundle();mixed.putBoolean("test_avatar_batched",true);mixed.putBoolean("test_specialized_batch",true);
            mixed.putBoolean("test_constant_white_primary",true);mixed.putBoolean(incompatible,true);rejected(mixed,true);
        }
        InterlaceRenderer fresh=new InterlaceRenderer(16,.3f,true);fresh.setRuntimeMode(true);
        check(!fresh.runtimeStatus().getBoolean("constant_white_primary_requested"));
        try{fresh.setConstantWhitePrimary(true);throw new AssertionError("Primary mode without specialization accepted");}
        catch(IllegalArgumentException expected){check(true);}
        fresh.setSpecializedBatch(true);fresh.setConstantWhitePrimary(true);check(fresh.runtimeStatus().getBoolean("constant_white_primary_requested"));
        try{fresh.setSpecializedBatch(false);throw new AssertionError("Silently disabled required specialized path");}
        catch(IllegalArgumentException expected){check(fresh.runtimeStatus().getBoolean("specialized_batch_requested"));}
        check(scene.get(fresh)==null);init.setBoolean(fresh,true);
        try{fresh.setConstantWhitePrimary(false);throw new AssertionError("Primary mode changed after GL init");}
        catch(IllegalStateException expected){check(true);}
        System.out.println("SpecializedBatchConfigTest: "+checks+" checks passed; actual options/renderer, no GPU evidence");
    }
}
