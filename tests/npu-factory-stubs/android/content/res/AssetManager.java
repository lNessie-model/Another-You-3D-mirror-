package android.content.res;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
public class AssetManager {
    public IOException failure;
    public InputStream open(String name)throws IOException{
        if(failure!=null)throw failure;
        return new ByteArrayInputStream(new byte[]{1,2,3,4});
    }
}
