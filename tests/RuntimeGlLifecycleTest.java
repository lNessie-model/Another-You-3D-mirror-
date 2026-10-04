package com.mirror.bench;

import android.os.Bundle;
import java.lang.reflect.*;

/** Strict real InputOptions parsing + deterministic late-frame lifecycle order, no host EGL. */
public final class RuntimeGlLifecycleTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    private static Method read;
    private static Field flag;
    private static boolean parsed(Bundle value,boolean debug)throws Exception{return flag.getBoolean(read.invoke(null,value,debug));}
    private static void rejected(Bundle value,boolean debug)throws Exception {
        try{parsed(value,debug);throw new AssertionError("invalid flag accepted");}
        catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalArgumentException);}
    }
    public static void main(String[] args)throws Exception {
        Class<?> options=Class.forName("com.mirror.bench.MirrorActivity$InputOptions");
        read=options.getDeclaredMethod("read",Bundle.class,boolean.class);read.setAccessible(true);
        flag=options.getDeclaredField("releaseGlOnPause");flag.setAccessible(true);
        check(!parsed(null,true));check(!parsed(null,false));check(!parsed(new Bundle(),false));
        Bundle b=new Bundle();b.putString("unrelated","anything");check(!parsed(b,true));
        for(boolean v:new boolean[]{false,true}){b.putBoolean("test_release_gl_on_pause",v);check(parsed(b,true)==v);rejected(b,false);}
        b.putString("test_release_gl_on_pause",null);rejected(b,true);rejected(b,false);
        b.putString("test_release_gl_on_pause","true");rejected(b,true);
        b.putInt("test_release_gl_on_pause",1);rejected(b,true);
        b.putLong("test_release_gl_on_pause",1L);rejected(b,true);
        b.putFloat("test_release_gl_on_pause",1f);rejected(b,true);
        RuntimeGlLifecycle gate=new RuntimeGlLifecycle();
        check(gate.snapshot().contextGeneration()==0&&!gate.snapshot().ready());
        gate.successfulFrame(gate.frameEpoch());check(!gate.snapshot().ready());
        check(gate.contextCreated()==1);long first=gate.frameEpoch();
        check(!gate.snapshot().ready()&&gate.snapshot().frameGeneration()==0);
        gate.successfulFrame(first);check(gate.snapshot().ready()&&gate.snapshot().frameGeneration()==1);
        // A frame entered before HOME cannot resurrect readiness after UI invalidation.
        gate.invalidate();gate.successfulFrame(first);check(!gate.snapshot().ready());
        long paused=gate.frameEpoch();check(gate.contextCreated()==2);
        gate.successfulFrame(paused);check(!gate.snapshot().ready()&&gate.snapshot().frameGeneration()==0);
        long second=gate.frameEpoch();gate.successfulFrame(second);
        check(gate.snapshot().contextGeneration()==2&&gate.snapshot().frameGeneration()==2&&gate.snapshot().ready());
        // Resume/resize can invalidate without a context change (default preservation).
        gate.invalidate();check(gate.snapshot().contextGeneration()==2&&!gate.snapshot().ready());
        gate.successfulFrame(second);check(!gate.snapshot().ready());
        gate.successfulFrame(gate.frameEpoch());check(gate.snapshot().ready()&&gate.snapshot().frameGeneration()==2);
        RuntimeGlLifecycle.Snapshot stable=gate.snapshot();
        gate.successfulFrame(gate.frameEpoch());check(stable==gate.snapshot()); // no per-frame allocation
        InterlaceRenderer renderer=new InterlaceRenderer(16,.3f,true);renderer.setRuntimeMode(true);
        var initial=renderer.runtimeStatus();check(initial.getLong("context_generation")==0);
        check(initial.getLong("frame_generation")==0&&!initial.getBoolean("runtime_gl_frame_ready"));
        // Exercise actual renderer publication with its lifecycle gate without any native GL calls.
        Field state=InterlaceRenderer.class.getDeclaredField("runtimeGlLifecycle");state.setAccessible(true);
        RuntimeGlLifecycle real=(RuntimeGlLifecycle)state.get(renderer);real.contextCreated();
        real.successfulFrame(real.frameEpoch());check(renderer.hasRuntimeFrame());
        renderer.pauseRuntimeAvatar();check(!renderer.hasRuntimeFrame());
        real.contextCreated();real.successfulFrame(real.frameEpoch());
        check(renderer.runtimeStatus().getLong("frame_generation")==2);
        Field error=InterlaceRenderer.class.getDeclaredField("error");error.setAccessible(true);error.set(renderer,"fixture GL error");
        check(!renderer.hasRuntimeFrame());check(!renderer.runtimeStatus().getBoolean("runtime_gl_frame_ready"));
        System.out.println("RuntimeGlLifecycleTest: "+checks+" checks passed; host does not simulate EGL");
    }
}
