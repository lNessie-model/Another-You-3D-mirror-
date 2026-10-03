package com.mirror.bench;

import java.util.Arrays;

/** Integer-pixel ground truth, independent of Android Bitmap, YUV conversion and camera metadata. */
public final class CameraInputTransformTest {
    private static int checks;
    public static void main(String[] args) {
        int[] source={1,2,3,4,5,6}; // width=3, height=2; all corners distinguishable
        int[][] expected={ {1,2,3,4,5,6}, {4,1,5,2,6,3}, {6,5,4,3,2,1}, {3,6,2,5,1,4} };
        int[][] reflected={ {3,2,1,6,5,4}, {1,4,2,5,3,6}, {4,5,6,1,2,3}, {6,3,5,2,4,1} };
        for(int turns=0;turns<4;turns++)for(boolean reflect:new boolean[]{false,true}) {
            CameraInputTransform t=new CameraInputTransform(3,2,turns*90,reflect);
            check(t.outputWidth()==(turns%2==0?3:2),"rotated width");
            check(t.outputHeight()==(turns%2==0?2:3),"rotated height");
            int[] out=new int[8];Arrays.fill(out,99);t.applyArgb(source,out);
            check(Arrays.equals(Arrays.copyOf(out,6),(reflect?reflected:expected)[turns]),"explicit asymmetric fixture");
            check(out[6]==99&&out[7]==99,"does not overwrite unused buffer capacity");
            check(Arrays.equals(source,new int[]{1,2,3,4,5,6}),"source remains unchanged");
            boolean[] visited=new boolean[6];
            for(int y=0;y<2;y++)for(int x=0;x<3;x++) {
                int index=t.outputIndexForSource(x,y);
                check(!visited[index],"source mapping is bijective");visited[index]=true;
                check(t.sourceIndexForOutput(index%t.outputWidth(),index/t.outputWidth())==y*3+x,"inverse mapping");
                check(out[index]==source[y*3+x],"pixel and coordinate mapping agree");
            }
            rejects(()->t.applyArgb(new int[5],out),"short input");
            rejects(()->t.applyArgb(source,new int[5]),"short output");
            rejects(()->t.sourceIndexForOutput(t.outputWidth(),0),"output boundary");
            rejects(()->t.outputIndexForSource(0,2),"source boundary");
            if(!t.isIdentity()) rejects(()->t.applyArgb(source,source),"alias rejected before any mutation");
        }
        int[] colors={0x00112233,0x80112233,0xffaabbcc,0x40102030};
        CameraInputTransform colorTransform=new CameraInputTransform(2,2,90,false);
        int[] output=new int[4];colorTransform.applyArgb(colors,output);
        check(Arrays.equals(output,new int[]{colors[2],colors[0],colors[3],colors[1]}),"all ARGB bits preserved");
        CameraInputTransform identity=new CameraInputTransform(2,2,0,false);
        identity.applyArgb(colors,colors);
        check(colors[0]==0x00112233,"identity accepts same buffer without changing alpha");
        for(int w:new int[]{1,2,7,640})for(int h:new int[]{1,3,5,480})
            for(int angle:new int[]{0,90,180,270})for(boolean flip:new boolean[]{false,true}) {
                CameraInputTransform t=new CameraInputTransform(w,h,angle,flip);
                for(int i:new int[]{0,w-1,w*(h-1),w*h-1,w*h/2}) {
                    int q=t.outputIndexForSource(i%w,i/w);
                    check(t.sourceIndexForOutput(q%t.outputWidth(),q/t.outputWidth())==i,"rectangular and degenerate-axis edges");
                }
            }
        rejects(()->new CameraInputTransform(0,2,0,false),"zero dimension");
        rejects(()->new CameraInputTransform(2,-1,0,false),"negative dimension");
        rejects(()->new CameraInputTransform(Integer.MAX_VALUE,3,0,false),"overflow budget");
        rejects(()->new CameraInputTransform(2,2,45,false),"non-right-angle rotation");
        rejects(()->new CameraInputTransform(2,2,360,false),"unsupported alias angle");
        rejects(()->identity.applyArgb(null,output),"null source");
        rejects(()->identity.outputIndexForSource(-1,0),"negative source coordinate");
        System.out.println("CameraInputTransformTest: "+checks+" checks; pixel math only, no camera/NPU/Bitmap execution");
    }
    private static void check(boolean good,String label){if(!good)throw new AssertionError(label);checks++;}
    private static void rejects(Runnable action,String label){try{action.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError(label);}
}
