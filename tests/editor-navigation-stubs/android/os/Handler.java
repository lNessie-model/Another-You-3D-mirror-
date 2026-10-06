package android.os;

/** Removes the UI clock boundary for onPause only; no callbacks are run or scheduled. */
public class Handler {
    public int removals;
    public void removeCallbacks(Runnable ignored){removals++;}
}
