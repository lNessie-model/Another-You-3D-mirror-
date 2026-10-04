package com.mirror.bench;

import android.content.res.AssetManager;
import android.opengl.Matrix;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Device diagnostic: real Android Matrix bit comparison, then same-pose strict RGBA comparison. */
public final class AvatarCameraProjectionCheck {
    private AvatarCameraProjectionCheck(){}
    public static JSONObject run(AssetManager assets,int width,int height,int views,int physicalWidth,int physicalHeight,BooleanSupplier cancelled)throws Exception {
        return AvatarMultiviewCheck.runCameraComparison(assets,width,height,views,physicalWidth,physicalHeight,cancelled);
    }
    static boolean pixelsMatch(AvatarPixelComparison.Stats stats){return stats.passed()&&stats.rgbMismatches==0&&stats.alphaMismatches==0;}
    /** Original per-group renderer expressions, deliberately separate from the cache implementation. */
    static void originalGroup(int views,int width,int height,int base,int group,float[] result,float[] view,float[] projection,float[] mvp){
        float aspect=(float)width/height;
        for(int relative=0;relative<group;relative++){
            float eye=views==1?0:((base+relative)/(float)(views-1)-.5f)*.4f;
            Matrix.setLookAtM(view,0,eye,0,3,eye,0,0,0,1,0);
            float near=.1f,half=.052f,shift=-eye*near/3;
            Matrix.frustumM(projection,0,-half*aspect+shift,half*aspect+shift,-half,half,near,10);
            Matrix.multiplyMM(mvp,0,projection,0,view,0);
            System.arraycopy(mvp,0,result,relative*16,16);
        }
    }
    static JSONObject matrixGate()throws Exception {
        AvatarCameraProjectionCache cache=new AvatarCameraProjectionCache();JSONArray cases=new JSONArray();
        float[] expected=new float[64],actual=new float[64],view=new float[16],projection=new float[16],mvp=new float[16];
        long mismatches=0,values=0;int invalidationChecks=0;
        int[][] sizes={{1200,1920},{1920,1200},{1200,1600},{2400,3840},{1,1}};
        for(int views:new int[]{1,4,16,20,32})for(int[] size:sizes){
            cache.prepare(views,size[0],size[1]);int group=views==1?1:4;long before=mismatches;
            for(int base=0;base<views;base+=group){
                originalGroup(views,size[0],size[1],base,group,expected,view,projection,mvp);
                cache.copyGroup(views,size[0],size[1],base,group,actual);
                for(int i=0;i<group*16;i++){values++;if(!Float.isFinite(expected[i])||Float.floatToRawIntBits(expected[i])!=Float.floatToRawIntBits(actual[i]))mismatches++;}
            }
            cache.invalidate();boolean rejected=false;
            try{cache.copyGroup(views,size[0],size[1],0,group,actual);}catch(IllegalStateException expectedError){rejected=true;}
            if(!rejected)throw new IllegalStateException("Invalidated camera cache was reused");invalidationChecks++;
            cache.prepare(views,size[0],size[1]);
            originalGroup(views,size[0],size[1],0,group,expected,view,projection,mvp);cache.copyGroup(views,size[0],size[1],0,group,actual);
            for(int i=0;i<group*16;i++){values++;if(Float.floatToRawIntBits(expected[i])!=Float.floatToRawIntBits(actual[i]))mismatches++;}
            cases.put(new JSONObject().put("views",views).put("physical_width",size[0]).put("physical_height",size[1])
                    .put("aspect",(float)size[0]/size[1]).put("bit_mismatches",mismatches-before));
        }
        return new JSONObject().put("passed",mismatches==0).put("bit_mismatches",mismatches).put("compared_float_values",values)
                .put("invalidation_checks",invalidationChecks).put("cases",cases)
                .put("scope","cache versus independent original per-group expressions using the running platform android.opengl.Matrix; all views and physical-size keys; invalidate/rebuild repeated");
    }
}
