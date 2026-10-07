package android.content.res;
import java.io.*;
import java.nio.file.*;
/** APK asset read boundary backed only by the immutable repository fixtures. */
public final class AssetManager {
 public static Path root;
 public InputStream open(String name)throws IOException {Path p=root.resolve(name).normalize();if(!p.startsWith(root))throw new IOException("Asset path escaped");return Files.newInputStream(p);}
}
