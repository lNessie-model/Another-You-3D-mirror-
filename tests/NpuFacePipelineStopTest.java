package com.mirror.bench;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Actual pipeline drain/close with real JDK executors; native graphs are explicit substitutes. */
public final class NpuFacePipelineStopTest {
    private static int checks;
    private static synchronized void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    private static void set(Object owner,String name,Object value)throws Exception{
        Field field=NpuFacePipeline.class.getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    private static Object get(Object owner,String name)throws Exception{
        Field field=NpuFacePipeline.class.getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    private static NpuFacePipeline pipeline(List<String> calls,Throwable postFailure)throws Exception{
        Field field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        NpuFacePipeline p=(NpuFacePipeline)((sun.misc.Unsafe)field.get(null)).allocateInstance(NpuFacePipeline.class);
        Class<?> post=Class.forName("com.mirror.bench.NpuFacePipeline$PostProcessor");
        Object fake=Proxy.newProxyInstance(post.getClassLoader(),new Class<?>[]{post},(owner,method,args)->{
            if(method.getName().equals("close")){calls.add("post");if(postFailure!=null)throw postFailure;return null;}
            throw new AssertionError("No post inference in close checks");
        });
        set(p,"post",fake);set(p,"mesh",new RknnModel("mesh",calls));set(p,"detector",new RknnModel("detector",calls));return p;
    }
    private static void join(Thread thread)throws Exception{thread.join(2000);check(!thread.isAlive());}
    private static Throwable closeFailure(NpuFacePipeline p){
        try{p.close();}catch(RuntimeException|Error failure){return failure;}
        throw new AssertionError("close failure was erased");
    }
    private static void waitsForExecutorTermination()throws Exception{
        List<String> calls=new CopyOnWriteArrayList<>();NpuFacePipeline p=pipeline(calls,null);
        CountDownLatch afterEntered=new CountDownLatch(1),afterRelease=new CountDownLatch(1),closeReturned=new CountDownLatch(1);
        ThreadPoolExecutor cpu=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new LinkedBlockingQueue<>()){
            @Override protected void afterExecute(Runnable job,Throwable failure){
                afterEntered.countDown();boolean interrupted=false;
                for(;;)try{afterRelease.await();break;}catch(InterruptedException ignored){interrupted=true;}
                if(interrupted)Thread.currentThread().interrupt();
            }
        };
        Future<?> pending=cpu.submit(()->{});set(p,"postWorker",cpu);set(p,"pendingPost",pending);
        check(afterEntered.await(2,TimeUnit.SECONDS));check(pending.isDone()&&!cpu.isTerminated());
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread closer=new Thread(()->{
            Thread.currentThread().interrupt();
            try{p.close();check(Thread.currentThread().isInterrupted());}
            catch(Throwable error){failure.set(error);}finally{closeReturned.countDown();}
        });
        closer.start();
        try{
            check(!closeReturned.await(40,TimeUnit.MILLISECONDS));check(calls.isEmpty());
        }finally{afterRelease.countDown();join(closer);cpu.shutdown();check(cpu.awaitTermination(2,TimeUnit.SECONDS));}
        check(failure.get()==null);check(cpu.isTerminated());check(calls.equals(List.of("post","mesh","detector")));
        check(get(p,"postWorker")==null&&get(p,"pendingPost")==null);
        check(p.closeStatus().requested()&&p.closeStatus().sequenceCompleted()&&p.closeStatus().postExecutorTerminated());
        check(p.closeStatus().failureType().isEmpty());
        p.close();check(calls.size()==3);
        for(int call=0;call<4;call++){
            try{
                if(call==0)p.detect(null,1,true);else if(call==1)p.submit(null,1,true,ignored->{});
                else if(call==2)p.beginMeasurement();else p.summary();
                throw new AssertionError("closed pipeline admitted work");
            }catch(IllegalStateException expected){checks++;}
        }
    }
    private static void failuresKeepFirstAndContinue()throws Exception{
        List<String> calls=new ArrayList<>();IllegalStateException postFailure=new IllegalStateException("post-close");
        NpuFacePipeline p=pipeline(calls,postFailure);
        AssertionError meshFailure=new AssertionError("mesh-close");((RknnModel)get(p,"mesh")).failure=meshFailure;
        IllegalStateException detectorFailure=new IllegalStateException("detector-close");((RknnModel)get(p,"detector")).failure=detectorFailure;
        Throwable caught=null;try{p.close();}catch(Throwable error){caught=error;}
        check(caught==postFailure);check(calls.equals(List.of("post","mesh","detector")));
        check(Arrays.asList(caught.getSuppressed()).containsAll(List.of(meshFailure,detectorFailure)));
        check(get(p,"post")==null&&get(p,"mesh")==null&&get(p,"detector")==null);
        check(closeFailure(p)==caught&&calls.size()==3);
        check(p.closeStatus().sequenceCompleted()&&p.closeStatus().postExecutorTerminated());
        check(p.closeStatus().failureType().equals("IllegalStateException"));
    }
    private static final class ShutdownFailure extends AbstractExecutorService {
        private final ExecutorService delegate=Executors.newSingleThreadExecutor();
        final RuntimeException failure=new IllegalStateException("executor shutdown denied");
        final boolean completesBeforeThrow;
        ShutdownFailure(boolean completesBeforeThrow){this.completesBeforeThrow=completesBeforeThrow;}
        public void execute(Runnable work){delegate.execute(work);}
        public void shutdown(){if(completesBeforeThrow)delegate.shutdown();throw failure;}
        public List<Runnable> shutdownNow(){return delegate.shutdownNow();}
        public boolean isShutdown(){return delegate.isShutdown();}
        public boolean isTerminated(){return delegate.isTerminated();}
        public boolean awaitTermination(long duration,TimeUnit unit)throws InterruptedException{return delegate.awaitTermination(duration,unit);}
    }
    private static void shutdownFailuresRetainLiveNativeOwnership()throws Exception{
        for(boolean completed:new boolean[]{false,true}){
            List<String> calls=new CopyOnWriteArrayList<>();NpuFacePipeline p=pipeline(calls,null);
            ShutdownFailure cpu=new ShutdownFailure(completed);Future<?> pending=cpu.submit(()->{});
            pending.get(2,TimeUnit.SECONDS);set(p,"postWorker",cpu);set(p,"pendingPost",pending);
            try{
                check(closeFailure(p)==cpu.failure);
                check(p.closeStatus().requested()&&p.closeStatus().sequenceCompleted()==completed);
                check(p.closeStatus().postExecutorTerminated()==completed);
                if(completed){check(calls.equals(List.of("post","mesh","detector")));check(get(p,"postWorker")==null);}
                else{check(calls.isEmpty());check(get(p,"post")!=null&&get(p,"mesh")!=null&&get(p,"detector")!=null);}
                check(closeFailure(p)==cpu.failure);check(calls.size()==(completed?3:0));
                RuntimeInputStop stop=new RuntimeInputStop(new RuntimeStatusOrder().begin(1),2);
                check(stop.close(RuntimeInputStop.Resource.NPU,p)==cpu.failure);
                var receipt=stop.seal(3);RuntimeInputStop.FailureLatch latch=new RuntimeInputStop.FailureLatch();latch.record(receipt);
                check(latch.blocked()&&!receipt.closeCallsSucceeded());
                check(receipt.failures(RuntimeInputStop.Resource.NPU)==1);
            }finally{cpu.shutdownNow();check(cpu.awaitTermination(2,TimeUnit.SECONDS));}
        }
    }
    private static void cancelledFutureWaitsForLiveTask()throws Exception{
        List<String> calls=new CopyOnWriteArrayList<>();NpuFacePipeline p=pipeline(calls,null);
        ExecutorService cpu=Executors.newSingleThreadExecutor();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),returned=new CountDownLatch(1);
        Future<?> pending=cpu.submit(()->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new IllegalStateException(e);}});
        check(entered.await(2,TimeUnit.SECONDS));check(pending.cancel(false));
        set(p,"postWorker",cpu);set(p,"pendingPost",pending);AtomicReference<Throwable> error=new AtomicReference<>();
        Thread closer=new Thread(()->{try{p.close();}catch(Throwable failure){error.set(failure);}finally{returned.countDown();}});
        closer.start();
        try{check(!returned.await(40,TimeUnit.MILLISECONDS));check(calls.isEmpty());}
        finally{release.countDown();join(closer);cpu.shutdown();check(cpu.awaitTermination(2,TimeUnit.SECONDS));}
        check(error.get() instanceof CancellationException);check(calls.equals(List.of("post","mesh","detector")));
        check(p.closeStatus().sequenceCompleted()&&p.closeStatus().postExecutorTerminated());
        check(closeFailure(p)==error.get());
    }
    private static void failedTaskKeepsPrimary()throws Exception{
        List<String> calls=new CopyOnWriteArrayList<>();IllegalStateException graphFailure=new IllegalStateException("native post close");
        NpuFacePipeline p=pipeline(calls,graphFailure);ExecutorService cpu=Executors.newSingleThreadExecutor();
        AssertionError taskFailure=new AssertionError("face task failed");Future<?> pending=cpu.submit(()->{throw taskFailure;});
        try{pending.get(2,TimeUnit.SECONDS);}catch(ExecutionException expected){check(expected.getCause()==taskFailure);}
        set(p,"postWorker",cpu);set(p,"pendingPost",pending);
        try{
            Throwable failure=closeFailure(p);check(failure instanceof IllegalStateException&&failure.getCause()==taskFailure);
            check(Arrays.asList(failure.getSuppressed()).contains(graphFailure));check(cpu.isTerminated());
            check(calls.equals(List.of("post","mesh","detector")));check(closeFailure(p)==failure);
        }finally{cpu.shutdownNow();check(cpu.awaitTermination(2,TimeUnit.SECONDS));}
    }
    private static void incompleteCloseNotSuccess()throws Exception{
        List<String> calls=new ArrayList<>();NpuFacePipeline p=pipeline(calls,null);
        // State left by an abnormal exit before closeFailure/completion could be published.
        set(p,"closeRequested",true);
        check(closeFailure(p) instanceof IllegalStateException);check(calls.isEmpty());
        check(!p.closeStatus().sequenceCompleted());
    }
    private static void anonymousFailureRemainsExplicit()throws Exception{
        List<String> calls=new ArrayList<>();RuntimeException anonymous=new RuntimeException(){};
        NpuFacePipeline p=pipeline(calls,anonymous);check(!p.closeStatus().failed());
        check(closeFailure(p)==anonymous);check(p.closeStatus().failed());
        check(p.closeStatus().failureType().isEmpty()&&p.closeStatus().sequenceCompleted());
    }
    public static void main(String[] args)throws Exception{
        waitsForExecutorTermination();failuresKeepFirstAndContinue();shutdownFailuresRetainLiveNativeOwnership();
        cancelledFutureWaitsForLiveTask();failedTaskKeepsPrimary();incompleteCloseNotSuccess();anonymousFailureRemainsExplicit();
        System.out.println("NpuFacePipeline stop checks: "+checks+"; actual drain/close and real executors; no RKNN/MediaPipe JNI/ADB qualification");
    }
}
