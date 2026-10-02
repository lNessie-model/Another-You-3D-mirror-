package com.mirror.bench;

/** Stage deadlines detect stopped processing independently from valid "no face" results. */
final class RuntimeProgressWatchdog {
    enum Stage {
        WAITING_FOR_OWNER(0), OPENING_CAMERA(25_000), INITIALIZING(60_000),
        CAPTURING(5_000), CONVERTING(5_000), INFERENCING(5_000), REPORTING(10_000),
        PACING(5_000), RELEASING(5_000), RETRY_WAIT(0), STOPPED(0);
        final long timeoutMs;
        Stage(long timeoutMs) { this.timeoutMs = timeoutMs; }
    }
    record Fault(Stage stage, long timeoutMs, long stalledMs, long observedNs) {}
    private Stage stage = Stage.WAITING_FOR_OWNER;
    private long stageStartedNs;
    private boolean cancelled;
    private Fault fault;

    synchronized void progress(Stage next, long nowNs) {
        if (next == null || nowNs < 0) throw new IllegalArgumentException("Valid stage and monotonic time required");
        if (cancelled || fault != null) return;
        stage = next;
        stageStartedNs = nowNs;
    }
    synchronized Fault poll(long nowNs) {
        if (nowNs < 0) throw new IllegalArgumentException("Monotonic time required");
        if (fault != null) return fault;
        if (cancelled || stage.timeoutMs == 0 || nowNs < stageStartedNs) return null;
        long elapsedNs = nowNs - stageStartedNs;
        if (elapsedNs >= stage.timeoutMs * 1_000_000L)
            fault = new Fault(stage, stage.timeoutMs, elapsedNs / 1_000_000L, nowNs);
        return fault;
    }
    synchronized Stage stage() { return stage; }
    /** Pause/maintenance cancellation is intentional; an existing failure remains diagnostic evidence. */
    synchronized void cancel() { cancelled = true; }
}
