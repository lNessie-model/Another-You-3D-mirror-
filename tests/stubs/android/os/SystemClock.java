package android.os;

/** Deterministic host-test clock; never included in the Android application. */
public final class SystemClock {
    public static long now = 2_000_000_000L;

    private SystemClock() {}

    public static long elapsedRealtimeNanos() { return now; }
    public static long uptimeMillis() { return now / 1_000_000L; }
}
