package com.mirror.bench;

import android.graphics.Bitmap;
import java.nio.ByteBuffer;
import java.util.List;
import org.json.JSONObject;

/** Host substitution for JNI only. Actual NpuFacePipeline bytecode is exercised. */
final class RknnModel implements AutoCloseable {
    final JSONObject info=new JSONObject();
    private final String name;
    private final List<String> calls;
    Throwable failure;
    static int factoryOpens,factoryCloses;
    static Throwable factoryOpenFailure,factoryCloseFailure;
    static void resetFactory(Throwable openFailure,Throwable closeFailure){
        factoryOpens=0;factoryCloses=0;factoryOpenFailure=openFailure;factoryCloseFailure=closeFailure;
    }
    private static void raise(Throwable error){if(error instanceof Error)throw (Error)error;throw (RuntimeException)error;}
    RknnModel(String path){
        name=path;calls=new java.util.ArrayList<>();factoryOpens++;
        if(factoryOpens==2&&factoryOpenFailure!=null)raise(factoryOpenFailure);
        failure=factoryCloseFailure;
    }
    RknnModel(String name,List<String> calls){this.name=name;this.calls=calls;}
    static ByteBuffer buffer(int elements){return ByteBuffer.allocateDirect(elements*4);}
    static void crop(Bitmap bitmap,ByteBuffer target,int size,float cx,float cy,float side,float rotation,float mean,float std){}
    static void cropReference(Bitmap bitmap,ByteBuffer target,int size,float cx,float cy,float side,float rotation,float mean,float std){}
    float[][] run(ByteBuffer input){throw new AssertionError("No JNI/inference in stop checks");}
    public void close(){factoryCloses++;calls.add(name);if(failure instanceof Error)throw (Error)failure;if(failure instanceof RuntimeException)throw (RuntimeException)failure;}
}
