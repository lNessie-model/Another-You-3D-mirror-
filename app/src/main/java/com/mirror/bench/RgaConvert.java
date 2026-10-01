package com.mirror.bench;

import android.graphics.Bitmap;

/** App-local Rockchip NDK library; existing /dev/rga driver is left unchanged. */
final class RgaConvert {
    static { System.loadLibrary("mirror_accel"); }
    static native void convert(byte[] nv21,Bitmap destination,int width,int height);
    static native String version();
    private RgaConvert() {}
}
