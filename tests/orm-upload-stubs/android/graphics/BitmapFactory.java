package android.graphics;

import java.io.InputStream;
import javax.imageio.ImageIO;

public final class BitmapFactory {
    public static final class Options {
        public boolean inScaled,inPremultiplied;
        public Bitmap.Config inPreferredConfig;
    }
    public static Bitmap decodeStream(InputStream input,android.graphics.Rect ignored,Options options){
        if(options.inScaled||options.inPremultiplied||options.inPreferredConfig!=Bitmap.Config.ARGB_8888)throw new AssertionError("decode flags");
        try{return new Bitmap(ImageIO.read(input));}catch(java.io.IOException e){return null;}
    }
}
