package com.mirror.bench;

/** Pure clock-driven watchdog cases. No threads, sleeps, Android or device are needed. */
public final class RuntimeProgressWatchdogTest {
    private static int checks;
    public static void main(String[] args) {
        initializationHasItsOwnDeadline();
        activeAndIdleProgressStayHealthy();
        blockedWorkLatchesItsStage();
        cancellationAndWaitingDoNotFault();
        negativeTimesRejected();
        System.out.println("PASS: " + checks + " runtime watchdog assertions");
    }
    private static void initializationHasItsOwnDeadline() {
        RuntimeProgressWatchdog w = new RuntimeProgressWatchdog();
        w.progress(RuntimeProgressWatchdog.Stage.OPENING_CAMERA, 0);
        check(w.poll(ms(24_999)) == null, "camera gets its 20 second API deadline plus margin");
        RuntimeProgressWatchdog.Fault fault = w.poll(ms(25_000));
        check(fault.stage() == RuntimeProgressWatchdog.Stage.OPENING_CAMERA, "camera timeout stage");
        check(fault.timeoutMs() == 25_000 && fault.stalledMs() == 25_000, "camera timeout details");
        w = new RuntimeProgressWatchdog();
        w.progress(RuntimeProgressWatchdog.Stage.INITIALIZING, ms(100));
        check(w.poll(ms(59_000)) == null, "cold model initialization permitted");
        check(w.poll(ms(60_100)).stage() == RuntimeProgressWatchdog.Stage.INITIALIZING, "stalled initialization detected");
    }
    private static void activeAndIdleProgressStayHealthy() {
        RuntimeProgressWatchdog w = new RuntimeProgressWatchdog();
        for (int frame = 0; frame < 100; frame++) {
            long base = ms(frame * 333L); // Idle 3 FPS is progress, including an absent face.
            w.progress(RuntimeProgressWatchdog.Stage.CAPTURING, base);
            check(w.poll(base + ms(10)) == null, "idle capture progressing");
            w.progress(RuntimeProgressWatchdog.Stage.CONVERTING, base + ms(11));
            w.progress(RuntimeProgressWatchdog.Stage.INFERENCING, base + ms(15));
            check(w.poll(base + ms(80)) == null, "inference progressing");
            w.progress(RuntimeProgressWatchdog.Stage.PACING, base + ms(85));
            check(w.poll(base + ms(330)) == null, "intentional idle pacing");
        }
        w.progress(RuntimeProgressWatchdog.Stage.INFERENCING, ms(40_000));
        check(w.poll(ms(40_060)) == null, "active 17 FPS frame progressing");
        w.progress(RuntimeProgressWatchdog.Stage.REPORTING, ms(40_061));
        check(w.poll(ms(40_900)) == null, "bounded diagnostic write margin");
    }
    private static void blockedWorkLatchesItsStage() {
        RuntimeProgressWatchdog w = new RuntimeProgressWatchdog();
        w.progress(RuntimeProgressWatchdog.Stage.INFERENCING, ms(200));
        check(w.poll(ms(5_199)) == null, "inference before deadline");
        RuntimeProgressWatchdog.Fault fault = w.poll(ms(5_200));
        check(fault.stage() == RuntimeProgressWatchdog.Stage.INFERENCING, "native stall identifies inference");
        check(fault.timeoutMs() == 5000 && fault.observedNs() == ms(5200), "timeout has units and clock");
        w.progress(RuntimeProgressWatchdog.Stage.PACING, ms(5300));
        check(w.poll(ms(5400)) == fault, "late completion cannot erase a latched failure");
        w.cancel();
        check(w.poll(ms(6000)) == fault, "cancellation preserves fault evidence");
        RuntimeProgressWatchdog cleanup = new RuntimeProgressWatchdog();
        cleanup.progress(RuntimeProgressWatchdog.Stage.RELEASING, 0);
        check(cleanup.poll(ms(5000)).stage() == RuntimeProgressWatchdog.Stage.RELEASING, "blocked recovery cleanup detected");
    }
    private static void cancellationAndWaitingDoNotFault() {
        RuntimeProgressWatchdog w = new RuntimeProgressWatchdog();
        check(w.poll(ms(120_000)) == null, "waiting for previous owner has no new native work");
        w.progress(RuntimeProgressWatchdog.Stage.RETRY_WAIT, ms(130_000));
        check(w.poll(ms(160_000)) == null, "30 second retry backoff is intentional");
        w.progress(RuntimeProgressWatchdog.Stage.INFERENCING, ms(170_000));
        w.cancel();
        w.progress(RuntimeProgressWatchdog.Stage.RELEASING, ms(171_000));
        check(w.poll(ms(200_000)) == null, "pause or maintenance cancellation suppresses watchdog");
        RuntimeProgressWatchdog fresh = new RuntimeProgressWatchdog();
        fresh.progress(RuntimeProgressWatchdog.Stage.CAPTURING, ms(100));
        check(fresh.poll(ms(99)) == null, "snapshot clock just before producer progress is harmless");
    }
    private static void negativeTimesRejected() {
        RuntimeProgressWatchdog w = new RuntimeProgressWatchdog();
        expectInvalid(() -> w.progress(RuntimeProgressWatchdog.Stage.CAPTURING, -1));
        expectInvalid(() -> w.progress(null, 0));
        expectInvalid(() -> w.poll(-1));
    }
    private static long ms(long milliseconds) { return milliseconds * 1_000_000L; }
    private static void check(boolean okay, String label) { checks++; if (!okay) throw new AssertionError(label); }
    private static void expectInvalid(Runnable action) {
        try { action.run(); throw new AssertionError("Expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
}
