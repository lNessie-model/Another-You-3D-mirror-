package com.mirror.bench;

import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Actual Activity cleanup/latch/JSON bridge compiled against Android SDK; no Activity or HAL run. */
public final class RuntimeInputStopIntegrationTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    private static RuntimeInputStop create(){return new RuntimeInputStop(new RuntimeStatusOrder().begin(1),2);}
    private static Method method(String name,Class<?>...types)throws Exception{
        Method result=MirrorActivity.class.getDeclaredMethod(name,types);result.setAccessible(true);return result;
    }
    private static void set(Object owner,String name,Object value)throws Exception{
        Field field=MirrorActivity.class.getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    private static Object get(Object owner,String name)throws Exception{
        Field field=MirrorActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    public static void main(String[] args)throws Exception{
        Method cleanup=method("closeRuntimeInputs",RuntimeInputStop.class,AutoCloseable.class,AutoCloseable.class,
                AutoCloseable.class,AutoCloseable.class,AutoCloseable.class,AutoCloseable.class);
        Method finish=method("finishRuntimeInputStop",RuntimeInputStop.class,java.util.function.LongSupplier.class);
        Method admitted=method("inputStartAllowed");
        Method nativeFailure=method("recordRuntimeNativeFailure",RuntimeInputStop.class,Throwable.class);
        Method json=method("inputStopReceiptJson",RuntimeInputStop.Receipt.class);
        check((Boolean)admitted.invoke(null));
        RuntimeInputStop clean=create();AtomicInteger closed=new AtomicInteger();
        AutoCloseable source=closed::incrementAndGet;clean.bindCamera(source);
        cleanup.invoke(null,clean,source,(AutoCloseable)closed::incrementAndGet,(AutoCloseable)closed::incrementAndGet,
                (AutoCloseable)closed::incrementAndGet,(AutoCloseable)closed::incrementAndGet,(AutoCloseable)closed::incrementAndGet);
        check(!clean.requestCameraClose());check(closed.get()==6);
        var passed=(RuntimeInputStop.Receipt)finish.invoke(null,clean,(java.util.function.LongSupplier)()->3L);
        check(passed.closeCallsSucceeded());check((Boolean)admitted.invoke(null));
        JSONObject value=(JSONObject)json.invoke(null,passed);
        check(value.getString("worker_id").equals(passed.workerId()));
        check(value.getString("epoch_id").equals(passed.epochId()));
        check(value.getLong("epoch_number")==1&&value.getLong("finished_elapsed_ns")==3);
        check(value.getBoolean("close_calls_succeeded")&&!value.getBoolean("hardware_qualified"));
        JSONObject rows=value.getJSONObject("resources");
        for(RuntimeInputStop.Resource kind:RuntimeInputStop.Resource.values()){
            JSONObject row=rows.getJSONObject(kind.name());
            check(row.getLong("attempts")==passed.attempts(kind));check(row.getLong("failures")==0);
        }
        RuntimeInputStop ordinary=create();nativeFailure.invoke(null,ordinary,new IllegalStateException("ordinary init"));
        check(!ordinary.hasFailures());
        RuntimeInputStop factory=create();OutOfMemoryError initFailure=new OutOfMemoryError("factory metadata");
        initFailure.addSuppressed(new NativeCleanupUnconfirmed("factory native destroy"));
        nativeFailure.invoke(null,factory,initFailure);
        check(factory.hasFailures());
        var factoryFailed=(RuntimeInputStop.Receipt)finish.invoke(null,factory,(java.util.function.LongSupplier)()->4L);
        check(!factoryFailed.closeCallsSucceeded()&&factoryFailed.attempts(RuntimeInputStop.Resource.NPU)==0
                &&factoryFailed.failures(RuntimeInputStop.Resource.NPU)==1);
        check(!(Boolean)admitted.invoke(null));
        RuntimeInputStop bad=create();closed.set(0);
        cleanup.invoke(null,bad,(AutoCloseable)()->{closed.incrementAndGet();throw new AssertionError("camera JNI failure");},
                (AutoCloseable)closed::incrementAndGet,(AutoCloseable)()->{closed.incrementAndGet();throw new IllegalStateException("sensitive message");},
                (AutoCloseable)closed::incrementAndGet,(AutoCloseable)closed::incrementAndGet,(AutoCloseable)closed::incrementAndGet);
        check(closed.get()==6);check(bad.hasFailures());
        var failed=(RuntimeInputStop.Receipt)finish.invoke(null,bad,(java.util.function.LongSupplier)()->4L);
        check(!failed.closeCallsSucceeded());check(!(Boolean)admitted.invoke(null));
        value=(JSONObject)json.invoke(null,failed);
        check(!value.getBoolean("close_calls_succeeded"));
        check(value.getJSONObject("resources").getJSONObject("NPU").getString("first_failure_type").equals("IllegalStateException"));
        check(value.getJSONObject("resources").getJSONObject("SOURCE").getString("first_failure_type").equals("AssertionError"));
        check(!value.toString().contains("sensitive message")&&!value.toString().contains("camera JNI failure"));
        RuntimeInputStop later=create();finish.invoke(null,later,(java.util.function.LongSupplier)()->5L);
        check(!(Boolean)admitted.invoke(null)); // A successful later receipt cannot reset the process latch.
        Field unsafeField=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
        sun.misc.Unsafe unsafe=(sun.misc.Unsafe)unsafeField.get(null);
        MirrorActivity activity=(MirrorActivity)unsafe.allocateInstance(MirrorActivity.class);
        // Unsafe bypasses field initializers; admit the real startup gate before exercising the input latch.
        MirrorStartupGate startup=new MirrorStartupGate();startup.markDiagnosticReady();set(activity,"avatarStartup",startup);
        set(activity,"resumed",true);set(activity,"configurationError","");set(activity,"reportedPreflightError","");
        set(activity,"interaction",new InteractionController());set(activity,"lastEventState",InteractionController.State.ERROR);
        Method start=method("startIfReady",boolean.class);
        start.invoke(activity,false);
        check(get(activity,"runtimeError").toString().contains("强制停止"));check(get(activity,"worker")==null);
        // Removing the preflight guard reaches uninitialized renderer/options and fails this test.
        var epoch=new RuntimeStatusOrder().begin(1);
        set(activity,"statusEpoch",epoch);set(activity,"main",new android.os.Handler(null));
        Class<?> workerClass=Class.forName("com.mirror.bench.MirrorActivity$RuntimeWorker");
        Constructor<?> constructor=workerClass.getDeclaredConstructor(MirrorActivity.class,RuntimeStatusOrder.Epoch.class);
        constructor.setAccessible(true);Thread worker=(Thread)constructor.newInstance(activity,epoch);
        AtomicReference<Throwable> threadFailure=new AtomicReference<>();
        worker.setUncaughtExceptionHandler((thread,problem)->threadFailure.set(problem));
        worker.start();worker.join(2000);
        check(!worker.isAlive()&&threadFailure.get()==null);
        check(get(activity,"runtimeError").toString().contains("强制停止"));
        check(android.os.Handler.posted.size()==1);
        JSONObject observation=(JSONObject)method("inputStopJson").invoke(activity);
        check(observation.getBoolean("failure_latched")&&observation.getBoolean("last_worker_thread_terminated"));
        check(observation.getString("last_worker_thread_state").equals("TERMINATED"));
        JSONObject last=observation.getJSONObject("last_worker_receipt");
        check(last.getString("epoch_id").equals(epoch.id()));
        for(RuntimeInputStop.Resource kind:RuntimeInputStop.Resource.values())
            check(last.getJSONObject("resources").getJSONObject(kind.name()).getLong("attempts")==0);
        // The actual worker's post-acquire guard rejected before any camera/native constructor.
        check(observation.getJSONObject("latched_failure_receipt").getString("worker_id").equals(factoryFailed.workerId()));
        System.out.println("Runtime input Activity bridge checks: "+checks+"; actual close ordering/latch/status, no Android lifecycle/HAL qualification");
    }
}
