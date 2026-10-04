package com.mirror.bench;

import android.graphics.Bitmap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.json.JSONObject;

/** Persistent ordinary-app RKNN execution; independent of the stock NPU enum. */
final class RknnModel implements AutoCloseable {
    private static final class NativeLibrary {
        static { System.loadLibrary("rknnrt"); System.loadLibrary("mirror_rknn_face"); }
        static void load() {}
    }
    interface NativeCalls {
        long open(String path) throws Exception;
        String info(long handle);
        float[][] run(long handle,ByteBuffer input);
        void close(long handle);
    }
    private static final NativeCalls BRIDGE=new NativeCalls() {
        public long open(String path){NativeLibrary.load();return openNative(path);}
        public String info(long handle){return infoNative(handle);}
        public float[][] run(long handle,ByteBuffer input){return runNative(handle,input);}
        public void close(long handle){closeNative(handle);}
    };
    private final NativeCalls calls;
    private long handle;
    private NativeCleanupUnconfirmed closeFailure;
    final JSONObject info;
    RknnModel(String path) throws Exception { this(path,BRIDGE); }
    RknnModel(String path,NativeCalls calls) throws Exception {
        if(calls==null)throw new IllegalArgumentException("Missing RKNN native calls");
        this.calls=calls;handle=calls.open(path);
        if(handle==0)throw new IllegalStateException("RKNN model returned no context");
        try { info=new JSONObject(calls.info(handle)); }
        catch(Throwable error) {
            try { close(); } catch(Throwable cleanup) { if(cleanup!=error)error.addSuppressed(cleanup); }
            throw error;
        }
    }
    synchronized float[][] run(ByteBuffer input) {
        if(handle==0) throw new IllegalStateException("Closed RKNN model");
        return calls.run(handle,input);
    }
    static ByteBuffer buffer(int elements) { return ByteBuffer.allocateDirect(elements*4).order(ByteOrder.nativeOrder()); }
    static void crop(Bitmap image,ByteBuffer target,int size,float cx,float cy,float side,float rotation,float mean,float std) {
        NativeLibrary.load();
        cropNative(image,target,size,cx,cy,side,rotation,mean,std);
    }
    static void cropOptimized(Bitmap image,ByteBuffer target,int size,float cx,float cy,float side,float rotation,float mean,float std) {
        NativeLibrary.load();
        cropNative(image,target,size,cx,cy,side,rotation,mean,std);
    }
    static void cropReference(Bitmap image,ByteBuffer target,int size,float cx,float cy,float side,float rotation,float mean,float std) {
        NativeLibrary.load();
        cropReferenceNative(image,target,size,cx,cy,side,rotation,mean,std);
    }
    @Override public synchronized void close() {
        if(closeFailure!=null)throw closeFailure;
        if(handle==0)return;
        long owned=handle;handle=0;
        try { calls.close(owned); }
        catch(Throwable error) {
            closeFailure=error instanceof NativeCleanupUnconfirmed?(NativeCleanupUnconfirmed)error
                    :new NativeCleanupUnconfirmed("RKNN context cleanup unconfirmed",error);
            throw closeFailure;
        }
    }
    private static native long openNative(String path);
    private static native String infoNative(long handle);
    private static native float[][] runNative(long handle,ByteBuffer input);
    private static native void closeNative(long handle);
    private static native void cropNative(Bitmap image,ByteBuffer target,int size,float cx,float cy,float side,float rotation,float mean,float std);
    private static native void cropReferenceNative(Bitmap image,ByteBuffer target,int size,float cx,float cy,float side,float rotation,float mean,float std);
}
