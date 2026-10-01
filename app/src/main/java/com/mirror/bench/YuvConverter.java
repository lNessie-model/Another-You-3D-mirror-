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
    private final YuvPacking.Workspace packing=new YuvPacking.Workspace();
    private final boolean rga,nativePacking;
    private final int width,height;
    final Bitmap bitmap;
    YuvConverter(Context context,int width,int height) {
        this(context,width,height,false);
    }
    YuvConverter(Context context,int width,int height,boolean rga) {
        this(context,width,height,rga,false);
    }
    YuvConverter(Context context,int width,int height,boolean rga,boolean nativePacking) {
        this.width=width; this.height=height; this.rga=rga; this.nativePacking=nativePacking;
        bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        nv21=new byte[width*height*3/2];
        if(rga) {
            RgaConvert.version(); // Fail explicitly during initialization if the library cannot load.
            rs=null; script=null; input=null; output=null; return;
        }
        rs=RenderScript.create(context);
        script=ScriptIntrinsicYuvToRGB.create(rs,Element.U8_4(rs));
        Type type=new Type.Builder(rs,Element.U8(rs)).setX(width).setY(height).setYuvFormat(ImageFormat.NV21).create();
        input=Allocation.createTyped(rs,type,Allocation.USAGE_SCRIPT);
        output=Allocation.createFromBitmap(rs,bitmap,Allocation.MipmapControl.MIPMAP_NONE,Allocation.USAGE_SCRIPT);
        script.setInput(input);
    }
    void convert(Image image) {
        Image.Plane[] p=image.getPlanes();
        if(nativePacking&&RgaConvert.pack(image.getWidth(),image.getHeight(),p[0].getBuffer(),p[0].getRowStride(),p[0].getPixelStride(),
                p[1].getBuffer(),p[1].getRowStride(),p[1].getPixelStride(),p[2].getBuffer(),p[2].getRowStride(),p[2].getPixelStride(),nv21)) {
            convertNv21(nv21); return;
        }
        YuvPacking.toNv21(image.getWidth(),image.getHeight(),p[0].getBuffer(),p[0].getRowStride(),p[0].getPixelStride(),
                p[1].getBuffer(),p[1].getRowStride(),p[1].getPixelStride(),p[2].getBuffer(),p[2].getRowStride(),p[2].getPixelStride(),nv21,packing);
        convertNv21(nv21);
    }
    void convertNv21(byte[] pixels) {
        if(pixels.length!=nv21.length) throw new IllegalArgumentException("Wrong NV21 frame size");
        if(rga) { RgaConvert.convert(pixels,bitmap,width,height); return; }
        input.copyFrom(pixels); script.forEach(output); output.copyTo(bitmap);
    }
    void convert(FrameInput.Frame frame) {
        if(frame.image!=null) convert(frame.image); else convertNv21(frame.nv21);
    }
    @Override public void close() {
        if(!rga) { script.destroy(); input.destroy(); output.destroy(); rs.destroy(); }
        if(!bitmap.isRecycled()) bitmap.recycle();
    }
}
