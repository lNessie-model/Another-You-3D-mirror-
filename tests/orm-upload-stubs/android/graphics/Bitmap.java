package android.graphics;

import java.awt.image.BufferedImage;

/** Host fixture only: ImageIO is not a claim about Android Bitmap decode equivalence. */
public final class Bitmap {
    public enum Config { ARGB_8888 }
    final BufferedImage image;
    public boolean recycled;
    public static Bitmap last;
    public Bitmap(BufferedImage image){this.image=image;last=this;}
    public int getWidth(){return image.getWidth();}
    public int getHeight(){return image.getHeight();}
    public Config getConfig(){return Config.ARGB_8888;}
    public void getPixels(int[] output,int offset,int stride,int x,int y,int width,int height){
        if(recycled)throw new IllegalStateException("recycled");image.getRGB(x,y,width,height,output,offset,stride);
    }
    public void recycle(){recycled=true;}
}
