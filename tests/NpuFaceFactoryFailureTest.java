package com.mirror.bench;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.File;
import java.io.IOException;

/** Actual pipeline constructor; model and asset boundaries are explicit host fixtures. */
public final class NpuFaceFactoryFailureTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    private static Throwable create(Context context){
        try{new NpuFacePipeline(context);}
        catch(Throwable error){checks++;return error;}
        throw new AssertionError("Expected constructor failure");
    }
    public static void main(String[] args){
        Context context=new Context(new File(args[0]),new AssetManager());
        OutOfMemoryError original=new OutOfMemoryError("mesh init");
        AssertionError closeError=new AssertionError("detector close");
        RknnModel.resetFactory(original,closeError);
        Throwable result=create(context);
        check(result==original&&RknnModel.factoryOpens==2&&RknnModel.factoryCloses==1);
        check(result.getSuppressed().length==1&&result.getSuppressed()[0] instanceof NativeCleanupUnconfirmed);
        check(result.getSuppressed()[0].getCause()==closeError&&NativeCleanupUnconfirmed.contains(result));
        IllegalStateException cleanOriginal=new IllegalStateException("mesh init clean close");
        RknnModel.resetFactory(cleanOriginal,null);result=create(context);
        check(result==cleanOriginal&&RknnModel.factoryCloses==1&&!NativeCleanupUnconfirmed.contains(result));
        NativeCleanupUnconfirmed nativeInit=new NativeCleanupUnconfirmed("native init destroy failed");
        RknnModel.resetFactory(nativeInit,null);result=create(context);
        check(result==nativeInit&&RknnModel.factoryCloses==1&&NativeCleanupUnconfirmed.contains(result));
        AssetManager assets=new AssetManager();IOException assetError=new IOException("asset failure");assets.failure=assetError;
        RknnModel.resetFactory(null,null);result=create(new Context(new File(args[0]),assets));
        check(result==assetError&&RknnModel.factoryOpens==0&&RknnModel.factoryCloses==0&&!NativeCleanupUnconfirmed.contains(result));
        System.out.println("NPU pipeline constructor checks: "+checks+"; actual constructor, fake assets/models, no Android/native execution");
    }
}
