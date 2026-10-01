package com.mirror.bench;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Plain-Java regression checks for real Camera2 row/pixel strides. */
public final class YuvPackingTest {
    public static void main(String[] args) {
        check("planar", 4, 2, plane(1,2,3,4,5,6,7,8),4,1,
                plane(21,22),2,1,plane(31,32),2,1,
                new byte[]{1,2,3,4,5,6,7,8,31,21,32,22});
        check("padded rows",2,4,plane(1,2,99,3,4,99,5,6,99,7,8),3,1,
                plane(21,99,22),2,1,plane(31,99,32),2,1,
                new byte[]{1,2,3,4,5,6,7,8,31,21,32,22});
        // Android chroma planes can overlap and omit the final padding byte.
        ByteBuffer vu=plane(31,21,32,22);
        ByteBuffer u=vu.duplicate(); u.position(1);
        check("interleaved chroma",4,2,plane(1,2,3,4,5,6,7,8),4,1,
                u,4,2,vu,4,2,new byte[]{1,2,3,4,5,6,7,8,31,21,32,22});
        ByteBuffer y=plane(99,1,2,3,4,5,6,7,8); y.position(1);
        check("buffer position",4,2,y,4,1,plane(21,22),2,1,plane(31,32),2,1,
                new byte[]{1,2,3,4,5,6,7,8,31,21,32,22});
        YuvPacking.Workspace workspace=new YuvPacking.Workspace();
        stridedDirect(8,6,1,2,3,workspace);
        stridedDirect(4,2,2,1,1,workspace);
        System.out.println("PASS: 6 YUV packing cases including reusable direct-plane rows");
    }
    private static void stridedDirect(int w,int h,int yp,int up,int vp,YuvPacking.Workspace workspace) {
        int yr=w*yp+3,ur=(w/2)*up+5,vr=(w/2)*vp+7;
        ByteBuffer y=ByteBuffer.allocateDirect(4+(h-1)*yr+(w-1)*yp+1);
        ByteBuffer u=ByteBuffer.allocateDirect(4+(h/2-1)*ur+(w/2-1)*up+1);
        ByteBuffer v=ByteBuffer.allocateDirect(4+(h/2-1)*vr+(w/2-1)*vp+1);
        byte[] expected=new byte[w*h*3/2];
        for(int row=0;row<h;row++) for(int x=0;x<w;x++) {
            byte value=(byte)(row*w+x+1); y.put(4+row*yr+x*yp,value); expected[row*w+x]=value;
        }
        int output=w*h;
        for(int row=0;row<h/2;row++) for(int x=0;x<w/2;x++) {
            byte a=(byte)(80+row*(w/2)+x),b=(byte)(160+row*(w/2)+x);
            u.put(4+row*ur+x*up,a); v.put(4+row*vr+x*vp,b);
            expected[output++]=b; expected[output++]=a;
        }
        y.position(4); u.position(4); v.position(4);
        byte[] actual=new byte[expected.length];
        YuvPacking.toNv21(w,h,y,yr,yp,u,ur,up,v,vr,vp,actual,workspace);
        if(!Arrays.equals(actual,expected)) throw new AssertionError("Strided direct planes differ");
        if(y.position()!=4||u.position()!=4||v.position()!=4) throw new AssertionError("Source buffer positions changed");
    }
    private static ByteBuffer plane(int... values) {
        ByteBuffer result=ByteBuffer.allocate(values.length);
        for(int value:values) result.put((byte)value);
        result.flip(); return result;
    }
    private static void check(String name,int w,int h,ByteBuffer y,int yr,int yp,
                              ByteBuffer u,int ur,int up,ByteBuffer v,int vr,int vp,byte[] expected) {
        byte[] actual=new byte[w*h*3/2];
        YuvPacking.toNv21(w,h,y,yr,yp,u,ur,up,v,vr,vp,actual);
        if(!Arrays.equals(actual,expected)) throw new AssertionError(name+": "+Arrays.toString(actual));
    }
}
