package com.mirror.bench;

import android.graphics.Bitmap;
import java.nio.ByteBuffer;

/** App-local Rockchip NDK library; existing /dev/rga driver is left unchanged. */
final class RgaConvert {
    static { System.loadLibrary("mirror_accel"); }
    static native void convert(byte[] nv21,Bitmap destination,int width,int height);
    static native String version();
    static native boolean pack(int width,int height,ByteBuffer y,int yr,int yp,
            ByteBuffer u,int ur,int up,ByteBuffer v,int vr,int vp,byte[] output);
    private RgaConvert() {}
}
