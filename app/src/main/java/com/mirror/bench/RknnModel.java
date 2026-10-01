package com.mirror.bench;

import android.graphics.Bitmap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.json.JSONObject;

/** Persistent ordinary-app RKNN execution; independent of the stock NPU enum. */
final class RknnModel implements AutoCloseable {
    static { System.loadLibrary("rknnrt"); System.loadLibrary("mirror_rknn_face"); }
    private long handle;
    final JSONObject info;
    RknnModel(String path) throws Exception {
        handle=openNative(path);
        try { info=new JSONObject(infoNative(handle)); }
        catch(Throwable error) { close(); throw error; }
    }
    synchronized float[][] run(ByteBuffer input) {
        if(handle==0) throw new IllegalStateException("Closed RKNN model");
        return runNative(handle,input);
    }
    static ByteBuffer buffer(int elements) { return ByteBuffer.allocateDirect(elements*4).order(ByteOrder.nativeOrder()); }
    static void crop(Bitmap image,ByteBuffer target,int size,float cx,float cy,float side,float rotation,float mean,float std) {
        cropNative(image,target,size,cx,cy,side,rotation,mean,std);
    }
    @Override public synchronized void close() { if(handle!=0) { closeNative(handle); handle=0; } }
    private static native long openNative(String path);
    private static native String infoNative(long handle);
    private static native float[][] runNative(long handle,ByteBuffer input);
    private static native void closeNative(long handle);
    private static native void cropNative(Bitmap image,ByteBuffer target,int size,float cx,float cy,float side,float rotation,float mean,float std);
}
