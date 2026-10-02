package com.mirror.bench;

/** Bounded, thread-safe face acquisition and loss handling; independent of Android and rendering. */
public final class InteractionController {
    public enum State { WAITING, ACQUIRING, INTERACTIVE, GRACE, ERROR }
    private static final long ACQUIRE_NS = 200_000_000L;
    private static final long MAX_AGE_NS = 500_000_000L;
    private static final long RESUME_GRACE_NS = 500_000_000L;
    private static final long WAIT_AFTER_LOSS_NS = 2_000_000_000L;
    private static final double ACTIVE_SMOOTH_NS = 70_000_000.0;
    private static final double NEUTRAL_SMOOTH_NS = 150_000_000.0;

    public static final class Snapshot {
        private final State state;
        private final float[] renderWeights, blendshapes52, pose;
        private final long sequence, receivedNs, completedNs;
        private final boolean facePresent;
        private final long resultAgeMs;

        private Snapshot(State state, float[] renderWeights, float[] blendshapes52, float[] pose,
                         boolean facePresent, long resultAgeMs, FaceFrame frame) {
            this.state = state;
            this.renderWeights = renderWeights.clone();
            this.blendshapes52 = blendshapes52.clone();
            sequence = frame == null ? -1 : frame.sequence();
            receivedNs = frame == null ? -1 : frame.receivedNs();
            completedNs = frame == null ? -1 : frame.completedNs();
            this.pose = pose.clone();
            this.facePresent = facePresent;
            this.resultAgeMs = resultAgeMs;
        }
        public State state() { return state; }
        public float[] renderWeights() { return renderWeights.clone(); }
        /** All input channels, independently smoothed and faded on loss; left/right remain separate. */
        public float[] blendshapes52() { return blendshapes52.clone(); }
        public long sequence() { return sequence; }
        public long receivedNs() { return receivedNs; }
        public long completedNs() { return completedNs; }
        /** Raw pose while interactive; otherwise identity, which the renderer smoothly approaches. */
        public float[] pose() { return pose.clone(); }
        public int analysisFps() { return state == State.INTERACTIVE || state == State.GRACE ? 17 : 3; }
        public boolean facePresent() { return facePresent; }
        /** Age since application receipt, including inference latency; -1 means no accepted result. */
        public long resultAgeMs() { return resultAgeMs; }
    }

    private State state = State.WAITING;
    private FaceFrame pending, latest;
    private long lastSequence = -1, lastSampleNs = -1, lastPresentReceivedNs = -1;
    private long acquireSinceNs = -1, acquireSequence = -1, graceSinceNs = -1;
    private final float[] smoothedWeights = new float[4];
    private final float[] smoothedBlendshapes = new float[52];
    private static final float[] NEUTRAL_BLENDSHAPES = new float[52];

    /** Latest-result mailbox. Duplicate/older sequences and results during ERROR are ignored. */
    public synchronized boolean accept(FaceFrame frame) {
        if (frame == null) throw new IllegalArgumentException("Frame is required");
        if (state == State.ERROR || frame.sequence() <= lastSequence) return false;
        lastSequence = frame.sequence();
        pending = frame;
        return true;
    }

    /** Call from any thread with nondecreasing times from the frame timestamp clock. */
    public synchronized Snapshot sample(java.util.function.LongSupplier monotonicClock) {
        if (monotonicClock == null) throw new IllegalArgumentException("Clock is required");
        // Read time while accept() is excluded: a concurrent valid completion cannot look future-dated.
        return sample(monotonicClock.getAsLong());
    }

    /** Deterministic timestamp overload for tests and callers that already own the result timeline. */
    public synchronized Snapshot sample(long nowNs) {
        if (nowNs < 0 || nowNs < lastSampleNs)
            throw new IllegalArgumentException("Sample time must be monotonic");
        boolean newResult = false;
        long previousPresentReceivedNs = lastPresentReceivedNs;
        if (pending != null) {
            // Future results are invalid, not delayed playback. Never activate them at a later sample.
            if (pending.completedNs() <= nowNs && (latest == null
                    || (pending.receivedNs() >= latest.receivedNs()
                    && pending.completedNs() >= latest.completedNs()))) {
                latest = pending;
                newResult = true;
            }
            pending = null;
        }
        long ageNs = latest == null ? -1 : nowNs - latest.receivedNs();
        boolean face = state != State.ERROR && latest != null && latest.present() && ageNs <= MAX_AGE_NS;

        if (face) {
            // A stalled sampler must not treat a long gap between detections as continuous presence.
            if (newResult && previousPresentReceivedNs >= 0
                    && latest.receivedNs() - previousPresentReceivedNs > MAX_AGE_NS) {
                if (state == State.INTERACTIVE) enterGrace(previousPresentReceivedNs + MAX_AGE_NS);
                else if (state == State.ACQUIRING) state = State.WAITING;
            }
            if (state == State.WAITING) beginAcquiring();
            else if (state == State.ACQUIRING && latest.sequence() > acquireSequence
                    && latest.receivedNs() - acquireSinceNs >= ACQUIRE_NS) {
                state = State.INTERACTIVE;
            } else if (state == State.GRACE) {
                if (nowNs - graceSinceNs <= RESUME_GRACE_NS) state = State.INTERACTIVE;
                else beginAcquiring();
            }
            lastPresentReceivedNs = latest.receivedNs();
        } else if (state == State.INTERACTIVE) {
            long lostAt = lastPresentReceivedNs + MAX_AGE_NS;
            if (latest != null && !latest.present()) lostAt = Math.min(lostAt, latest.receivedNs());
            enterGrace(lostAt);
        } else if (state == State.ACQUIRING) {
            state = State.WAITING;
            acquireSinceNs = acquireSequence = -1;
        }
        if (state == State.GRACE && !face && nowNs - graceSinceNs >= WAIT_AFTER_LOSS_NS)
            state = State.WAITING;

        boolean drive = state == State.INTERACTIVE && face;
        float[] fullTarget = drive ? latest.blendshapes() : NEUTRAL_BLENDSHAPES;
        float[] target = drive ? mapWeights(fullTarget) : new float[4];
        long elapsedNs = lastSampleNs < 0 ? 0 : nowNs - lastSampleNs;
        float alpha = (float) -Math.expm1(-elapsedNs / (drive ? ACTIVE_SMOOTH_NS : NEUTRAL_SMOOTH_NS));
        for (int i = 0; i < smoothedWeights.length; i++) {
            smoothedWeights[i] += (target[i] - smoothedWeights[i]) * alpha;
            if (state == State.WAITING || Math.abs(smoothedWeights[i]) < .00001f) smoothedWeights[i] = 0;
        }
        for (int i = 0; i < smoothedBlendshapes.length; i++) {
            smoothedBlendshapes[i] += (clamp(fullTarget[i]) - smoothedBlendshapes[i]) * alpha;
            if (state == State.WAITING || Math.abs(smoothedBlendshapes[i]) < .00001f) smoothedBlendshapes[i] = 0;
        }
        lastSampleNs = nowNs;
        return new Snapshot(state, smoothedWeights, smoothedBlendshapes, drive ? latest.pose() : FaceFrame.identity(),
                face, ageNs < 0 ? -1 : ageNs / 1_000_000L, latest);
    }

    private void beginAcquiring() {
        state = State.ACQUIRING;
        acquireSinceNs = latest.receivedNs();
        acquireSequence = latest.sequence();
        graceSinceNs = -1;
    }
    private void enterGrace(long lostAtNs) { state = State.GRACE; graceSinceNs = lostAtNs; }
    private static float[] mapWeights(float[] values) {
        return new float[] {clamp(values[25]), (clamp(values[9]) + clamp(values[10])) * .5f,
                (clamp(values[44]) + clamp(values[45])) * .5f, clamp(values[3])};
    }
    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }

    /** Start a new session after its old producer has stopped; sequence and clock may restart. */
    public synchronized void reset() {
        state = State.WAITING;
        pending = latest = null;
        lastSequence = lastSampleNs = lastPresentReceivedNs = -1;
        acquireSinceNs = acquireSequence = graceSinceNs = -1;
        java.util.Arrays.fill(smoothedWeights, 0);
        java.util.Arrays.fill(smoothedBlendshapes, 0);
    }
    /** Latches ERROR until explicitly cleared. Existing motion fades toward neutral on sample(). */
    public synchronized void setError() {
        state = State.ERROR;
        pending = latest = null;
        lastPresentReceivedNs = acquireSinceNs = acquireSequence = graceSinceNs = -1;
    }
    /** Clear a recovered fault without reopening acceptance of older in-flight sequences. */
    public synchronized void clearError() {
        if (state != State.ERROR) return;
        state = State.WAITING;
        java.util.Arrays.fill(smoothedWeights, 0);
        java.util.Arrays.fill(smoothedBlendshapes, 0);
    }
}
