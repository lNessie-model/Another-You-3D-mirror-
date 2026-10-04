package com.mirror.bench;

/** Immutable inference result. Both timestamps use the same monotonic nanosecond clock. */
public final class FaceFrame {
    private final long sequence, receivedNs, completedNs;
    private final float[] blendshapes, pose;
    private final boolean present;

    private FaceFrame(long sequence, long receivedNs, long completedNs,
                      float[] blendshapes, float[] pose, boolean present) {
        if (sequence < 0 || receivedNs < 0 || completedNs < receivedNs)
            throw new IllegalArgumentException("Invalid frame sequence or monotonic timestamps");
        this.sequence = sequence;
        this.receivedNs = receivedNs;
        this.completedNs = completedNs;
        this.blendshapes = copyFinite(blendshapes, 52, "blendshapes");
        this.pose = copyFinite(pose, 16, "pose");
        this.present = present;
    }

    public static FaceFrame present(long sequence, long receivedNs, long completedNs,
                                    float[] blendshapes, float[] pose) {
        return new FaceFrame(sequence, receivedNs, completedNs, blendshapes, pose, true);
    }

    public static FaceFrame absent(long sequence, long receivedNs, long completedNs) {
        return new FaceFrame(sequence, receivedNs, completedNs, new float[52], identity(), false);
    }

    public long sequence() { return sequence; }
    public long receivedNs() { return receivedNs; }
    public long completedNs() { return completedNs; }
    public boolean present() { return present; }
    public float[] blendshapes() { return blendshapes.clone(); }
    /** Original inference matrix layout; the renderer owns coordinate conversion and smoothing. */
    public float[] pose() { return pose.clone(); }

    static float[] identity() {
        return new float[] {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    }

    private static float[] copyFinite(float[] values, int length, String name) {
        if (values == null || values.length != length)
            throw new IllegalArgumentException(name + " must contain " + length + " floats");
        float[] copy = values.clone();
        for (float value : copy)
            if (!Float.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
        return copy;
    }
}
