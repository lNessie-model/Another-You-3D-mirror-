package com.mirror.bench;

/** Immutable software interlace parameters and a float-ordered CPU reference, not optical certification. */
public final class PanelCalibration {
    public enum PitchUnits { PIXELS, SUBPIXELS }
    public enum SubpixelOrder { RGB, BGR }
    public enum YOrigin { BOTTOM, TOP }
    private static final int MAX_DIMENSION = 16_384;
    private final float pitch, tan, phaseCycles, pitchSubpixels, tiltSubpixelsPerPixel;
    private final PitchUnits pitchUnits;
    private final SubpixelOrder subpixelOrder;
    private final boolean reverseViews;
    private final YOrigin yOrigin;

    public PanelCalibration(float pitch, float tan, PitchUnits pitchUnits, float phaseCycles,
                            SubpixelOrder subpixelOrder, boolean reverseViews, YOrigin yOrigin) {
        if (!Float.isFinite(pitch) || pitch < 1 || pitch > 4096)
            throw new IllegalArgumentException("pitch must be finite and within [1,4096] in the declared units");
        if (!Float.isFinite(tan) || tan < -4 || tan > 4)
            throw new IllegalArgumentException("tan must be finite and within [-4,4]");
        if (!Float.isFinite(phaseCycles) || phaseCycles < -1024 || phaseCycles > 1024)
            throw new IllegalArgumentException("phaseCycles must be finite and within [-1024,1024]");
        if (pitchUnits == null || subpixelOrder == null || yOrigin == null)
            throw new IllegalArgumentException("Pitch units, subpixel order and Y origin are required");
        this.pitch = pitch;
        this.tan = tan;
        this.pitchUnits = pitchUnits;
        float wrappedPhase = phaseCycles - (float) Math.floor(phaseCycles);
        // A negative subnormal can round its positive wrap to 1f, which represents the next period.
        this.phaseCycles = wrappedPhase >= 1f ? 0f : wrappedPhase;
        this.subpixelOrder = subpixelOrder;
        this.reverseViews = reverseViews;
        this.yOrigin = yOrigin;
        pitchSubpixels = pitchUnits == PitchUnits.PIXELS ? pitch * 3f : pitch;
        tiltSubpixelsPerPixel = tan * 3f;
    }

    /** User-supplied pitch/tan, temporarily assuming subpixel units and the old shader's directions. */
    public static PanelCalibration defaults() {
        return new PanelCalibration(10f, .2777777f, PitchUnits.SUBPIXELS, 0f,
                SubpixelOrder.RGB, false, YOrigin.BOTTOM);
    }

    public float pitch() { return pitch; }
    /** Horizontal whole-pixel displacement per vertical pixel along the selected Y origin. */
    public float tan() { return tan; }
    public PitchUnits pitchUnits() { return pitchUnits; }
    /** Canonical periodic offset in [0,1); it changes view selection, never color components. */
    public float phaseCycles() { return phaseCycles; }
    public SubpixelOrder subpixelOrder() { return subpixelOrder; }
    public boolean reverseViews() { return reverseViews; }
    public YOrigin yOrigin() { return yOrigin; }
    public float pitchSubpixels() { return pitchSubpixels; }
    public float tiltSubpixelsPerPixel() { return tiltSubpixelsPerPixel; }
    /** Optical verification needs a separate measured screen/installation record. */
    public boolean opticalAlignmentVerified() { return false; }

    /** channel is the OUTPUT component R=0/G=1/B=2; BGR changes position, not the sampled component. */
    public int physicalOffset(int channel) {
        if (channel < 0 || channel > 2) throw new IllegalArgumentException("channel must be R=0, G=1 or B=2");
        return subpixelOrder == SubpixelOrder.RGB ? channel : 2 - channel;
    }

    /**
     * Integer framebuffer coordinates, always bottom-up at the API boundary. TOP uses height-(y+.5).
     * The renderer still samples the requested RGB component from the returned layer.
     * Java 17 float operations intentionally preserve the uncontracted default shader order.
     */
    public int viewIndex(int x, int y, int height, int channel, int count) {
        if (height < 1 || height > MAX_DIMENSION || x < 0 || x >= MAX_DIMENSION || y < 0 || y >= height)
            throw new IllegalArgumentException("Pixel coordinates/height exceed the supported framebuffer bounds");
        if (count < 1 || count > 32) throw new IllegalArgumentException("view count must be within [1,32]");
        float offset = physicalOffset(channel);
        float fx = (float) x + .5f;
        float fy = (float) y + .5f;
        if (yOrigin == YOrigin.TOP) fy = (float) height - fy;
        float xTerm = fx * 3f;
        float yTerm = fy * tiltSubpixelsPerPixel;
        float sum = xTerm + yTerm;
        sum = sum + offset;
        float q = sum / pitchSubpixels;
        if (phaseCycles != 0f) q = q + phaseCycles;
        float fraction = q - (float) Math.floor(q);
        float selected = fraction * (float) count;
        // float fract of a negative value extremely close to zero may round to 1f.
        // Keep the half-open view range, including before applying reversal.
        int index = Math.min(count - 1, (int) Math.floor(selected));
        return reverseViews ? count - 1 - index : index;
    }
}
