package android.app;

import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import java.io.File;

/** Host I/O fixture only; instances are allocated without running Android constructors. */
public class Activity extends ContextWrapper {
    public static File files;
    public static Intent intent;
    public static ApplicationInfo info;
    public Activity(){super(null);}
    public File getFilesDir(){return files;}
    public AssetManager getAssets(){return null;}
    public Intent getIntent(){return intent;}
    public ApplicationInfo getApplicationInfo(){return info;}
    public void runOnUiThread(Runnable task){task.run();}
}
