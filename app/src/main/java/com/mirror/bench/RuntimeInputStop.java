package com.mirror.bench;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Input-worker cleanup only: returned close calls do not prove native/HAL/GL ownership release. */
final class RuntimeInputStop {
    enum Resource { SOURCE, REFERENCE, NPU, NORMALIZER, CONVERTER, REFERENCE_CONVERTER, ASYNC_CAMERA }
    @FunctionalInterface interface ThreadStarter { void start(Thread thread); }
    static final class Receipt {
        private final String workerId,epochId;
        private final long epochNumber,startedNs,finishedNs;
        private final long[] attempts,failures;
        private final String[] failureTypes;
        private final boolean asyncClaimed,asyncTerminated;
        private Receipt(RuntimeInputStop stop,long nowNs) {
            workerId=stop.workerId;epochId=stop.epoch.id();epochNumber=stop.epoch.number();
            startedNs=stop.startedNs;finishedNs=nowNs;attempts=stop.attempts.clone();
            failures=stop.failures.clone();failureTypes=stop.failureTypes.clone();
            asyncClaimed=stop.asyncClaimed;
            asyncTerminated=!asyncClaimed||(stop.asyncThread!=null&&stop.asyncThread.getState()==Thread.State.TERMINATED);
        }
        String workerId(){return workerId;} String epochId(){return epochId;}
        long epochNumber(){return epochNumber;} long startedNs(){return startedNs;} long finishedNs(){return finishedNs;}
        long attempts(Resource kind){return attempts[kind.ordinal()];}
        long failures(Resource kind){return failures[kind.ordinal()];}
        String failureType(Resource kind){return failureTypes[kind.ordinal()];}
        boolean asyncCameraClaimed(){return asyncClaimed;} boolean asyncCameraThreadTerminated(){return asyncTerminated;}
        boolean closeCallsSucceeded(){for(long n:failures)if(n!=0)return false;return asyncTerminated;}
    }
    /** Keep only immutable evidence, never an Activity, Thread, Throwable or native resource. No reset. */
    static final class FailureLatch {
        private final AtomicReference<Receipt> first=new AtomicReference<>();
        void record(Receipt receipt){if(!receipt.closeCallsSucceeded())first.compareAndSet(null,receipt);}
        boolean blocked(){return first.get()!=null;} Receipt failure(){return first.get();}
    }
    private final String workerId=UUID.randomUUID().toString();
    private final RuntimeStatusOrder.Epoch epoch;
    private final long startedNs;
    private final long[] attempts=new long[Resource.values().length],failures=new long[Resource.values().length];
    private final String[] failureTypes=new String[Resource.values().length];
    private final ThreadStarter starter;
    private final CountDownLatch asyncDone=new CountDownLatch(1);
    private AutoCloseable camera;
    private Thread asyncThread;
    private int closing;
    private boolean asyncClaimed,asyncFinished;
    private Receipt receipt;

    RuntimeInputStop(RuntimeStatusOrder.Epoch epoch,long startedNs){this(epoch,startedNs,Thread::start);}
    RuntimeInputStop(RuntimeStatusOrder.Epoch epoch,long startedNs,ThreadStarter starter){
        if(epoch==null||startedNs<epoch.startedNs()||starter==null)throw new IllegalArgumentException("Invalid input stop identity");
        this.epoch=epoch;this.startedNs=startedNs;this.starter=starter;
    }
    private void mutable(){if(receipt!=null)throw new IllegalStateException("Input stop receipt already sealed");}
    synchronized void bindCamera(AutoCloseable resource){
        mutable();if(resource==null||camera!=null||asyncClaimed)throw new IllegalStateException("Camera stop already owned");
        camera=resource;
    }
    synchronized void clearCamera(AutoCloseable resource){mutable();if(camera==resource)camera=null;}
    synchronized boolean hasFailures(){for(long n:failures)if(n!=0)return true;return false;}
    /** Factory failures may own native resources without returning a closeable to this worker. */
    synchronized void recordUnconfirmedCleanup(Resource kind,Throwable failure){
        mutable();if(kind==null||failure==null)throw new IllegalArgumentException("Missing cleanup failure");
        failed(kind,failure);
    }
    private synchronized void failed(Resource kind,Throwable failure){
        int index=kind.ordinal();failures[index]++;
        if(failureTypes[index]==null){String type=failure.getClass().getSimpleName();failureTypes[index]=type.substring(0,Math.min(type.length(),96));}
    }
    /** Always let the caller continue closing its other resources, including after a JNI Error. */
    Throwable close(Resource kind,AutoCloseable resource){
        if(resource==null)return null;
        synchronized(this){mutable();attempts[kind.ordinal()]++;closing++;}
        try{resource.close();return null;}
        catch(Throwable failure){failed(kind,failure);return failure;}
        finally{synchronized(this){closing--;}}
    }
    boolean requestCameraClose(){
        final AutoCloseable owned;
        synchronized(this){
            if(receipt!=null||camera==null||asyncClaimed)return false;
            asyncClaimed=true;owned=camera;
        }
        try{
            Thread closer=new Thread(()->{
                try{close(Resource.ASYNC_CAMERA,owned);}
                finally{finishAsync();}
            },"MirrorCameraStop");
            synchronized(this){asyncThread=closer;}
            starter.start(closer);
        }catch(RuntimeException|Error failure){failed(Resource.ASYNC_CAMERA,failure);finishAsync();}
        return true;
    }
    private void finishAsync(){synchronized(this){asyncFinished=true;}asyncDone.countDown();}
    /** Worker-only wait, after clearCamera. Also joins the closer; a latch alone is not termination. */
    void awaitCameraClose(){
        synchronized(this){if(!asyncClaimed)return;}
        boolean interrupted=false;
        for(;;){try{asyncDone.await();break;}catch(InterruptedException ignored){interrupted=true;}}
        Thread closer;synchronized(this){closer=asyncThread;}
        if(closer==Thread.currentThread())throw new IllegalStateException("Camera closer cannot join itself");
        if(closer!=null)while(closer.isAlive()){
            try{closer.join();}catch(InterruptedException ignored){interrupted=true;}
        }
        if(interrupted)Thread.currentThread().interrupt();
    }
    synchronized Receipt seal(long nowNs){
        if(receipt!=null)return receipt;
        if(nowNs<startedNs)throw new IllegalArgumentException("Stop time precedes worker");
        if(camera!=null||closing!=0||(asyncClaimed&&(!asyncFinished||(asyncThread!=null&&asyncThread.isAlive()))))
            throw new IllegalStateException("Input cleanup still in progress");
        receipt=new Receipt(this,nowNs);return receipt;
    }
}
