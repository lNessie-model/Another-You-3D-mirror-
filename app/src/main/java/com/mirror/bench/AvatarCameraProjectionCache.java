package com.mirror.bench;

import android.opengl.Matrix;

/** GL-owner-only fixed camera VP cache. Contains no model/head transform or GL object names. */
final class AvatarCameraProjectionCache {
    private final float[] matrices=new float[32*16],view=new float[16],projection=new float[16],mvp=new float[16];
    private int views,width,height,aspectBits;
    private int spanBits,zeroBits;
    private boolean ready;
    private long builds;
    /** Uses the exact original float expressions and Android Matrix call order, once per complete key. */
    boolean prepare(int requestedViews,int physicalWidth,int physicalHeight){
        return prepare(requestedViews,physicalWidth,physicalHeight,SceneViewSettings.DEFAULT);
    }
    boolean prepare(int requestedViews,int physicalWidth,int physicalHeight,SceneViewSettings controls){
        if(controls==null)throw new IllegalArgumentException("Camera controls required");
        validate(requestedViews,physicalWidth,physicalHeight);
        if(matches(requestedViews,physicalWidth,physicalHeight)&&spanBits==Float.floatToRawIntBits(controls.cameraSpan)&&zeroBits==Float.floatToRawIntBits(controls.zeroPlane))return false;
        ready=false;float aspect=(float)physicalWidth/physicalHeight;
        for(int v=0;v<requestedViews;v++){
            float eye=controls.eyeAt(v,requestedViews);
            Matrix.setLookAtM(view,0,eye,0,3,eye,0,0,0,1,0);
            float near=.1f,half=.052f,shift=controls.frustumShift(eye);
            Matrix.frustumM(projection,0,-half*aspect+shift,half*aspect+shift,-half,half,near,10);
            Matrix.multiplyMM(mvp,0,projection,0,view,0);
            for(float value:mvp)if(!Float.isFinite(value))throw new IllegalArgumentException("Nonfinite camera matrix");
            System.arraycopy(mvp,0,matrices,v*16,16);
        }
        views=requestedViews;width=physicalWidth;height=physicalHeight;aspectBits=Float.floatToRawIntBits(aspect);
        spanBits=Float.floatToRawIntBits(controls.cameraSpan);zeroBits=Float.floatToRawIntBits(controls.zeroPlane);ready=true;
        if(builds<Long.MAX_VALUE)builds++;
        return true;
    }
    /** No allocation or matrix evaluation. Every access verifies the full physical-size/view key. */
    void copyGroup(int requestedViews,int physicalWidth,int physicalHeight,int base,int count,float[] destination){
        if(!matches(requestedViews,physicalWidth,physicalHeight))throw new IllegalStateException("Camera matrix cache key is absent or stale");
        if((count!=1&&count!=4)||base<0||base>views-count||(count==4&&base%4!=0)||destination==null||destination.length<count*16)
            throw new IllegalArgumentException("Invalid camera view group or destination");
        System.arraycopy(matrices,base*16,destination,0,count*16);
    }
    void invalidate(){ready=false;}
    long builds(){return builds;}
    int capacityFloats(){return matrices.length;}
    private boolean matches(int requestedViews,int physicalWidth,int physicalHeight){
        return ready&&views==requestedViews&&width==physicalWidth&&height==physicalHeight
                &&aspectBits==Float.floatToRawIntBits((float)physicalWidth/physicalHeight);
    }
    private static void validate(int views,int width,int height){
        if(views<1||views>32||width<=0||height<=0)throw new IllegalArgumentException("Camera cache requires 1..32 views and positive physical dimensions");
    }
}
