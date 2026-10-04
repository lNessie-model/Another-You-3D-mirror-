package com.mirror.bench;

/** GL-thread-owned progress clock. All timestamps use one monotonic nanoTime domain.
 * Pause/resume is explicit; a delayed render callback never counts as successful CPU work.
 * Signed nanoTime wrap is supported when successive intervals are less than 2^63 nanoseconds.
 */
public final class AvatarPoseProgressWatchdog {
    public static final long FRESH_RESULT_NS=500_000_000L;
    public static final long TIMEOUT_NS=2_000_000_000L;
    private boolean initialized,hasResult;
    private long observedNs,progressNs,submittedNs;

    /** Grants one initialization/recovery interval; does not invent or erase an applied result. */
    public void resume(long nowNs) {
        observe(nowNs);
        progressNs=nowNs;
    }

    /** Call after an accepted lease has been uploaded. Stale results do not advance progress. */
    public boolean applied(long sourceSubmittedNs,long nowNs) {
        long age=interval(nowNs,sourceSubmittedNs);
        observe(nowNs);
        if(age>FRESH_RESULT_NS)return false;
        progressNs=nowNs;submittedNs=sourceSubmittedNs;hasResult=true;
        return true;
    }

    /** First call initializes a grace interval. Later callback gaps never reset that interval. */
    public boolean check(long nowNs) {
        observe(nowNs);
        return interval(nowNs,progressNs)>=TIMEOUT_NS;
    }

    /** Age of the actual last applied input, including time spent paused; -1 until first result. */
    public long currentResultAgeMs(long nowNs) {
        validateObservation(nowNs);
        return hasResult?interval(nowNs,submittedNs)/1_000_000L:-1;
    }

    /** Time since explicit resume/initialization or a confirmed fresh application. */
    public long stalledMs(long nowNs) {
        validateObservation(nowNs);
        return initialized?interval(nowNs,progressNs)/1_000_000L:-1;
    }

    private void observe(long nowNs) {
        validateObservation(nowNs);
        if(!initialized){initialized=true;progressNs=nowNs;}
        observedNs=nowNs;
    }
    private void validateObservation(long nowNs){if(initialized)interval(nowNs,observedNs);}
    private static long interval(long nowNs,long earlierNs) {
        long elapsed=nowNs-earlierNs;
        if(elapsed<0)throw new IllegalArgumentException("Avatar progress timestamps must be monotonic and from the same clock");
        return elapsed;
    }
}
