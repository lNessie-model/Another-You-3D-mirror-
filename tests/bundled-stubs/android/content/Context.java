package android.content;
import android.content.res.AssetManager;
/** Host boundary only; no stub is included in the Android source set. */
public abstract class Context {
    public static final int MODE_PRIVATE=0;
    public abstract AssetManager getAssets();
    public abstract SharedPreferences getSharedPreferences(String name,int mode);
}
