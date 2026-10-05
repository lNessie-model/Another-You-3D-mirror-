package com.mirror.bench;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the Activity's actual preference boundary, without constructing an Android Activity. */
public final class AssetLibraryLifecycleTest {
    private static int checks;
    private static void check(boolean pass,String message){checks++;if(!pass)throw new AssertionError(message);}
    private static void await(CountDownLatch latch)throws IOException{
        try{if(!latch.await(2,TimeUnit.SECONDS))throw new IOException("Test transition timed out");}
        catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IOException(failure);}
    }
    private static void join(Thread thread)throws Exception{
        thread.join(2000);check(!thread.isAlive(),"Test worker failed to finish");
    }
    public static void main(String[] args)throws Exception{
        var lifetime=new AssetLibraryActivity.SelectionLifetime();AtomicReference<String> choice=new AtomicReference<>("old-role");
        long absent=lifetime.next();check(!lifetime.active(),"Initially inactive");
        check(!lifetime.commitIfCurrent(absent,()->true,()->choice.set("new-role")),"Inactive owner committed");
        check(choice.get().equals("old-role"),"Inactive owner changed choice");
        lifetime.resume();long selected=lifetime.next();check(lifetime.current(selected),"Resumed owner current token");
        long superseding=lifetime.next();check(!lifetime.current(selected),"New selection did not supersede old token");
        check(!lifetime.commitIfCurrent(selected,()->true,()->choice.set("stale-role")),"Stale selection committed");
        check(!lifetime.commitIfCurrent(superseding,()->false,()->choice.set("finished-role")),"Finishing owner committed");
        check(choice.get().equals("old-role"),"Rejected transaction changed choice");
        try{lifetime.commitIfCurrent(superseding,()->true,()->{throw new IOException("Real store failure");});
            throw new AssertionError("Preference error hidden");}catch(IOException expected){checks++;}
        check(choice.get().equals("old-role"),"Failed transaction changed choice");

        // A slow preparation happens outside the preference monitor. Returning invalidates
        // immediately, and the eventual verification result cannot reach the store callback.
        CountDownLatch hashing=new CountDownLatch(1),finishHash=new CountDownLatch(1);
        AtomicReference<Throwable> failure=new AtomicReference<>();AtomicBoolean committed=new AtomicBoolean(true);
        Thread preparing=new Thread(()->{
            try{hashing.countDown();await(finishHash);committed.set(lifetime.commitIfCurrent(superseding,()->true,()->choice.set("late-role")));}
            catch(Throwable error){failure.set(error);}
        },"TestAssetPreparation");
        preparing.start();await(hashing);lifetime.invalidate();
        check(!lifetime.active()&&!lifetime.current(superseding),"Return did not invalidate in-flight preparation");
        finishHash.countDown();join(preparing);check(failure.get()==null,"Preparation test failure: "+failure.get());
        check(!committed.get()&&choice.get().equals("old-role"),"Cancelled long preparation saved a role");
        lifetime.resume();check(!lifetime.current(superseding),"Recreation reused an old preparation token");

        // Once the short, explicit preference transaction has entered, returning waits for
        // that transaction only. A completed selection is retained rather than rolled back.
        long accepted=lifetime.next();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        CountDownLatch returning=new CountDownLatch(1),returned=new CountDownLatch(1);failure.set(null);
        Thread committing=new Thread(()->{
            try{committed.set(lifetime.commitIfCurrent(accepted,()->true,()->{entered.countDown();await(release);choice.set("selected-role");}));}
            catch(Throwable error){failure.set(error);}
        },"TestShortPreferenceCommit");
        Thread leaving=new Thread(()->{returning.countDown();lifetime.invalidate();returned.countDown();},"TestReturnAfterCommitEntry");
        committing.start();await(entered);leaving.start();await(returning);
        check(!returned.await(100,TimeUnit.MILLISECONDS),"Return raced through the preference transaction");
        release.countDown();join(committing);join(leaving);check(failure.get()==null,"Preference test failure: "+failure.get());
        check(committed.get()&&choice.get().equals("selected-role"),"Completed explicit selection was discarded");
        check(!lifetime.current(accepted),"Returned owner still current");
        check(!lifetime.commitIfCurrent(accepted,()->true,()->choice.set("post-return-role")),"Post-return store callback executed");
        System.out.println("AssetLibraryLifecycleTest PASS "+checks+" checks");
    }
}
