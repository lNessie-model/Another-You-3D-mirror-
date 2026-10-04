package com.mirror.bench;

import java.nio.ByteBuffer;

/** Packs full-frame, even-sized Camera2 YUV_420_888 into NV21. */
final class YuvPacking {
    static final class Workspace { byte[] uRow=new byte[0],vRow=new byte[0]; }
    static void toNv21(int w,int h,ByteBuffer y,int yr,int yp,ByteBuffer u,int ur,int up,
                       ByteBuffer v,int vr,int vp,byte[] out) {
        toNv21(w,h,y,yr,yp,u,ur,up,v,vr,vp,out,new Workspace());
    }
    static void toNv21(int w,int h,ByteBuffer y,int yr,int yp,ByteBuffer u,int ur,int up,
                       ByteBuffer v,int vr,int vp,byte[] out,Workspace scratch) {
        if(w<=0||h<=0||(w&1)!=0||(h&1)!=0||out.length!=w*h*3/2) throw new IllegalArgumentException("Invalid NV21 size");
        int y0=y.position(),u0=u.position(),v0=v.position();
        ByteBuffer yl=y.duplicate(),ul=u.duplicate(),vl=v.duplicate();
        for(int row=0;row<h;row++) {
            if(yp==1) {
                yl.position(y0+row*yr); yl.get(out,row*w,w);
            } else for(int x=0;x<w;x++) out[row*w+x]=y.get(y0+row*yr+x*yp);
        }
        int uSpan=(w/2-1)*up+1,vSpan=(w/2-1)*vp+1;
        if(scratch.uRow.length<uSpan) scratch.uRow=new byte[uSpan];
        if(scratch.vRow.length<vSpan) scratch.vRow=new byte[vSpan];
        int offset=w*h;
        for(int row=0;row<h/2;row++) {
            ul.position(u0+row*ur); ul.get(scratch.uRow,0,uSpan);
            vl.position(v0+row*vr); vl.get(scratch.vRow,0,vSpan);
            for(int x=0;x<w/2;x++) {
                out[offset++]=scratch.vRow[x*vp];
                out[offset++]=scratch.uRow[x*up];
            }
        }
    }
}
