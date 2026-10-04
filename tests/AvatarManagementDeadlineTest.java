package com.mirror.bench;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deterministic clock boundaries plus a real single executor whose provider ignores interrupt. */
public final class AvatarManagementDeadlineTest {
    private static int checks;
    private static final long SECOND=1_000_000_000L;
    public static void main(String[] args)throws Exception {
        loadingAndOwnership();glOwnership();activationAndLifecycle();blockedProvider();
        System.out.println("AvatarManagementDeadlineTest: "+checks+" assertions passed");
    }
    private static void loadingAndOwnership(){
        AvatarManagementGate g=new AvatarManagementGate();g.resume();
        check(g.pollTimeout(100*SECOND)==null,"resumed idle page has no deadline");
        var first=g.beginSelection(0);var ctx=g.openContext(29*SECOND);
        check(g.pollTimeout(30*SECOND-1)==null,"IO receives the full 30 seconds");
        var timeout=g.pollTimeout(30*SECOND);
        check(timeout!=null&&timeout.stage==AvatarManagementGate.Stage.LOADING,"empty GL context does not steal IO deadline");
        check(timeout.owner==first&&timeout.elapsedNs==30*SECOND&&timeout.budgetNs==30*SECOND,"timeout records exact original ownership and budget");
        check(!g.isCurrent(first)&&!g.loaded(first,new Object(),31*SECOND),"late IO result revoked");
        check(!g.previewSubmitted(first,ctx,new Object(),31*SECOND),"late GL cannot authorize timed-out owner");
        check(g.pollTimeout(100*SECOND)==null,"timeout delivered once");
        var second=g.beginSelection(100*SECOND);
        check(!g.workFailed(first,101*SECOND),"old failure cannot stop new owner's clock");
        check(g.pollTimeout(100*SECOND-1)==null,"older sampled time cannot create a timeout");
        check(!g.loaded(second,new Object(),131*SECOND),"late success cannot reset expired IO deadline before UI poll");
        check(g.pollTimeout(131*SECOND).stage==AvatarManagementGate.Stage.LOADING,"late success leaves timeout for UI");
        var third=g.beginSelection(200*SECOND);
        check(g.workFailed(third,201*SECOND),"ordinary IO failure ends wait");
        check(g.pollTimeout(300*SECOND)==null,"reported failure does not later become a timeout");
    }
    private static void glOwnership(){
        AvatarManagementGate g=new AvatarManagementGate();g.resume();var owner=g.beginSelection(0);Object a=new Object();
        var ctx=g.openContext(2*SECOND);
        check(g.loaded(owner,a,29*SECOND),"slow valid IO completes within budget");
        check(g.pollTimeout(30*SECOND)==null,"GL budget starts after CPU load rather than at selection");
        g.contextLost(ctx,40*SECOND);ctx=g.openContext(40*SECOND);
        check(g.pollTimeout(49*SECOND-1)==null,"GL receives full 20 seconds");
        check(g.pollTimeout(49*SECOND).stage==AvatarManagementGate.Stage.GL_INITIALIZING,"repeated context construction cannot extend pending GL forever");
        check(!g.previewSubmitted(owner,ctx,a,50*SECOND),"late GL success cannot resurrect preview");
        owner=g.beginSelection(60*SECOND);ctx=g.openContext(60*SECOND);g.loaded(owner,a,61*SECOND);
        check(g.previewSubmitted(owner,ctx,a,80*SECOND),"successful preview before deadline accepted");
        check(g.pollTimeout(1000*SECOND)==null,"successfully previewed idle page never expires");
        g.contextLost(ctx,1001*SECOND);
        check(!g.canActivate(owner,a),"lost context immediately revokes GL proof");
        ctx=g.openContext(1010*SECOND);
        check(g.pollTimeout(1021*SECOND).stage==AvatarManagementGate.Stage.GL_INITIALIZING,"reconstruction budget begins at loss, not later openContext");
        owner=g.beginSelection(1100*SECOND);ctx=g.openContext(1100*SECOND);g.loaded(owner,a,1101*SECOND);
        check(!g.previewSubmitted(owner,ctx,a,1121*SECOND),"exact GL deadline rejects success even before poll");
        check(g.pollTimeout(1121*SECOND)!=null,"expired GL transition leaves user-visible timeout");
    }
    private static void activationAndLifecycle(){
        AvatarManagementGate g=new AvatarManagementGate();g.resume();var owner=g.beginSelection(0);Object a=new Object();
        var ctx=g.openContext(0);g.loaded(owner,a,SECOND);g.previewSubmitted(owner,ctx,a,2*SECOND);
        var claim=g.claimActivation(owner,a,3*SECOND);
        check(claim!=null&&g.isValid(claim,3*SECOND),"successful preview permits one explicit claim");
        g.previewSubmitted(owner,ctx,a,10*SECOND);
        check(g.pollTimeout(18*SECOND-1)==null,"GL callback cannot terminate activation deadline");
        check(g.pollTimeout(18*SECOND).stage==AvatarManagementGate.Stage.ACTIVATING,"activation has 15 second bound");
        check(!g.isValid(claim,18*SECOND)&&!g.canActivate(owner,a),"timed-out commit cannot claim UI success or reuse proof");
        owner=g.beginSelection(20*SECOND);ctx=g.openContext(20*SECOND);g.loaded(owner,a,21*SECOND);g.previewSubmitted(owner,ctx,a,22*SECOND);
        claim=g.claimActivation(owner,a,23*SECOND);g.activationFailed(claim,24*SECOND);
        check(g.canActivate(owner,a)&&g.pollTimeout(100*SECOND)==null,"reported commit failure ends wait and preserves valid proof");
        claim=g.claimActivation(owner,a,101*SECOND);g.openContext(105*SECOND);
        check(!g.isValid(claim,105*SECOND),"new context invalidates an uncommitted claim");
        check(g.pollTimeout(116*SECOND).stage==AvatarManagementGate.Stage.ACTIVATING,"context change cannot relabel/reset in-flight commit deadline");
        owner=g.beginSelection(120*SECOND);ctx=g.openContext(120*SECOND);g.loaded(owner,a,121*SECOND);g.previewSubmitted(owner,ctx,a,122*SECOND);
        claim=g.claimActivation(owner,a,123*SECOND);
        check(!g.isValid(claim,138*SECOND),"late commit success cannot outrun UI deadline poll");
        check(g.pollTimeout(138*SECOND).stage==AvatarManagementGate.Stage.ACTIVATING,"late commit still reports an uncertain timeout");
        owner=g.beginSelection(200*SECOND);g.pause();
        check(g.pollTimeout(300*SECOND)==null&&!g.isCurrent(owner),"HOME cancels deadline and owner together");
        g.resume();owner=g.beginSelection(400*SECOND);
        check(g.pollTimeout(429*SECOND)==null,"resume receives new independent IO budget");
        g.pause();g.resume();check(g.pollTimeout(900*SECOND)==null,"resume without work cannot inherit an old timeout");
    }
    private static void blockedProvider()throws Exception {
        // Simulates provider code which ignores interrupt. Release is only a test teardown escape.
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1));
        AtomicBoolean queuedRan=new AtomicBoolean();
        try {
            Future<?> stuck=io.submit(()->{entered.countDown();while(true){try{release.await();break;}catch(InterruptedException ignored){}}});
            check(entered.await(5,TimeUnit.SECONDS),"provider occupies only IO thread");
            AvatarManagementGate g=new AvatarManagementGate();g.resume();var owner=g.beginSelection(0);
            Future<?> queued=io.submit(()->{queuedRan.set(true);g.loaded(owner,new Object(),31*SECOND);});
            check(io.getQueue().size()==1,"new selection accepted into queue behind stuck provider");
            var timeout=g.pollTimeout(30*SECOND);
            check(timeout!=null&&!g.isCurrent(owner),"UI clock expires queued selection without an IO callback");
            queued.cancel(true);io.remove((Runnable)queued);stuck.cancel(true);
            check(io.getQueue().isEmpty()&&io.getPoolSize()==1,"cancellation removes queue entry without replacing worker");
            release.countDown();io.shutdown();check(io.awaitTermination(5,TimeUnit.SECONDS),"provider eventually releases existing thread");
            check(!queuedRan.get(),"cancelled queued task cannot later load or activate");
        }finally {release.countDown();io.shutdownNow();}
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;}
}
