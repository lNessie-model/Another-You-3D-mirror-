package com.mirror.bench;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.media.Image;
import android.renderscript.*;

/** Native conversion available on this Android 11 target; not a future Android API choice. */
@SuppressWarnings("deprecation")
final class YuvConverter implements AutoCloseable {
    private final RenderScript rs;
    private final ScriptIntrinsicYuvToRGB script;
    private final Allocation input,output;
    private final byte[] nv21;
    final Bitmap bitmap;
    YuvConverter(Context context,int width,int height) {
        rs=RenderScript.create(context);
        script=ScriptIntrinsicYuvToRGB.create(rs,Element.U8_4(rs));
        nv21=new byte[width*height*3/2];
        Type type=new Type.Builder(rs,Element.U8(rs)).setX(width).setY(height).setYuvFormat(ImageFormat.NV21).create();
        input=Allocation.createTyped(rs,type,Allocation.USAGE_SCRIPT);
        bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        output=Allocation.createFromBitmap(rs,bitmap,Allocation.MipmapControl.MIPMAP_NONE,Allocation.USAGE_SCRIPT);
        script.setInput(input);
    }
    void convert(Image image) {
        Image.Plane[] p=image.getPlanes();
        YuvPacking.toNv21(image.getWidth(),image.getHeight(),p[0].getBuffer(),p[0].getRowStride(),p[0].getPixelStride(),
                p[1].getBuffer(),p[1].getRowStride(),p[1].getPixelStride(),p[2].getBuffer(),p[2].getRowStride(),p[2].getPixelStride(),nv21);
        input.copyFrom(nv21); script.forEach(output); output.copyTo(bitmap);
    }
    @Override public void close() {
        script.destroy(); input.destroy(); output.destroy(); rs.destroy();
        if(!bitmap.isRecycled()) bitmap.recycle();
    }
}
