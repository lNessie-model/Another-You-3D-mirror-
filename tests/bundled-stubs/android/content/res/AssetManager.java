package android.content.res;
import java.io.IOException;
import java.io.InputStream;
/** Host boundary only; production compiles against the real Android SDK. */
public abstract class AssetManager {
    public abstract InputStream open(String name) throws IOException;
}
