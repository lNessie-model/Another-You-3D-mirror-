package android.content;

import java.io.File;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;

/** Constructor-only host fixture; no Android filesystem or native services. */
public class Context {
    private final File directory;
    private final AssetManager assets;
    public Context(File directory,AssetManager assets){this.directory=directory;this.assets=assets;}
    public File getFilesDir(){return directory;}
    public AssetManager getAssets(){return assets;}
    public ApplicationInfo getApplicationInfo(){return new ApplicationInfo();}
}
