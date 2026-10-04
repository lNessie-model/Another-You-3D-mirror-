package com.mirror.bench;

import java.nio.FloatBuffer;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Actual rig/deformer and JDK thread; the thread wrapper delays exit after the task returns. */
public final class AvatarPoseWorkerStopTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void await(BooleanSupplier condition)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean()){
            if(System.nanoTime()>deadline)throw new AssertionError("condition timeout");
            Thread.sleep(1);
        }
    }
    private static void waitGate(CountDownLatch gate){
        boolean interrupted=false;
        for(;;)try{gate.await();break;}catch(InterruptedException ignored){interrupted=true;}
        if(interrupted)Thread.currentThread().interrupt();
    }
    private static void bodyReturnIsNotThreadTermination(AvatarAsset asset,String manifest)throws Exception{
        CountDownLatch begin=new CountDownLatch(1),bodyReturned=new CountDownLatch(1),exit=new CountDownLatch(1);
        AtomicReference<Thread> actual=new AtomicReference<>();
        AvatarPoseWorker worker=new AvatarPoseWorker(asset,manifest,work->{
            Thread thread=new Thread(()->{waitGate(begin);work.run();bodyReturned.countDown();waitGate(exit);},"pose-exit-fixture");
            actual.set(thread);return thread;
        });
        try{
            check(worker.submit(new float[52],new float[3])>0,"pending fixture input admitted before close");
            worker.close();begin.countDown();
            check(bodyReturned.await(3,TimeUnit.SECONDS),"actual worker task has returned");
            check(actual.get().isAlive(),"wrapper thread remains live");
            check(!worker.isTerminated(),"a returned task must not masquerade as a terminated Thread");
            AvatarPoseWorker.Status status=worker.status();
            check(status.runExitMarked&&!status.terminated&&status.threadState!=Thread.State.TERMINATED,"status separates task and thread");
            var receipt=worker.stopReceipt();
            check(receipt.closeRequested()&&receipt.runExitMarked()&&!receipt.closeSucceeded(),"live thread cannot confirm stop");
            check(receipt.pendingCount()==0&&receipt.readyCount()==0&&receipt.writingCount()==0&&receipt.leasedCount()==0,"close discarded queued work");
            check(UUID.fromString(receipt.workerId()).toString().equals(receipt.workerId()),"fixed worker identity");
            exit.countDown();actual.get().join(3000);
            check(!actual.get().isAlive()&&worker.isTerminated(),"actual Thread exits");
            var completed=worker.stopReceipt();
            check(completed.closeSucceeded()&&completed.threadState()==Thread.State.TERMINATED,"confirmed stopped receipt");
            worker.close();var repeated=worker.stopReceipt();
            check(repeated.workerId().equals(completed.workerId())&&repeated.requestedNs()==completed.requestedNs(),"repeat close preserves identity/time");
            check(!receipt.closeSucceeded()&&receipt.threadState()!=Thread.State.TERMINATED,"old observation remains immutable");
            check(completed.observedNs()>=completed.requestedNs(),"monotonic stop observation");
        }finally{worker.close();begin.countDown();exit.countDown();actual.get().join(3000);}
    }
    private static void leasesOutliveThread(AvatarAsset asset,String manifest)throws Exception{
        AvatarPoseWorker worker=new AvatarPoseWorker(asset,manifest);
        AvatarPoseWorker.Frame frame=null;
        try{
            worker.submit(new float[52],new float[3]);await(()->worker.status().readyCount==1);
            frame=worker.acquireLatest();check(frame!=null,"acquired real output lease");
            FloatBuffer data=frame.interleaved(0,0);float before=data.get(0);
            var active=worker.stopReceipt();check(!active.closeRequested()&&!active.closeSucceeded(),"live worker is not stopped");
            worker.close();await(worker::isTerminated);
            var held=worker.stopReceipt();
            check(held.threadState()==Thread.State.TERMINATED&&held.leasedCount()==1&&!held.closeSucceeded(),"thread termination does not release a leased frame");
            check(Float.floatToRawIntBits(frame.interleaved(0,0).get(0))==Float.floatToRawIntBits(before),"close preserves lease contents");
            check(worker.submit(new float[52],new float[3])==-1&&worker.acquireLatest()==null,"closed worker rejects new ownership");
            worker.release(frame);frame=null;
            var released=worker.stopReceipt();
            check(released.closeSucceeded()&&released.leasedCount()==0,"release completes CPU ownership stop");
            check(held.leasedCount()==1&&!held.closeSucceeded(),"leased snapshot cannot change after release");
            check(active.workerId().equals(released.workerId()),"all observations belong to same worker");
            AvatarPoseWorker next=new AvatarPoseWorker(asset,manifest);
            try{check(!next.stopReceipt().workerId().equals(released.workerId()),"new worker cannot reuse identity");}
            finally{next.close();await(next::isTerminated);}
        }finally{worker.close();if(frame!=null)worker.release(frame);await(worker::isTerminated);}
    }
    public static void main(String[] args)throws Exception{
        AvatarAsset asset=AvatarRigTest.fixture();String manifest=AvatarRigTest.manifest().toString();
        bodyReturnIsNotThreadTermination(asset,manifest);leasesOutliveThread(asset,manifest);
        System.out.println("AvatarPoseWorker stop checks: "+checks+"; actual Java rig/deformer/Thread, no Android or GL qualification");
    }
}
