package android.opengl;
import android.graphics.Bitmap;
/** Texture decode/upload ownership fixture only; no GPU sampling simulation. */
public final class GLUtils {
    public static void texSubImage2D(int target,int level,int x,int y,Bitmap bitmap,int format,int type){
        if(bitmap.recycled||format!=0x1908||type!=0x1401)throw new AssertionError("RGBA source contract");
    }
}
