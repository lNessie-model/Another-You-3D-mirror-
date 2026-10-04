package com.mirror.bench;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Real fixed model/APK hashes, fake native boundary. No native/device inference. */
public final class RknnExpressionTest {
    private static int checks;
    private static void check(boolean okay,String message){checks++;if(!okay)throw new AssertionError(message);}
    private interface Action{void run()throws Exception;}
    private static void rejects(Class<? extends Throwable> type,Action action)throws Exception{
        try{action.run();throw new AssertionError("Expected "+type.getSimpleName());}catch(Throwable error){check(type.isInstance(error),"Wrong exception: "+error);}
    }
    private static final class Fake implements RknnExpression.NativeCalls {
        int opens,runs,infos,closes;boolean failInfo,failRun,failClose,zeroHandle;
        CountDownLatch inRun,finishRun;
        public long open(byte[] model,String runtime){opens++;check(model.length==1209569,"exact model bytes");check(runtime.endsWith("!/lib/arm64-v8a/librknnrt.so"),"same app APK runtime entry");return zeroHandle?0:99;}
        public String info(long handle){check(handle==99,"info owns handle");infos++;if(failInfo)throw new OutOfMemoryError("info allocation");return "{\"kind\":\"fake-boundary\"}";}
        public float[] run(long handle,ByteBuffer input){
            check(handle==99&&input.isDirect()&&input.capacity()==1168,"run contract");runs++;
            if(inRun!=null){inRun.countDown();try{if(!finishRun.await(5,TimeUnit.SECONDS))throw new AssertionError("test gate timeout");}catch(InterruptedException e){throw new AssertionError(e);}}
            if(failRun)throw new IllegalStateException("native failure");return new float[52];
        }
        public void close(long handle){check(handle==99,"close owns handle");closes++;if(failClose)throw new IllegalStateException("destroy failure");}
    }
    private static void runtimeZip(Path file,byte[] runtime,boolean stored)throws Exception{
        try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(file))){
            ZipEntry entry=new ZipEntry(RknnExpression.RUNTIME_ENTRY);
            if(stored){CRC32 crc=new CRC32();crc.update(runtime);entry.setMethod(ZipEntry.STORED);entry.setSize(runtime.length);entry.setCompressedSize(runtime.length);entry.setCrc(crc.getValue());}
            out.putNextEntry(entry);out.write(runtime);out.closeEntry();
        }
    }
    public static void main(String[] args)throws Exception{
        check(args.length==3,"model, APK, fresh scratch directory");String model=args[0],apk=args[1];Path scratch=Path.of(args[2]);Files.createDirectory(scratch);
        Fake fake=new Fake();RknnExpression expression=new RknnExpression(model,apk,fake);
        check(fake.opens==1&&fake.infos==1,"open and metadata once");check(expression.info().contains("fake-boundary"),"info available");
        ByteBuffer good=RknnExpression.buffer();check(good.capacity()==1168&&good.order()==ByteOrder.nativeOrder(),"292 native floats");
        check(expression.run(good).length==52,"exact result size");
        rejects(IllegalArgumentException.class,()->expression.run(null));
        rejects(IllegalArgumentException.class,()->expression.run(ByteBuffer.allocate(1168).order(ByteOrder.nativeOrder())));
        rejects(IllegalArgumentException.class,()->expression.run(ByteBuffer.allocateDirect(1164).order(ByteOrder.nativeOrder())));
        rejects(IllegalArgumentException.class,()->expression.run(ByteBuffer.allocateDirect(1172).order(ByteOrder.nativeOrder())));
        ByteBuffer wrongOrder=RknnExpression.buffer().order(ByteOrder.nativeOrder()==ByteOrder.LITTLE_ENDIAN?ByteOrder.BIG_ENDIAN:ByteOrder.LITTLE_ENDIAN);
        rejects(IllegalArgumentException.class,()->expression.run(wrongOrder));
        good.position(4);rejects(IllegalArgumentException.class,()->expression.run(good));good.clear();good.limit(1164);rejects(IllegalArgumentException.class,()->expression.run(good));good.clear();
        for(float bad:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}){good.putFloat(1164,bad);rejects(IllegalArgumentException.class,()->expression.run(good));}good.putFloat(1164,0);
        check(fake.runs==1,"bad inputs never call native");check(expression.run(good).length==52,"input validation leaves context usable");
        fake.failRun=true;rejects(IllegalStateException.class,()->expression.run(good));int runs=fake.runs;rejects(IllegalStateException.class,()->expression.run(good));check(fake.runs==runs,"no reuse after native fault");
        expression.close();expression.close();check(fake.closes==1,"idempotent close");rejects(IllegalStateException.class,()->expression.run(good));
        Fake infoFail=new Fake();infoFail.failInfo=true;infoFail.failClose=true;rejects(OutOfMemoryError.class,()->new RknnExpression(model,apk,infoFail));check(infoFail.closes==1,"metadata allocation failure closes and preserves original error");
        Fake closeFail=new Fake();RknnExpression c=new RknnExpression(model,apk,closeFail);closeFail.failClose=true;rejects(IllegalStateException.class,c::close);c.close();check(closeFail.closes==1,"failed destruction never retries freed JNI handle");
        Fake zero=new Fake();zero.zeroHandle=true;rejects(IllegalStateException.class,()->new RknnExpression(model,apk,zero));check(zero.closes==0,"zero handle never destroyed");
        byte[] changed=Files.readAllBytes(Path.of(model));changed[100]^=1;Path wrong=scratch.resolve("changed.rknn");Files.write(wrong,changed);Fake unopened=new Fake();
        rejects(IOException.class,()->new RknnExpression(wrong.toString(),apk,unopened));check(unopened.opens==0,"model mismatch rejected before native");
        Path shortModel=scratch.resolve("short.rknn");Files.write(shortModel,new byte[4]);rejects(IOException.class,()->new RknnExpression(shortModel.toString(),apk,unopened));
        Path wrongZip=scratch.resolve("not-an-apk.zip");Files.write(wrongZip,new byte[]{1,2,3});rejects(IOException.class,()->new RknnExpression(model,wrongZip.toString(),unopened));check(unopened.opens==0,"bad APK rejected before native");
        byte[] runtime;
        try(ZipFile zip=new ZipFile(apk);var entry=zip.getInputStream(zip.getEntry(RknnExpression.RUNTIME_ENTRY))){runtime=entry.readAllBytes();}
        runtime[100]^=1;Path corruptRuntime=scratch.resolve("corrupt-runtime.zip");runtimeZip(corruptRuntime,runtime,true);
        rejects(IOException.class,()->new RknnExpression(model,corruptRuntime.toString(),unopened));check(unopened.opens==0,"correct-size bad runtime SHA rejected");
        runtime[100]^=1;Path compressed=scratch.resolve("compressed-runtime.zip");runtimeZip(compressed,runtime,false);
        rejects(IOException.class,()->new RknnExpression(model,compressed.toString(),unopened));check(unopened.opens==0,"compressed identical runtime rejected before dlopen");
        Fake blocked=new Fake();blocked.inRun=new CountDownLatch(1);blocked.finishRun=new CountDownLatch(1);RknnExpression serial=new RknnExpression(model,apk,blocked);
        AtomicReference<Throwable> failure=new AtomicReference<>();CountDownLatch closeStarted=new CountDownLatch(1),closed=new CountDownLatch(1);
        Thread worker=new Thread(()->{try{serial.run(RknnExpression.buffer());}catch(Throwable t){failure.set(t);}});
        Thread closer=new Thread(()->{closeStarted.countDown();try{serial.close();}catch(Throwable t){failure.set(t);}finally{closed.countDown();}});
        worker.start();check(blocked.inRun.await(5,TimeUnit.SECONDS),"worker entered native");closer.start();check(closeStarted.await(5,TimeUnit.SECONDS),"close requested");check(!closed.await(100,TimeUnit.MILLISECONDS),"close cannot destroy during run");blocked.finishRun.countDown();worker.join(5000);closer.join(5000);
        check(!worker.isAlive()&&!closer.isAlive()&&failure.get()==null&&blocked.closes==1,"sequential cross-thread lifecycle succeeds");
        System.out.println("RknnExpressionTest: "+checks+" checks passed; native boundary is fake, fixed model/APK are real");
    }
}
