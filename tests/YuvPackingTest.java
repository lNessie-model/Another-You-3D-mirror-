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
        System.out.println("PASS: 4 YUV packing cases");
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
