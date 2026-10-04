package com.mirror.bench;

import android.opengl.Matrix;

/** Host Matrix fixture tests caching/keys/order; the device diagnostic repeats these with Android Matrix. */
public final class AvatarCameraProjectionCacheTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    private static void rejects(Runnable operation){try{operation.run();throw new AssertionError("Accepted invalid cache access");}catch(IllegalArgumentException|IllegalStateException expected){checks++;}}
    public static void main(String[] args){
        AvatarCameraProjectionCache cache=new AvatarCameraProjectionCache();float[] actual=new float[64],expected=new float[64];
        rejects(()->cache.copyGroup(20,1200,1920,0,4,actual));
        int[][] dimensions={{1200,1920},{1920,1200},{1200,1600},{2400,3840},{1,1}};
        for(int views:new int[]{1,4,16,20,24,32})for(int[] size:dimensions){
            check(cache.prepare(views,size[0],size[1]));check(!cache.prepare(views,size[0],size[1]));
            int group=views==1?1:4;
            for(int base=0;base<views;base+=group){
                original(views,size[0],size[1],base,group,expected);cache.copyGroup(views,size[0],size[1],base,group,actual);
                for(int i=0;i<group*16;i++)check(Float.floatToRawIntBits(actual[i])==Float.floatToRawIntBits(expected[i]));
            }
        }
        cache.prepare(20,1200,1920);long builds=cache.builds();
        for(int i=0;i<100_000;i++){check(!cache.prepare(20,1200,1920));cache.copyGroup(20,1200,1920,(i%5)*4,4,actual);}
        check(cache.builds()==builds);check(cache.capacityFloats()==32*16);
        actual[0]=Float.NaN;cache.copyGroup(20,1200,1920,0,4,actual);check(Float.isFinite(actual[0]));
        rejects(()->cache.copyGroup(16,1200,1920,0,4,actual));rejects(()->cache.copyGroup(20,2400,3840,0,4,actual));
        rejects(()->cache.copyGroup(20,1200,1600,0,4,actual));rejects(()->cache.copyGroup(20,1200,1920,1,4,actual));
        rejects(()->cache.copyGroup(20,1200,1920,20,4,actual));rejects(()->cache.copyGroup(20,1200,1920,0,2,actual));
        rejects(()->cache.copyGroup(20,1200,1920,0,4,new float[63]));rejects(()->cache.copyGroup(20,1200,1920,0,4,null));
        rejects(()->cache.prepare(33,1200,1920));rejects(()->cache.prepare(0,1200,1920));rejects(()->cache.prepare(20,0,1920));
        cache.copyGroup(20,1200,1920,0,4,actual);check(Float.isFinite(actual[0]));
        cache.invalidate();rejects(()->cache.copyGroup(20,1200,1920,0,4,actual));
        check(cache.prepare(20,1200,1920));check(cache.builds()==builds+1);
        var controls=SceneViewSettings.DEFAULT.withValue(7,.8f).withValue(8,.6f);
        check(cache.prepare(20,1200,1920,controls));check(!cache.prepare(20,1200,1920,controls));
        cache.copyGroup(20,1200,1920,0,4,actual);
        float[] view=new float[16],projection=new float[16],vp=new float[16];
        for(int v=0;v<4;v++){
            float eye=(v/19f-.5f)*.8f,shift=-eye*.1f/2.4f;
            Matrix.setLookAtM(view,0,eye,0,3,eye,0,0,0,1,0);
            Matrix.frustumM(projection,0,-.052f*.625f+shift,.052f*.625f+shift,-.052f,.052f,.1f,10);
            Matrix.multiplyMM(vp,0,projection,0,view,0);
            for(int i=0;i<16;i++)check(Float.floatToRawIntBits(vp[i])==Float.floatToRawIntBits(actual[v*16+i]));
        }
        check(cache.prepare(20,1200,1920,controls.withValue(8,.5f)));check(cache.prepare(20,1200,1920,controls.withValue(7,.7f)));
        System.out.println("AvatarCameraProjectionCacheTest: "+checks+" checks passed (host Matrix fixture, not Android native implementation)");
    }
    private static void original(int views,int width,int height,int base,int group,float[] destination){
        float[] view=new float[16],projection=new float[16],mvp=new float[16];float aspect=(float)width/height;
        for(int relative=0;relative<group;relative++){
            float eye=views==1?0:((base+relative)/(float)(views-1)-.5f)*.4f;
            Matrix.setLookAtM(view,0,eye,0,3,eye,0,0,0,1,0);
            float near=.1f,half=.052f,shift=-eye*near/3;
            Matrix.frustumM(projection,0,-half*aspect+shift,half*aspect+shift,-half,half,near,10);
            Matrix.multiplyMM(mvp,0,projection,0,view,0);System.arraycopy(mvp,0,destination,relative*16,16);
        }
    }
}
