package com.mirror.bench;

import java.nio.ByteBuffer;
import java.util.Arrays;
import org.json.JSONObject;

/** Actual model lifecycle with a narrow native boundary substitution; no library is loaded. */
public final class RknnModelLifecycleTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    private static final class Fake implements RknnModel.NativeCalls {
        int opens,infos,runs,closes;
        Throwable infoFailure,closeFailure;
        long handle=17;
        public long open(String path){opens++;check(path.equals("fixed-model"));return handle;}
        public String info(long handle){infos++;check(handle==17);if(infoFailure!=null)raise(infoFailure);return "{}";}
        public float[][] run(long handle,ByteBuffer input){runs++;check(handle==17);return new float[][]{{1}};}
        public void close(long handle){closes++;check(handle==17);if(closeFailure!=null)raise(closeFailure);}
    }
    private static void raise(Throwable failure){if(failure instanceof Error)throw (Error)failure;throw (RuntimeException)failure;}
    private interface Action {void run()throws Exception;}
    private static Throwable failure(Action action){try{action.run();}catch(Throwable error){checks++;return error;}throw new AssertionError("failure erased");}
    public static void main(String[] args)throws Exception{
        Fake f=new Fake();RknnModel model=new RknnModel("fixed-model",f);
        check(f.opens==1&&f.infos==1&&model.info instanceof JSONObject);
        check(model.run(RknnModel.buffer(1))[0][0]==1&&f.runs==1);
        model.close();model.close();check(f.closes==1);
        check(failure(()->model.run(RknnModel.buffer(1))) instanceof IllegalStateException&&f.runs==1);
        Fake bad=new Fake();RknnModel broken=new RknnModel("fixed-model",bad);
        AssertionError nativeError=new AssertionError("destroy result unknown");bad.closeFailure=nativeError;
        Throwable first=failure(broken::close);check(first instanceof NativeCleanupUnconfirmed&&first.getCause()==nativeError);
        check(failure(broken::close)==first&&bad.closes==1);
        check(failure(()->broken.run(RknnModel.buffer(1))) instanceof IllegalStateException&&bad.runs==0);
        Fake init=new Fake();OutOfMemoryError original=new OutOfMemoryError("metadata");
        init.infoFailure=original;init.closeFailure=new IllegalStateException("cleanup");
        check(failure(()->new RknnModel("fixed-model",init))==original);
        check(init.closes==1&&original.getSuppressed().length==1);
        check(NativeCleanupUnconfirmed.contains(original));
        check(Arrays.stream(original.getSuppressed()).anyMatch(e->e instanceof NativeCleanupUnconfirmed));
        Fake cleanInit=new Fake();IllegalStateException infoError=new IllegalStateException("info");cleanInit.infoFailure=infoError;
        check(failure(()->new RknnModel("fixed-model",cleanInit))==infoError&&cleanInit.closes==1);
        check(!NativeCleanupUnconfirmed.contains(infoError));
        Fake zero=new Fake();zero.handle=0;check(failure(()->new RknnModel("fixed-model",zero)) instanceof IllegalStateException);
        check(zero.closes==0&&zero.infos==0);
        RuntimeException outer=new RuntimeException(new NativeCleanupUnconfirmed("JNI cleanup"));
        check(NativeCleanupUnconfirmed.contains(outer));check(!NativeCleanupUnconfirmed.contains(new IllegalArgumentException("ordinary")));
        RuntimeException a=new RuntimeException(),b=new RuntimeException();a.initCause(b);b.initCause(a);
        check(!NativeCleanupUnconfirmed.contains(a));
        Throwable deep=new RuntimeException();for(int i=0;i<100;i++)deep=new RuntimeException(deep);
        check(NativeCleanupUnconfirmed.contains(deep)); // Too-deep diagnostic ownership is not confirmed.
        System.out.println("RKNN model lifecycle checks: "+checks+"; actual Java model and fake native calls only");
    }
}
