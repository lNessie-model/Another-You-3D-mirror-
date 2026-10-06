package android.opengl;

import android.graphics.Bitmap;

public final class GLUtils {
    public static void texSubImage2D(int target,int level,int x,int y,Bitmap bitmap,int format,int type){
        GLES30.rgbaUploads++;GLES30.calls.add("rgba");
        if(format!=0x1908||type!=0x1401)throw new AssertionError("RGBA bytes contract");
        if(GLES30.failRgba)throw new IllegalStateException("injected RGBA upload failure");
    }
}
