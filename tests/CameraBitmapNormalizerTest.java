package com.mirror.bench;
import android.graphics.Bitmap;
import java.util.Arrays;
public final class CameraBitmapNormalizerTest {
    private static int checks;
    private static void require(boolean b,String detail){checks++;if(!b)throw new AssertionError(detail);}
    private static void rejects(Runnable body){boolean failed=false;try{body.run();}catch(IllegalArgumentException|IllegalStateException expected){failed=true;}require(failed,"Expected rejection");}
    public static void main(String[] args){
        int[][] expected={{1,2,3,4,5,6},{3,2,1,6,5,4},{4,1,5,2,6,3},{1,4,2,5,3,6},
                {6,5,4,3,2,1},{4,5,6,1,2,3},{3,6,2,5,1,4},{6,3,5,2,4,1}};
        int row=0;
        for(int angle:new int[]{0,90,180,270})for(boolean reflect:new boolean[]{false,true}){
            Bitmap src=Bitmap.createBitmap(3,2,Bitmap.Config.ARGB_8888);src.setPixels(new int[]{1,2,3,4,5,6},0,3,0,0,3,2);
            CameraBitmapNormalizer normalizer=new CameraBitmapNormalizer(3,2,angle,reflect);
            Bitmap output=normalizer.apply(src);
            require(output.getWidth()==(angle%180==0?3:2)&&output.getHeight()==(angle%180==0?2:3),"dimensions");
            if(angle==0&&!reflect){require(output==src,"identity object");require(src.reads==0&&src.writes==1,"identity zero copy");}
            else require(output!=src,"owned transformed image");
            int[] values=new int[6];output.getPixels(values,0,output.getWidth(),0,0,output.getWidth(),output.getHeight());
            require(Arrays.equals(values,expected[row++]),"explicit orientation oracle "+angle+" / "+reflect);
            require(normalizer.apply(src)==output,"bounded output reuse");
            rejects(()->normalizer.apply(Bitmap.createBitmap(2,3,Bitmap.Config.ARGB_8888)));
            normalizer.close();normalizer.close();require(!src.isRecycled(),"borrowed source ownership");
            require(output==src||output.isRecycled(),"owned output disposal");rejects(()->normalizer.apply(src));src.recycle();
        }
        rejects(()->new CameraBitmapNormalizer(3,2,45,false));
        CameraBitmapNormalizer normalizer=new CameraBitmapNormalizer(3,2,0,false);rejects(()->normalizer.apply(null));normalizer.close();
        System.out.println("CameraBitmapNormalizerTest: "+checks+" checks passed; Bitmap boundary fake, not Android implementation.");
    }
}
