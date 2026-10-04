package com.mirror.bench;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Invokes the real Activity's nested owner, without pretending to run an Android lifecycle. */
public final class AvatarStoreOwnerTest {
    static int checks;static Class<?> type,factory;static Method get,close;static Field retained;
    static Path suite;
    public static void main(String[] args)throws Exception {
        suite=Path.of(args[0]).resolve(UUID.randomUUID().toString());Files.createDirectories(suite);
        type=Class.forName("com.mirror.bench.AvatarManagementActivity$StoreOwner");
        factory=Class.forName("com.mirror.bench.AvatarManagementActivity$StoreFactory");
        get=type.getDeclaredMethod("get",factory);get.setAccessible(true);
        close=type.getDeclaredMethod("close");close.setAccessible(true);
        retained=type.getDeclaredField("store");retained.setAccessible(true);
        reuseAndClose();failuresCanRetry();closeDuringOpen();closeDuringUse();
        System.out.println("AvatarStoreOwnerTest "+checks+" checks GREEN; evidence="+suite);
    }
    static Object owner()throws Exception{Constructor<?> c=type.getDeclaredConstructor();c.setAccessible(true);return c.newInstance();}
    interface Open{AvatarPackageStore run()throws IOException;}
    static AvatarPackageStore acquire(Object o,Open f)throws Exception {
        Object proxy=Proxy.newProxyInstance(factory.getClassLoader(),new Class[]{factory},(p,m,a)->f.run());
        try{return (AvatarPackageStore)get.invoke(o,proxy);}catch(InvocationTargetException e){
            if(e.getCause() instanceof Exception)throw(Exception)e.getCause();throw(Error)e.getCause();
        }
    }
    static void dispose(Object o)throws Exception{close.invoke(o);}
    static AvatarPackageStore create(String name)throws IOException{return AvatarPackageStore.open(suite.resolve(name).toFile(),30);}
    static void reuseAndClose()throws Exception {
        Object o=owner();AtomicInteger count=new AtomicInteger();AvatarPackageStore a=create("reuse");
        check(acquire(o,()->{count.incrementAndGet();return a;})==a,"first real Store returned");
        check(acquire(o,()->{throw new AssertionError("second open");})==a,"same owner reuses Store");
        check(count.get()==1&&retained.get(o)==a,"one retained Store");
        dispose(o);check(retained.get(o)==null,"close releases cache owner reference");dispose(o);
        rejects(()->acquire(o,()->{throw new AssertionError("factory after destroy");}),InterruptedIOException.class,"closed cannot reopen");
        Object next=owner();check(acquire(next,()->create("next"))!=a,"new Activity gets independent Store");dispose(next);
    }
    static void failuresCanRetry()throws Exception {
        Object o=owner();IOException failure=new IOException("open failed");
        try{acquire(o,()->{throw failure;});throw new AssertionError("missing failure");}catch(IOException e){check(e==failure,"original failure preserved");}
        check(retained.get(o)==null,"failed open not retained");
        rejects(()->acquire(o,()->null),IOException.class,"null cannot become cached success");
        AssertionError error=new AssertionError("factory allocation fault");
        try{acquire(o,()->{throw error;});throw new AssertionError("missing error");}catch(AssertionError e){check(e==error,"Error not swallowed");}
        AvatarPackageStore a=create("retry");check(acquire(o,()->a)==a,"failed creation releases in-progress guard");dispose(o);
    }
    static void closeDuringOpen()throws Exception {
        Object o=owner();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),closed=new CountDownLatch(1);
        AtomicReference<Throwable> outcome=new AtomicReference<>();AtomicReference<AvatarPackageStore> created=new AtomicReference<>();
        Thread opener=new Thread(()->{try{acquire(o,()->{
            entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("test release deadline");}catch(InterruptedException e){throw new InterruptedIOException();}
            AvatarPackageStore s=create("late-open");created.set(s);return s;
        });outcome.set(new AssertionError("late open returned success"));}catch(Throwable t){outcome.set(t);}},"test-store-open");
        opener.start();try{
            check(entered.await(3,TimeUnit.SECONDS),"opening factory reached controlled barrier");
            rejects(()->acquire(o,()->{throw new AssertionError("parallel factory");}),AvatarPackageStore.BusyException.class,"one open owner even during creation");
            Thread destroyer=new Thread(()->{try{dispose(o);closed.countDown();}catch(Throwable e){outcome.set(e);}},"test-destroy");destroyer.start();
            check(closed.await(2,TimeUnit.SECONDS),"destroy does not wait for blocked filesystem factory");
            check(retained.get(o)==null,"no retained Store while opener blocked");
        }finally{release.countDown();opener.join(5000);}
        check(!opener.isAlive(),"opener terminates after controlled release");
        check(created.get()!=null&&outcome.get() instanceof InterruptedIOException,"late constructed Store is rejected after destroy");
        check(retained.get(o)==null,"late open cannot reattach after destroy");
        rejects(()->acquire(o,()->created.get()),InterruptedIOException.class,"destroyed owner remains permanently closed");
    }
    static void closeDuringUse()throws Exception {
        Object o=owner();CountDownLatch reading=new CountDownLatch(1),release=new CountDownLatch(1);
        AvatarPackageStore a=create("active-import");acquire(o,()->a);
        Method archive=AvatarPackageStoreTest.class.getDeclaredMethod("archive",String.class);archive.setAccessible(true);
        byte[] data=(byte[])archive.invoke(null,"real active import");AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread io=new Thread(()->{try{
            a.importZip(new ByteArrayInputStream(data){boolean first=true;@Override public synchronized int read(byte[] b,int off,int len){
                if(first){first=false;reading.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("release deadline");}catch(InterruptedException e){throw new AssertionError(e);}}
                return super.read(b,off,len);
            }});
        }catch(Throwable t){failure.set(t);}},"test-store-use");
        io.start();try{check(reading.await(3,TimeUnit.SECONDS),"real Store operation owns Guard and waits for provider");dispose(o);
            check(retained.get(o)==null,"destroy drops cached owner without closing an in-flight Store operation");
            rejects(()->acquire(o,()->a),InterruptedIOException.class,"no later work after destroy");
        }finally{release.countDown();io.join(30000);}
        // This verifies ownership after completion, not a five-second filesystem latency budget.
        check(!io.isAlive(),"caller-owned Store operation finishes within the test watchdog");
        check(failure.get()==null,"caller-owned in-flight Store can finish safely: "+failure.get());
        check(a.readCandidate()!=null&&retained.get(o)==null,"completed operation cannot republish owner; disk result is independently readable");
    }
    interface Checked{void run()throws Exception;}
    static void rejects(Checked f,Class<? extends Exception> kind,String why)throws Exception{
        try{f.run();throw new AssertionError("Missing rejection: "+why);}catch(Exception e){if(!kind.isInstance(e))throw e;checks++;}
    }
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
}
