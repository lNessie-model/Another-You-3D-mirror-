package android.os;

import java.io.File;

/** Host-only Android filesystem boundary. Never included in the APK. */
public final class StatFs {
    public static Long availableOverride;
    public static RuntimeException failure;
    public static String lastPath;
    private final File path;
    public StatFs(String path) {
        lastPath=path;
        if(failure!=null)throw failure;
        this.path=new File(path);
    }
    public long getAvailableBytes() {
        return availableOverride==null?path.getUsableSpace():availableOverride;
    }
    public static void reset() {availableOverride=null;failure=null;lastPath=null;}
}
