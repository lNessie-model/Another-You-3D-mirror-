package com.mirror.bench;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Real close calls and bounded thread races; no Android/native close qualification. */
public final class RuntimeInputStopTest {
    private static int checks;
    private static void check(boolean value) { checks++; if(!value)throw new AssertionError("check "+checks); }
    private static RuntimeInputStop create() { return new RuntimeInputStop(new RuntimeStatusOrder().begin(1),2); }
    private static void rejected(Runnable work) {
        try { work.run(); throw new AssertionError("unsafe transition accepted"); }
        catch(IllegalStateException expected) { checks++; }
    }
    private static void join(Thread thread)throws Exception { thread.join(2000);check(!thread.isAlive()); }

    public static void main(String[] args)throws Exception {
        RuntimeInputStop stop=create();
        AtomicInteger calls=new AtomicInteger();
        for(RuntimeInputStop.Resource kind:RuntimeInputStop.Resource.values()) {
            if(kind==RuntimeInputStop.Resource.ASYNC_CAMERA)continue;
            check(stop.close(kind,calls::incrementAndGet)==null);
            check(stop.close(kind,null)==null);
        }
        check(calls.get()==6);
        var receipt=stop.seal(3);
        check(receipt.closeCallsSucceeded());check(!receipt.asyncCameraClaimed());
        check(receipt.epochNumber()==1&&receipt.startedNs()==2&&receipt.finishedNs()==3);
        check(receipt.workerId().length()==36&&receipt.epochId().length()==36);
        check(stop.seal(4)==receipt);
        rejected(()->stop.close(RuntimeInputStop.Resource.NPU,()->{}));
        rejected(()->stop.bindCamera(()->{}));
        check(!stop.requestCameraClose());
        for(RuntimeInputStop.Resource kind:RuntimeInputStop.Resource.values()) {
            check(receipt.failures(kind)==0);
            check(receipt.attempts(kind)==(kind==RuntimeInputStop.Resource.ASYNC_CAMERA?0:1));
        }
        RuntimeInputStop failing=create();
        IllegalStateException failure=new IllegalStateException("never expose arbitrary resource message");
        check(failing.close(RuntimeInputStop.Resource.NPU,()->{throw failure;})==failure);
        check(failing.close(RuntimeInputStop.Resource.NPU,()->{})==null);
        AssertionError nativeFailure=new AssertionError("JNI close error");
        check(failing.close(RuntimeInputStop.Resource.CONVERTER,()->{throw nativeFailure;})==nativeFailure);
        check(failing.hasFailures());
        var failed=failing.seal(4);
        check(!failed.closeCallsSucceeded());check(failed.attempts(RuntimeInputStop.Resource.NPU)==2);
        check(failed.failures(RuntimeInputStop.Resource.NPU)==1);
        check(failed.failureType(RuntimeInputStop.Resource.NPU).equals("IllegalStateException"));
        RuntimeInputStop.FailureLatch latch=new RuntimeInputStop.FailureLatch();
        latch.record(receipt);check(!latch.blocked());latch.record(failed);check(latch.blocked());
        latch.record(receipt);check(latch.failure()==failed);

        // close() returning twice must not hide the first still-running asynchronous close.
        RuntimeInputStop concurrent=create();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger cameraCalls=new AtomicInteger();
        AutoCloseable camera=()->{
            if(cameraCalls.incrementAndGet()==1){entered.countDown();release.await();}
        };
        concurrent.bindCamera(camera);
        check(concurrent.requestCameraClose());check(entered.await(2,TimeUnit.SECONDS));
        check(!concurrent.requestCameraClose());
        concurrent.clearCamera(camera);check(!concurrent.requestCameraClose());
        check(concurrent.close(RuntimeInputStop.Resource.SOURCE,camera)==null);
        rejected(()->concurrent.seal(5));
        CountDownLatch waiting=new CountDownLatch(1),finished=new CountDownLatch(1);
        AtomicReference<RuntimeInputStop.Receipt> result=new AtomicReference<>();
        AtomicReference<Throwable> error=new AtomicReference<>();
        Thread waiter=new Thread(()->{
            waiting.countDown();Thread.currentThread().interrupt();
            try { concurrent.awaitCameraClose();check(Thread.currentThread().isInterrupted());result.set(concurrent.seal(6)); }
            catch(Throwable problem){error.set(problem);}finally{finished.countDown();}
        });
        waiter.start();check(waiting.await(2,TimeUnit.SECONDS));
        check(!finished.await(30,TimeUnit.MILLISECONDS));release.countDown();join(waiter);
        check(error.get()==null);check(result.get().closeCallsSucceeded());
        check(result.get().asyncCameraClaimed()&&result.get().asyncCameraThreadTerminated());
        check(result.get().attempts(RuntimeInputStop.Resource.SOURCE)==1);
        check(result.get().attempts(RuntimeInputStop.Resource.ASYNC_CAMERA)==1);

        // Late cancellation cannot schedule a closer after the worker clears its owned camera.
        RuntimeInputStop cleared=create();cleared.bindCamera(camera);cleared.clearCamera(camera);
        check(!cleared.requestCameraClose());cleared.awaitCameraClose();check(cleared.seal(4).closeCallsSucceeded());
        RuntimeInputStop blocked=create();blocked.bindCamera(camera);
        rejected(()->blocked.seal(4));rejected(()->blocked.bindCamera(()->{}));
        blocked.clearCamera(()->{});rejected(()->blocked.seal(4));blocked.clearCamera(camera);
        check(blocked.seal(4).closeCallsSucceeded());

        // Scheduling failure is sticky even if the synchronous worker close succeeds.
        RuntimeInputStop unscheduled=new RuntimeInputStop(new RuntimeStatusOrder().begin(1),2,
                thread->{throw new IllegalStateException("cannot create close thread");});
        AutoCloseable inert=()->{};unscheduled.bindCamera(inert);check(unscheduled.requestCameraClose());
        unscheduled.clearCamera(inert);check(unscheduled.close(RuntimeInputStop.Resource.SOURCE,inert)==null);
        unscheduled.awaitCameraClose();var notStopped=unscheduled.seal(5);
        check(!notStopped.closeCallsSucceeded()&&!notStopped.asyncCameraThreadTerminated());
        check(notStopped.failures(RuntimeInputStop.Resource.ASYNC_CAMERA)==1);
        latch.record(notStopped);check(latch.failure()==failed);
        RuntimeInputStop unknown=create();unknown.recordUnconfirmedCleanup(RuntimeInputStop.Resource.NPU,new IllegalStateException("factory cleanup"));
        var factory=unknown.seal(6);check(!factory.closeCallsSucceeded());
        check(factory.attempts(RuntimeInputStop.Resource.NPU)==0&&factory.failures(RuntimeInputStop.Resource.NPU)==1);
        rejected(()->unknown.recordUnconfirmedCleanup(RuntimeInputStop.Resource.NPU,new IllegalStateException()));
        RuntimeInputStop busy=create();CountDownLatch inside=new CountDownLatch(1),outside=new CountDownLatch(1);
        Thread close=new Thread(()->busy.close(RuntimeInputStop.Resource.NPU,()->{inside.countDown();outside.await();}));
        close.start();check(inside.await(2,TimeUnit.SECONDS));rejected(()->busy.seal(4));
        outside.countDown();join(close);check(busy.seal(5).closeCallsSucceeded());
        RuntimeInputStop startedThenFailed=new RuntimeInputStop(new RuntimeStatusOrder().begin(1),2,
                thread->{thread.start();throw new AssertionError("start returned ambiguous result");});
        startedThenFailed.bindCamera(inert);startedThenFailed.requestCameraClose();startedThenFailed.clearCamera(inert);
        startedThenFailed.close(RuntimeInputStop.Resource.SOURCE,inert);startedThenFailed.awaitCameraClose();
        var ambiguous=startedThenFailed.seal(5);
        check(!ambiguous.closeCallsSucceeded()&&ambiguous.asyncCameraThreadTerminated());
        check(ambiguous.attempts(RuntimeInputStop.Resource.ASYNC_CAMERA)==1);
        // Two real competing callers reproduce the former volatile read/scheduled-flag window.
        for(int i=0;i<200;i++){
            RuntimeInputStop race=create();AtomicInteger closed=new AtomicInteger();AutoCloseable input=closed::incrementAndGet;
            race.bindCamera(input);CountDownLatch go=new CountDownLatch(1);
            AtomicReference<Throwable> problem=new AtomicReference<>();
            Thread cancel=new Thread(()->{try{go.await();race.requestCameraClose();}catch(Throwable t){problem.set(t);}});
            Thread releaseWorker=new Thread(()->{try{go.await();race.clearCamera(input);race.close(RuntimeInputStop.Resource.SOURCE,input);}catch(Throwable t){problem.set(t);}});
            cancel.start();releaseWorker.start();go.countDown();join(cancel);join(releaseWorker);
            race.awaitCameraClose();var stopped=race.seal(6);
            check(problem.get()==null&&stopped.closeCallsSucceeded());
            check(stopped.attempts(RuntimeInputStop.Resource.SOURCE)==1);
            check(stopped.attempts(RuntimeInputStop.Resource.ASYNC_CAMERA)==(stopped.asyncCameraClaimed()?1:0));
            check(closed.get()==(stopped.asyncCameraClaimed()?2:1));
            check(!race.requestCameraClose());
        }
        System.out.println("Runtime input stop checks: "+checks+"; close-return/thread scope only, no Camera2 HAL/NPU/GL qualification");
    }
}
