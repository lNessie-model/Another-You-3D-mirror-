package com.mirror.bench;

import android.graphics.Bitmap;

/** Worker-owned camera pixel transform. Identity borrows the input with no pixel copy. */
final class CameraBitmapNormalizer implements AutoCloseable {
    private final CameraInputTransform transform;
    private final int[] sourcePixels,normalizedPixels;
    private final Bitmap output;
    private boolean closed;
    CameraBitmapNormalizer(int width,int height,int clockwiseDegrees,boolean reflectAfterRotation){
        transform=new CameraInputTransform(width,height,clockwiseDegrees,reflectAfterRotation);
        if(transform.isIdentity()){sourcePixels=normalizedPixels=null;output=null;}
        else {
            sourcePixels=new int[width*height];normalizedPixels=new int[width*height];
            output=Bitmap.createBitmap(transform.outputWidth(),transform.outputHeight(),Bitmap.Config.ARGB_8888);
        }
    }
    Bitmap apply(Bitmap source){
        if(closed)throw new IllegalStateException("Camera normalizer is closed");
        if(source==null||source.isRecycled()||source.getWidth()!=transform.sourceWidth()||source.getHeight()!=transform.sourceHeight())
            throw new IllegalArgumentException("Camera bitmap dimensions or lifetime do not match the configured input");
        if(output==null)return source;
        source.getPixels(sourcePixels,0,transform.sourceWidth(),0,0,transform.sourceWidth(),transform.sourceHeight());
        transform.applyArgb(sourcePixels,normalizedPixels);
        output.setPixels(normalizedPixels,0,transform.outputWidth(),0,0,transform.outputWidth(),transform.outputHeight());
        return output;
    }
    @Override public void close(){if(closed)return;closed=true;if(output!=null&&!output.isRecycled())output.recycle();}
}
