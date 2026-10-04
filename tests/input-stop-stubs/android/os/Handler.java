package android.os;

/** Enqueue only. Never runs Android lifecycle callbacks or accesses a device. */
public class Handler {
    public static final java.util.List<Runnable> posted=new java.util.concurrent.CopyOnWriteArrayList<>();
    public Handler(Looper looper){}
    public boolean post(Runnable task){posted.add(task);return true;}
}
