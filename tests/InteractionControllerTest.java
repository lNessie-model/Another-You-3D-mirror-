package com.mirror.bench;

/** Deterministic interaction checks: no Android, camera, wall clock or sleeps. */
public final class InteractionControllerTest {
    private static int checks;
    public static void main(String[] args) {
        frameValidationAndCopies();
        acquisitionNeedsDistinctStableFrames();
        mappedExpressionsAndSnapshotCopies();
        lossReturnsToNeutralAndWaiting();
        briefLossResumesButLongLossReacquires();
        staleAndFutureResultsCannotAnimate();
        acquisitionUsesInputTimeAndRejectsRegressedTime();
        liveClockIsReadWithTheResultMailboxLocked();
        orderResetAndErrors();
        rawPoseOnlyWhileInteractive();
        System.out.println("PASS: " + checks + " interaction assertions");
    }
    private static void frameValidationAndCopies() {
        float[] weights = weights(1), pose = identity();
        FaceFrame frame = FaceFrame.present(7, ns(10), ns(20), weights, pose);
        weights[25] = 0; pose[0] = 0;
        near(frame.blendshapes()[25], 1, "input weights copied");
        near(frame.pose()[0], 1, "input pose copied");
        frame.blendshapes()[25] = 0; frame.pose()[0] = 0;
        near(frame.blendshapes()[25], 1, "weight accessor copied");
        near(frame.pose()[0], 1, "pose accessor copied");
        check(frame.sequence() == 7 && frame.receivedNs() == ns(10)
                && frame.completedNs() == ns(20) && frame.present(), "frame metadata");
        expectInvalid(() -> FaceFrame.present(0, 0, 0, new float[51], identity()));
        expectInvalid(() -> FaceFrame.present(0, 0, 0, weights(0), new float[15]));
        expectInvalid(() -> FaceFrame.present(0, 0, 0, null, identity()));
        float[] invalid = weights(0); invalid[8] = Float.NaN;
        expectInvalid(() -> FaceFrame.present(0, 0, 0, invalid, identity()));
        float[] invalidPose = identity(); invalidPose[3] = Float.POSITIVE_INFINITY;
        expectInvalid(() -> FaceFrame.present(0, 0, 0, weights(0), invalidPose));
        expectInvalid(() -> FaceFrame.absent(-1, 0, 0));
        expectInvalid(() -> FaceFrame.absent(0, -1, 0));
        expectInvalid(() -> FaceFrame.absent(0, 2, 1));
        FaceFrame absent = FaceFrame.absent(8, ns(30), ns(40));
        check(!absent.present(), "absent marked absent");
        near(absent.blendshapes()[25], 0, "absent weights neutral");
        near(absent.pose()[15], 1, "absent identity pose");
    }
    private static void acquisitionNeedsDistinctStableFrames() {
        InteractionController c = new InteractionController();
        snapshot(c.sample(0), InteractionController.State.WAITING, 3, false);
        check(c.sample(0).resultAgeMs() == -1, "no result age sentinel");
        c.accept(face(0, 100, 1));
        snapshot(c.sample(ns(100)), InteractionController.State.ACQUIRING, 3, true);
        snapshot(c.sample(ns(350)), InteractionController.State.ACQUIRING, 3, true);
        c.accept(face(1, 433, 1));
        snapshot(c.sample(ns(433)), InteractionController.State.INTERACTIVE, 17, true);
        c.reset();
        c.accept(face(0, 100, 1)); c.sample(ns(100));
        c.accept(face(1, 150, 1));
        snapshot(c.sample(ns(150)), InteractionController.State.ACQUIRING, 3, true);
        c.accept(face(2, 301, 1));
        snapshot(c.sample(ns(301)), InteractionController.State.INTERACTIVE, 17, true);
        c.reset();
        c.accept(face(0, 100, 1)); c.sample(ns(100));
        c.accept(face(1, 1000, 1));
        snapshot(c.sample(ns(1000)), InteractionController.State.ACQUIRING, 3, true);
    }
    private static void mappedExpressionsAndSnapshotCopies() {
        InteractionController c = active();
        float[] weights = weights(1);
        weights[9] = .2f; weights[10] = .6f;
        weights[44] = .8f; weights[45] = .4f; weights[3] = .7f;
        c.accept(FaceFrame.present(2, ns(400), ns(410), weights, identity()));
        c.sample(ns(410));
        InteractionController.Snapshot s = c.sample(ns(700));
        near(s.renderWeights()[0], 1, .02f, "jaw mapping");
        near(s.renderWeights()[1], .4f, .02f, "eyes mapping");
        near(s.renderWeights()[2], .6f, .02f, "smile mapping");
        near(s.renderWeights()[3], .7f, .02f, "brow mapping");
        check(s.resultAgeMs() == 300, "age includes inference time");
        s.renderWeights()[0] = 99; s.pose()[0] = 99;
        check(c.sample(ns(700)).renderWeights()[0] <= 1, "snapshot does not alias controller weights");
        near(c.sample(ns(700)).pose()[0], 1, "snapshot does not alias controller pose");
        c.accept(FaceFrame.present(3, ns(710), ns(710), weights(4), identity()));
        check(c.sample(ns(720)).renderWeights()[0] <= 1, "finite out-of-range score clamped");
    }
    private static void lossReturnsToNeutralAndWaiting() {
        InteractionController c = active();
        c.sample(ns(400));
        float before = c.sample(ns(450)).renderWeights()[0];
        c.accept(FaceFrame.absent(2, ns(500), ns(500)));
        InteractionController.Snapshot lost = c.sample(ns(500));
        snapshot(lost, InteractionController.State.GRACE, 17, false);
        check(lost.renderWeights()[0] > 0 && lost.renderWeights()[0] < before,
                "loss fades instead of holding or jumping");
        check(c.sample(ns(1000)).renderWeights()[0] < .05f, "neutral within grace fade");
        snapshot(c.sample(ns(2499)), InteractionController.State.GRACE, 17, false);
        snapshot(c.sample(ns(2500)), InteractionController.State.WAITING, 3, false);
        near(c.sample(ns(2500)).renderWeights()[0], 0, "waiting exactly neutral");
    }
    private static void briefLossResumesButLongLossReacquires() {
        InteractionController c = active();
        c.accept(FaceFrame.absent(2, ns(400), ns(400))); c.sample(ns(400));
        c.accept(face(3, 600, 1));
        snapshot(c.sample(ns(600)), InteractionController.State.INTERACTIVE, 17, true);
        c.accept(FaceFrame.absent(4, ns(700), ns(700))); c.sample(ns(700));
        c.accept(face(5, 1300, 1));
        snapshot(c.sample(ns(1300)), InteractionController.State.ACQUIRING, 3, true);
        c.accept(face(6, 1633, 1));
        snapshot(c.sample(ns(1633)), InteractionController.State.INTERACTIVE, 17, true);
    }
    private static void staleAndFutureResultsCannotAnimate() {
        InteractionController c = active();
        snapshot(c.sample(ns(801)), InteractionController.State.GRACE, 17, false);
        snapshot(c.sample(ns(2800)), InteractionController.State.WAITING, 3, false);
        c.reset();
        c.accept(FaceFrame.present(0, 0, ns(800), weights(1), identity()));
        snapshot(c.sample(ns(800)), InteractionController.State.WAITING, 3, false);
        c.reset();
        c.accept(FaceFrame.present(0, ns(100), ns(1100), weights(1), identity()));
        snapshot(c.sample(ns(100)), InteractionController.State.WAITING, 3, false);
        snapshot(c.sample(ns(1100)), InteractionController.State.WAITING, 3, false);
        check(c.sample(ns(1100)).resultAgeMs() == -1, "future result discarded permanently");
    }
    private static void orderResetAndErrors() {
        InteractionController c = active();
        check(!c.accept(face(1, 350, 0)), "duplicate sequence rejected");
        check(!c.accept(face(0, 360, 0)), "older sequence rejected");
        c.setError();
        snapshot(c.sample(ns(400)), InteractionController.State.ERROR, 3, false);
        check(!c.accept(face(2, 410, 1)), "fault ignores in-flight results");
        snapshot(c.sample(ns(420)), InteractionController.State.ERROR, 3, false);
        c.clearError();
        snapshot(c.sample(ns(430)), InteractionController.State.WAITING, 3, false);
        check(c.accept(face(2, 440, 1)), "resume accepts next sequence");
        snapshot(c.sample(ns(440)), InteractionController.State.ACQUIRING, 3, true);
        c.reset();
        check(c.accept(face(0, 10, 1)), "reset allows new session sequence");
        c.sample(ns(10));
        expectInvalid(() -> c.sample(ns(9)));
    }
    private static void liveClockIsReadWithTheResultMailboxLocked() {
        InteractionController c = new InteractionController();
        // UI sampled 100 ms, then a genuine result completed at 101 ms before it inspected the mailbox.
        c.accept(FaceFrame.present(0, ns(90), ns(101), weights(1), identity()));
        snapshot(c.sample(() -> {
            check(Thread.holdsLock(c), "live clock read is atomic with mailbox inspection");
            return ns(102);
        }), InteractionController.State.ACQUIRING, 3, true);
        check(c.sample(() -> ns(103)).resultAgeMs() == 13, "legitimate concurrently completed result retained");
        expectInvalid(() -> c.sample((java.util.function.LongSupplier) null));
    }
    private static void acquisitionUsesInputTimeAndRejectsRegressedTime() {
        InteractionController c = new InteractionController();
        c.accept(FaceFrame.present(0, ns(100), ns(110), weights(1), identity()));
        c.sample(ns(110));
        // Slower inference must not manufacture 200 ms of stable observed presence.
        c.accept(FaceFrame.present(1, ns(200), ns(400), weights(1), identity()));
        snapshot(c.sample(ns(400)), InteractionController.State.ACQUIRING, 3, true);
        c.accept(FaceFrame.present(2, ns(433), ns(443), weights(1), identity()));
        snapshot(c.sample(ns(443)), InteractionController.State.INTERACTIVE, 17, true);
        c.accept(FaceFrame.absent(3, ns(300), ns(450)));
        snapshot(c.sample(ns(450)), InteractionController.State.INTERACTIVE, 17, true);
        check(c.sample(ns(450)).resultAgeMs() == 17, "regressed input does not replace newer evidence");
    }
    private static void rawPoseOnlyWhileInteractive() {
        InteractionController c = active();
        float[] pose = identity();
        pose[0] = 0; pose[2] = -1; pose[8] = 1; pose[10] = 0; pose[12] = 3;
        c.accept(FaceFrame.present(2, ns(400), ns(400), weights(1), pose));
        float[] live = c.sample(ns(400)).pose();
        for (int i = 0; i < 16; i++) near(live[i], pose[i], "raw pose reaches renderer");
        c.accept(FaceFrame.absent(3, ns(500), ns(500))); c.sample(ns(500));
        float[] neutral = c.sample(ns(2500)).pose();
        for (int i = 0; i < 16; i++) near(neutral[i], identity()[i], "waiting pose identity");
    }
    private static InteractionController active() {
        InteractionController c = new InteractionController();
        c.accept(face(0, 0, 1)); c.sample(0);
        c.accept(face(1, 300, 1)); c.sample(ns(300));
        return c;
    }
    private static FaceFrame face(long sequence, long ms, float amount) {
        return FaceFrame.present(sequence, ns(ms), ns(ms), weights(amount), identity());
    }
    private static float[] weights(float amount) { float[] v = new float[52]; v[25] = amount; return v; }
    private static float[] identity() { return new float[] {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1}; }
    private static long ns(long ms) { return ms * 1_000_000L; }
    private static void snapshot(InteractionController.Snapshot s, InteractionController.State state, int fps, boolean face) {
        check(s.state() == state, "state expected " + state + " got " + s.state());
        check(s.analysisFps() == fps, "analysis fps for " + state);
        check(s.facePresent() == face, "fresh face for " + state);
    }
    private static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    private static void near(float actual, float expected, String label) { near(actual, expected, .00001f, label); }
    private static void near(float actual, float expected, float tolerance, String label) {
        check(Float.isFinite(actual) && Math.abs(actual - expected) <= tolerance, label + ": " + actual);
    }
    private static void expectInvalid(Runnable action) {
        try { action.run(); throw new AssertionError("Expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
}
