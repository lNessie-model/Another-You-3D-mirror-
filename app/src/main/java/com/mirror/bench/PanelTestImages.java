package com.mirror.bench;

/** Deterministic, genuinely distinct 01..32 view cards and lossless RGB view codes. */
final class PanelTestImages {
    private static final int[] DIGITS={0x3f,0x06,0x5b,0x4f,0x66,0x6d,0x7d,0x07,0x7f,0x6f};
    static int code(int view,int channel) {
        if(view<0||view>=32||channel<0||channel>2)throw new IllegalArgumentException("Invalid view/channel");
        return channel==0?17+7*view:channel==1?31+5*view:47+3*view;
    }
    /** OpenGL bottom-up RGBA. Two large seven-segment digits label the 1-based view number. */
    static byte[] card(int view,int width,int height) {
        if(view<0||view>=32||width<32||height<32||width>1024||height>2048)
            throw new IllegalArgumentException("Invalid view card size");
        byte[] bytes=new byte[width*height*4];
        float hue=view*.61803398875f; hue-=Math.floor(hue);
        float h=hue*6, x=1-Math.abs(h%2-1); int sector=(int)h;
        float r=sector==0||sector==5?1:sector==1||sector==4?x:0;
        float g=sector==1||sector==2?1:sector==0||sector==3?x:0;
        float b=sector==3||sector==4?1:sector==2||sector==5?x:0;
        int red=35+(int)(r*145),green=35+(int)(g*145),blue=35+(int)(b*145);
        for(int y=0;y<height;y++)for(int px=0;px<width;px++) {
            float nx=(px+.5f)/width, ny=1-(y+.5f)/height; // text measured from top
            int digit=nx<.5f?(view+1)/10:(view+1)%10;
            float dx=(nx-(nx<.5f?.10f:.54f))/.36f,dy=(ny-.29f)/.42f;
            boolean ink=digitPixel(digit,dx,dy);
            boolean border=nx<.025f||nx>.975f||ny<.02f||ny>.98f;
            // Upward arrow and a different bottom marker make the card orientation visible.
            boolean arrow=(ny>.08f&&ny<.20f&&Math.abs(nx-.5f)<.025f)
                    ||(ny>.06f&&ny<.12f&&Math.abs(nx-.5f)<(ny-.06f)*1.5f);
            boolean bottom=ny>.88f&&ny<.91f&&nx>.25f&&nx<.75f;
            int offset=(y*width+px)*4;
            bytes[offset]=(byte)(ink||arrow||border?255:bottom?0:red);
            bytes[offset+1]=(byte)(ink||arrow||border?255:bottom?0:green);
            bytes[offset+2]=(byte)(ink||arrow||border?255:bottom?0:blue);
            bytes[offset+3]=(byte)255;
        }
        return bytes;
    }
    private static boolean digitPixel(int digit,float x,float y) {
        if(x<0||x>1||y<0||y>1)return false;
        int mask=DIGITS[digit]; float thick=.13f;
        return ((mask&1)!=0&&y<thick&&x>.1f&&x<.9f)
                ||((mask&2)!=0&&x>1-thick&&y>.07f&&y<.5f)
                ||((mask&4)!=0&&x>1-thick&&y>.5f&&y<.93f)
                ||((mask&8)!=0&&y>1-thick&&x>.1f&&x<.9f)
                ||((mask&16)!=0&&x<thick&&y>.5f&&y<.93f)
                ||((mask&32)!=0&&x<thick&&y>.07f&&y<.5f)
                ||((mask&64)!=0&&Math.abs(y-.5f)<thick/2&&x>.1f&&x<.9f);
    }
}
