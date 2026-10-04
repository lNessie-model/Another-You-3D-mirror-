package android.graphics;
/** Small owned pixel fake for wrapper contracts; actual Android Bitmap is separately device checked. */
public final class Bitmap {
    public enum Config { ARGB_8888 }
    private final int width,height; private final int[] pixels; private boolean recycled;
    public int reads,writes;
    private Bitmap(int w,int h){width=w;height=h;pixels=new int[w*h];}
    public static Bitmap createBitmap(int w,int h,Config c){return new Bitmap(w,h);}
    public int getWidth(){return width;} public int getHeight(){return height;}
    public boolean isRecycled(){return recycled;} public void recycle(){recycled=true;}
    public void getPixels(int[] p,int offset,int stride,int x,int y,int w,int h){
        if(recycled)throw new IllegalStateException();reads++;
        for(int r=0;r<h;r++)System.arraycopy(pixels,(y+r)*width+x,p,offset+r*stride,w);
    }
    public void setPixels(int[] p,int offset,int stride,int x,int y,int w,int h){
        if(recycled)throw new IllegalStateException();writes++;
        for(int r=0;r<h;r++)System.arraycopy(p,offset+r*stride,pixels,(y+r)*width+x,w);
    }
}
