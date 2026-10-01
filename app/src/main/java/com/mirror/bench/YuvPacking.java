package com.mirror.bench;

import java.nio.ByteBuffer;

/** Packs full-frame, even-sized Camera2 YUV_420_888 into NV21. */
final class YuvPacking {
    static void toNv21(int w,int h,ByteBuffer y,int yr,int yp,ByteBuffer u,int ur,int up,
                       ByteBuffer v,int vr,int vp,byte[] out) {
        if((w&1)!=0||(h&1)!=0||out.length!=w*h*3/2) throw new IllegalArgumentException("Invalid NV21 size");
        int y0=y.position(),u0=u.position(),v0=v.position();
        for(int row=0;row<h;row++) {
            if(yp==1) {
                ByteBuffer line=y.duplicate(); line.position(y0+row*yr); line.get(out,row*w,w);
            } else for(int x=0;x<w;x++) out[row*w+x]=y.get(y0+row*yr+x*yp);
        }
        int offset=w*h;
        for(int row=0;row<h/2;row++) for(int x=0;x<w/2;x++) {
            out[offset++]=v.get(v0+row*vr+x*vp);
            out[offset++]=u.get(u0+row*ur+x*up);
        }
    }
}
