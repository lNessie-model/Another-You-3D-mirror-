package com.mirror.bench;

import android.opengl.GLES30;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import sun.misc.Unsafe;

/** Executes production Program/Comparison ownership against a GL API boundary fake.
 * Unsafe only bypasses unrelated geometry/EGL initialization. No source extraction or shader emulation. */
public final class AvatarPbrProgramOwnershipTest {
    private static int checks;
    private static final Unsafe U=unsafe();
    public static void main(String[] args)throws Exception {
        for(boolean initial:new boolean[]{false,true})successful(initial);
        for(int failure=1;failure<=8;failure++)partial(failure,false);
        for(int failure=1;failure<=4;failure++)partial(failure,true);
        cleanupFailure();
        System.out.println("AvatarPbrProgramOwnershipTest: "+checks+" checks; actual production program/selection/cleanup, boundary GL only; no pixels");
    }
    private static void successful(boolean initial)throws Exception {
        Fixture f=fixture(initial);Object c=initialize(f);
        check(GLES30.programs.size()==8&&GLES30.shaders.isEmpty(),"four defaults plus four alternative programs, shader cleanup");
        for(boolean fast:new boolean[]{false,true})for(boolean batched:new boolean[]{false,true})for(int count:new int[]{1,4}){
            set(f.scene,"pbrFastMathSelected",fast);f.batch.selectPbrVariant(fast);
            Object selected=batched?call(f.batch,"selectedProgram",new Class[]{int.class},count):call(f.scene,"selectedPbrProgram",new Class[]{int.class},count);
            int id=(int)get(selected,"id");GLES30.glUseProgram(id);
            call(c,"verifyBound",new Class[]{int.class,boolean.class,boolean.class},count,fast,batched);
            check(f.defaults.contains(id)==(fast==initial),"selected actual default/alternate identity");
        }
        var s=(org.json.JSONObject)call(c,"status",new Class[0]);
        for(String key:List.of("reference_individual_binding_checks","candidate_individual_binding_checks","reference_batched_binding_checks","candidate_batched_binding_checks"))check(s.getLong(key)==2,"four separate actual bindings "+key);
        check(!s.getString("reference_batched_fragment_sha256").equals(s.getString("candidate_batched_fragment_sha256")),"batch actual sources distinct");
        GLES30.current=0;
        reject(()->call(c,"verifyBound",new Class[]{int.class,boolean.class,boolean.class},4,true,true),"mismatch must fail");
        check(((org.json.JSONObject)call(c,"status",new Class[0])).getLong("candidate_batched_binding_checks")==2,"mismatch never increments success");
        AtomicReference<Throwable> wrong=new AtomicReference<>();Thread thread=new Thread(()->{try{call(c,"close",new Class[0]);}catch(Throwable e){wrong.set(e);}});thread.start();thread.join();
        check(wrong.get() instanceof IllegalStateException&&GLES30.programs.size()==8,"foreign thread cannot free programs");
        call(c,"close",new Class[0]);call(c,"close",new Class[0]);
        check(GLES30.programs.equals(f.defaults)&&GLES30.deleted.size()==4,"close alternative four once, preserve defaults");
        check((boolean)get(f.scene,"pbrFastMathSelected")==initial,"scene original policy restored");
        check(f.batch.selectedPbrProgramId(1)==f.batchDefault,"batch original policy restored");
        reject(()->call(c,"verifyBound",new Class[]{int.class,boolean.class,boolean.class},1,initial,false),"closed guard");
        set(f.scene,"batch",null);check(((org.json.JSONObject)call(c,"status",new Class[0])).getBoolean("closed"),"closed status survives owner batch release");
    }
    private static void partial(int at,boolean link)throws Exception {
        Fixture f=fixture(false);if(link)GLES30.failLink=GLES30.linkCalls+at;else GLES30.failCompile=GLES30.compileCalls+at;
        Object c=comparison(f.scene);
        reject(()->call(c,"initialize",new Class[0]),"injected construction failure");
        call(c,"close",new Class[0]);
        check(GLES30.programs.equals(f.defaults)&&GLES30.shaders.isEmpty(),"partial program failure releases all owned resources");
    }
    private static void cleanupFailure()throws Exception {
        Fixture f=fixture(false);Object c=initialize(f);
        GLES30.failDelete=f.batch.selectedPbrProgramId(1); // currently default; choose alternative explicitly
        f.batch.selectPbrVariant(true);GLES30.failDelete=f.batch.selectedPbrProgramId(1);
        reject(()->call(c,"close",new Class[0]),"cleanup error retained");
        check(GLES30.programs.equals(f.defaults)&&GLES30.deleted.size()==4,"delete failure still attempts all four");
        call(c,"close",new Class[0]);check(GLES30.deleted.size()==4,"failed close remains detached and idempotent");
    }
    private record Fixture(AvatarGpuScene scene,AvatarBatchGpu batch,Set<Integer> defaults,int batchDefault){}
    private static Fixture fixture(boolean initial)throws Exception {
        GLES30.reset();AvatarGpuScene scene=(AvatarGpuScene)U.allocateInstance(AvatarGpuScene.class);
        AvatarBatchGpu batch=(AvatarBatchGpu)U.allocateInstance(AvatarBatchGpu.class);
        set(scene,"pbrFastMathRequested",initial);set(scene,"pbrFastMathSelected",initial);set(scene,"batch",batch);
        set(batch,"pbrFastMath",initial);set(batch,"selectedFastMath",initial);set(batch,"atlas",true);set(batch,"pbr",true);
        var primitive=new AvatarAsset.Primitive(new float[]{0,0,0,1,0,0,0,1,0},null,null,null,new int[]{0,1,2},List.of(),0);
        var mesh=new AvatarAsset.Mesh("one",List.of(primitive),List.of(),new float[0]);
        float[] identity={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
        var node=new AvatarAsset.Node("one",0,new int[0],identity,identity,null);
        var asset=new AvatarAsset(List.of(mesh),List.of(node),List.of(new AvatarAsset.Material("one",new float[]{1,1,1,1},1,false)),new int[]{0},3,1,0);
        set(batch,"layout",new AvatarBatchLayout(asset,new boolean[]{true}));
        set(scene,"program",program("AvatarGpuScene",false,initial));set(scene,"multiviewProgram",program("AvatarGpuScene",true,initial));
        set(batch,"single",program("AvatarBatchGpu",false,initial));set(batch,"multiview",program("AvatarBatchGpu",true,initial));
        return new Fixture(scene,batch,Set.copyOf(GLES30.programs),batch.selectedPbrProgramId(1));
    }
    private static Object initialize(Fixture f)throws Exception {Object c=comparison(f.scene);call(c,"initialize",new Class[0]);set(f.scene,"pbrComparison",c);return c;}
    private static Object comparison(AvatarGpuScene s)throws Exception {var c=Class.forName("com.mirror.bench.AvatarGpuScene$PbrComparison").getDeclaredConstructor(AvatarGpuScene.class);c.setAccessible(true);return c.newInstance(s);}
    private static Object program(String owner,boolean multi,boolean fast)throws Exception {
        Class<?> type=Class.forName("com.mirror.bench."+owner+"$Program");
        var c=owner.equals("AvatarGpuScene")?type.getDeclaredConstructor(boolean.class,boolean.class,boolean.class,boolean.class):type.getDeclaredConstructor(boolean.class,int.class,boolean.class,boolean.class,boolean.class);c.setAccessible(true);
        return owner.equals("AvatarGpuScene")?c.newInstance(multi,true,true,fast):c.newInstance(multi,1,true,true,fast);
    }
    private static Object call(Object o,String name,Class<?>[] types,Object...args)throws Exception {
        var m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);
        try{return m.invoke(o,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception x)throw x;if(e.getCause() instanceof Error x)throw x;throw e;}
    }
    private static Object get(Object o,String n)throws Exception {var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String n,Object v)throws Exception {var f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
    private interface Run{void run()throws Exception;}
    private static void reject(Run r,String label)throws Exception {try{r.run();throw new AssertionError("expected "+label);}catch(IllegalStateException expected){checks++;}}
    private static void check(boolean v,String label){if(!v)throw new AssertionError(label);checks++;}
    private static Unsafe unsafe(){try{Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);return (Unsafe)f.get(null);}catch(Exception e){throw new AssertionError(e);}}
}
