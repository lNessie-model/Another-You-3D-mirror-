package com.mirror.bench;

import android.content.res.AssetManager;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Capture the actual Activity's dispatch arguments. No GL, model loading or pixel comparison. */
final class VerificationCalls {
    static String route;
    static int views,width,height,physicalWidth,physicalHeight;
    static float aspect;
    static BooleanSupplier cancellation;
    static Runnable during;
    static JSONObject run(String name,int w,int h,int n,float a,BooleanSupplier cancel)throws Exception {
        route=name;width=w;height=h;views=n;aspect=a;cancellation=cancel;
        if(during!=null)during.run();
        return new JSONObject().put("passed",true).put("views",n);
    }
}
final class AvatarPersistentFboCheck {
    public static JSONObject run(AssetManager assets,int width,int height,int views,float aspect,BooleanSupplier cancel)throws Exception {
        return VerificationCalls.run("persistent",width,height,views,aspect,cancel);
    }
}
final class AvatarCameraProjectionCheck {
    public static JSONObject run(AssetManager assets,int width,int height,int views,int physicalWidth,int physicalHeight,BooleanSupplier cancel)throws Exception {
        VerificationCalls.physicalWidth=physicalWidth;VerificationCalls.physicalHeight=physicalHeight;
        return VerificationCalls.run("cached",width,height,views,(float)physicalWidth/physicalHeight,cancel);
    }
}
final class AvatarBatchCheck {
    public static JSONObject run(AssetManager assets,int width,int height,int views,float aspect,BooleanSupplier cancel)throws Exception {
        return VerificationCalls.run("batch",width,height,views,aspect,cancel);
    }
}
final class AvatarMultiviewCheck {
    public static JSONObject runSixteen(AssetManager assets,BooleanSupplier cancel)throws Exception {
        return VerificationCalls.run("sixteen",400,720,16,1200f/1920f,cancel);
    }
    public static JSONObject runSixteen(AssetManager assets,int width,int height,BooleanSupplier cancel)throws Exception {
        return VerificationCalls.run("sixteen",width,height,16,1200f/1920f,cancel);
    }
    public static JSONObject run(AssetManager assets,int width,int height,int views,float aspect,BooleanSupplier cancel)throws Exception {
        return VerificationCalls.run("multiview",width,height,views,aspect,cancel);
    }
}
final class AvatarGpuScene {
    static Runnable created;
    static RuntimeException failure;
    static AvatarGpuScene builtin(AssetManager assets,boolean worker){
        if(created!=null)created.run();
        if(failure!=null)throw failure;
        return new AvatarGpuScene();
    }
    void prepare(float[] weights,float[] angles){}
    void draw(float[] matrix,float point,float aspect){}
    JSONObject status(){return new JSONObject();}
}
