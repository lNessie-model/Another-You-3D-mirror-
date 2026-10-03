package com.mirror.bench;

import android.os.Bundle;
import java.lang.reflect.*;

/** Strict actual diagnostic option parser and pre-GL status; no native graphics execution. */
public final class BackgroundCacheConfigTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void rejects(Method read,Bundle extras,boolean debug)throws Exception {
        try{read.invoke(null,extras,debug);throw new AssertionError("Accepted invalid background experiment");}
        catch(InvocationTargetException error){check(error.getCause() instanceof IllegalArgumentException,"Strict option rejection");}
    }
    public static void main(String[] args)throws Exception {
        Class<?> options=Class.forName("com.mirror.bench.MirrorActivity$InputOptions");
        Method read=options.getDeclaredMethod("read",Bundle.class,boolean.class);read.setAccessible(true);
        Field enabled=options.getDeclaredField("staticBackgroundCache");enabled.setAccessible(true);
        check(!enabled.getBoolean(read.invoke(null,null,true)),"Default experiment disabled");
        check(!enabled.getBoolean(read.invoke(null,new Bundle(),false)),"Release product default unchanged");
        Bundle extras=new Bundle();extras.putBoolean("test_static_background_cache",true);
        rejects(read,extras,true);extras.putBoolean("test_private_head",true);
        check(enabled.getBoolean(read.invoke(null,extras,true)),"Explicit private-head debug experiment enabled");
        rejects(read,extras,false);
        extras.putBoolean("test_avatar_batched",true);rejects(read,extras,true);extras.putBoolean("test_avatar_batched",false);
        extras.putString("test_static_background_cache","true");rejects(read,extras,true);
        extras.putString("test_static_background_cache",null);rejects(read,extras,true);
        extras.putBoolean("test_static_background_cache",false);check(!enabled.getBoolean(read.invoke(null,extras,true)),"Explicit false remains ordinary drawing");
        rejects(read,extras,false);
        InterlaceRenderer renderer=new InterlaceRenderer(20,.3f,true);renderer.setRuntimeMode(true);
        Method set=InterlaceRenderer.class.getDeclaredMethod("setStaticBackgroundCache",boolean.class);set.setAccessible(true);
        check(!renderer.runtimeStatus().getBoolean("static_background_cache_requested"),"Default renderer disabled");
        check(renderer.runtimeStatus().getLong("static_background_cache_bytes")==0,"No GPU allocation before initialization");
        set.invoke(renderer,true);check(renderer.runtimeStatus().getBoolean("static_background_cache_requested"),"Explicit requested flag visible");
        check(renderer.runtimeStatus().getString("static_background_cache_actual").equals("uninitialized"),"Request does not imply capture or device qualification");
        Field initialized=InterlaceRenderer.class.getDeclaredField("surfaceInitialized");initialized.setAccessible(true);initialized.setBoolean(renderer,true);
        try{set.invoke(renderer,false);throw new AssertionError("Changed cache backend after initialization");}
        catch(InvocationTargetException error){check(error.getCause() instanceof IllegalStateException,"Renderer backend fixed before GL initialization");}
        Method profile=AvatarMultiviewCheck.class.getDeclaredMethod("backgroundProfile",int.class);profile.setAccessible(true);
        for(int views:new int[]{16,20}){
            org.json.JSONObject p=(org.json.JSONObject)profile.invoke(null,views);
            check(p.getInt("views")==views&&p.getInt("expected_fixtures")==69,"Keep full pose regression for both requested view counts");
            check(p.getInt("expected_layer_comparisons")==69*views,"Every global view layer must be compared");
            check(p.getInt("view_width")==400&&p.getInt("view_height")==640,"Keep requested per-view resolution");
            check(p.getInt("max_rgb_error_allowed")==0&&p.getInt("alpha_error_allowed")==0,"Cache output gate requires exact RGBA");
            check(!p.getBoolean("performance_evidence"),"Pixel checks cannot claim simultaneous NPU/interlace frame rate");
            check(p.getBoolean("require_distinct_view_hashes"),"All requested views must have distinct output, beyond first/last variation");
        }
        try{profile.invoke(null,12);throw new AssertionError("Accepted wrong qualification profile");}
        catch(InvocationTargetException error){check(error.getCause() instanceof IllegalArgumentException,"Only explicit 16/20 profiles accepted");}
        Method previewRead=AvatarPreviewActivity.class.getDeclaredMethod("readBackgroundVerification",Bundle.class,boolean.class,boolean.class);previewRead.setAccessible(true);
        check(!(Boolean)previewRead.invoke(null,null,false,false),"Preview diagnostic disabled by default");
        Bundle verify=new Bundle();verify.putBoolean("verify_private_background_cache",true);
        check((Boolean)previewRead.invoke(null,verify,true,true),"Explicit debug private-head pixel gate enabled");
        for(int invalidCase=0;invalidCase<4;invalidCase++){
            if(invalidCase==2)verify.putString("verify_private_background_cache","true");
            if(invalidCase==3)verify.putString("verify_private_background_cache",null);
            try{previewRead.invoke(null,verify,invalidCase!=0,invalidCase!=1);throw new AssertionError("Accepted invalid preview cache option");}
            catch(InvocationTargetException error){check(error.getCause() instanceof IllegalArgumentException,"Strict preview diagnostic rejection");}
        }
        System.out.println("BackgroundCacheConfigTest: "+checks+" checks passed; actual option/status code, no EGL or lifecycle qualification");
    }
}
