package com.mirror.bench;

import java.util.Random;

/** Hand-computed optical-index math followed by a pinned original-formula regression oracle. */
public final class PanelCalibrationTest {
    private static int checks;
    public static void main(String[] args) {
        defaultsStayUnverified();
        handComputedEndpointsAndPeriod();
        physicalChannelOrderAndViewReversal();
        slopeAndOriginDirections();
        phaseWrapAndUnits();
        roundingAtNegativeCycleBoundaryKeepsValidIndices();
        rejectInvalidConfigurationAndCoordinates();
        originalDefaultFormulaRandomComparison();
        System.out.println("PASS: " + checks + " panel calibration assertions");
    }
    private static void defaultsStayUnverified() {
        PanelCalibration p = PanelCalibration.defaults();
        equal(p.pitch(), 10f, "default pitch");
        equal(p.tan(), .2777777f, "default tan");
        check(p.pitchUnits() == PanelCalibration.PitchUnits.SUBPIXELS, "default pitch unit is explicit assumption");
        check(p.subpixelOrder() == PanelCalibration.SubpixelOrder.RGB, "default RGB");
        check(p.yOrigin() == PanelCalibration.YOrigin.BOTTOM && !p.reverseViews(), "default direction");
        check(!p.opticalAlignmentVerified(), "math does not certify optical alignment");
        // (1.5 + 0.5 * .8333331 + channel) / 10 * 20 is approximately 3.8333 + 2*channel.
        check(p.viewIndex(0, 0, 1920, 0, 20) == 3, "default first pixel R");
        check(p.viewIndex(0, 0, 1920, 1, 20) == 5, "default first pixel G");
        check(p.viewIndex(0, 0, 1920, 2, 20) == 7, "default first pixel B");
    }
    private static void handComputedEndpointsAndPeriod() {
        PanelCalibration p = calibration(6, 0, 0, false);
        // Pixel-center R coordinates are 1.5, 4.5, 7.5 subpixels: phases 1/4, 3/4, 1+1/4.
        check(p.viewIndex(0, 0, 1, 0, 4) == 1, "quarter-period endpoint");
        check(p.viewIndex(1, 0, 1, 0, 4) == 3, "three-quarter-period endpoint");
        check(p.viewIndex(2, 0, 1, 0, 4) == 1, "two pixels form a complete pitch");
        for (int x = 0; x < 500; x++)
            for (int c = 0; c < 3; c++)
                check(p.viewIndex(x, 0, 1, c, 4) == p.viewIndex(x + 2, 0, 1, c, 4), "horizontal period");
        check(calibration(6, 0, .75f, false).viewIndex(0, 0, 1, 0, 4) == 0, "phase wraps exactly at cycle endpoint");
        check(calibration(6, 0, Math.nextDown(.75f), false).viewIndex(0, 0, 1, 0, 4) == 3,
                "immediately before cycle endpoint selects final view");
        check(p.viewIndex(0, 0, 1, 0, 1) == 0, "one view always index zero");
    }
    private static void physicalChannelOrderAndViewReversal() {
        PanelCalibration rgb = calibration(3, 0, 0, false);
        PanelCalibration bgr = new PanelCalibration(3, 0, PanelCalibration.PitchUnits.SUBPIXELS, 0,
                PanelCalibration.SubpixelOrder.BGR, false, PanelCalibration.YOrigin.BOTTOM);
        // Physical offsets 0/1/2 yield [1,2,0]. BGR changes offsets to 2/1/0, not output components.
        int[] expectedRgb = {1, 2, 0}, expectedBgr = {0, 2, 1};
        for (int c = 0; c < 3; c++) {
            check(rgb.viewIndex(0, 0, 1, c, 3) == expectedRgb[c], "RGB physical offset " + c);
            check(bgr.viewIndex(0, 0, 1, c, 3) == expectedBgr[c], "BGR physical offset " + c);
            check(bgr.physicalOffset(c) == 2 - c, "component remains caller's channel; only physical offset changes");
        }
        PanelCalibration reversed = calibration(6, 0, 0, true);
        check(reversed.viewIndex(0, 0, 1, 0, 4) == 2, "reverse maps index 1 to 4-1-1");
        check(reversed.viewIndex(1, 0, 1, 0, 4) == 0, "reverse maps index 3 to first view");
        check(calibration(6, 0, 0, true).viewIndex(0, 0, 1, 2, 1) == 0, "single-view reversal");
    }
    private static void slopeAndOriginDirections() {
        PanelCalibration rising = calibration(12, 1, 0, false);
        check(rising.viewIndex(0, 0, 4, 0, 12) == 3, "positive slope initial index");
        check(rising.viewIndex(0, 1, 4, 0, 12) == 6, "positive slope advances three subpixels per row");
        check(rising.viewIndex(1, 0, 4, 0, 12) == 6, "tan one makes one x pixel equal one y pixel");
        PanelCalibration falling = calibration(12, -1, 0, false);
        check(falling.viewIndex(0, 0, 4, 0, 12) == 0, "negative slope cancels half-pixel centers");
        check(falling.viewIndex(0, 1, 4, 0, 12) == 9, "negative phase uses floor fract, not remainder");
        PanelCalibration bottom = calibration(4, 1f / 3f, 0, false);
        PanelCalibration top = new PanelCalibration(4, 1f / 3f, PanelCalibration.PitchUnits.SUBPIXELS, 0,
                PanelCalibration.SubpixelOrder.RGB, false, PanelCalibration.YOrigin.TOP);
        check(bottom.viewIndex(0, 0, 4, 0, 4) == 2, "bottom first center y=.5");
        check(top.viewIndex(0, 0, 4, 0, 4) == 1, "top first GL row uses height-.5=3.5");
        check(top.viewIndex(0, 3, 4, 0, 4) == 2, "top last GL row uses .5");
        check(top.viewIndex(0, 0, 1, 0, 4) == bottom.viewIndex(0, 0, 1, 0, 4), "height one preserves its pixel center");
        for (int y = 0; y < 120; y++) for (int c = 0; c < 3; c++)
            check(top.viewIndex(7, y, 120, c, 20) == bottom.viewIndex(7, 119 - y, 120, c, 20), "origin reflects row centers");
    }
    private static void phaseWrapAndUnits() {
        PanelCalibration quarter = calibration(6, 0, .25f, false);
        check(quarter.viewIndex(0, 0, 1, 0, 4) == 2, "positive quarter phase advances one of four views");
        check(calibration(6, 0, -.25f, false).viewIndex(0, 0, 1, 0, 4) == 0, "negative quarter phase moves backward");
        equal(calibration(6, 0, 1.25f, false).phaseCycles(), .25f, "positive cycle normalization");
        equal(calibration(6, 0, -.75f, false).phaseCycles(), .25f, "negative cycle normalization");
        PanelCalibration pixels = new PanelCalibration(4, .25f, PanelCalibration.PitchUnits.PIXELS, .25f,
                PanelCalibration.SubpixelOrder.RGB, false, PanelCalibration.YOrigin.BOTTOM);
        PanelCalibration subpixels = calibration(12, .25f, .25f, false);
        equal(pixels.pitchSubpixels(), 12, "full pixel pitch multiplies by three");
        equal(pixels.tiltSubpixelsPerPixel(), .75f, "tan always uses full-pixel displacement");
        for (int x = 0; x < 120; x++) for (int c = 0; c < 3; c++)
            check(pixels.viewIndex(x, 73, 1920, c, 20) == subpixels.viewIndex(x, 73, 1920, c, 20), "equivalent pitch units");
        for (float phase : new float[]{-1024, -1, -0f, 0, 1, 1024}) {
            PanelCalibration p = calibration(6, 0, phase, false);
            check(p.viewIndex(0, 0, 1, 0, 4) == 1, "integer cycles do not change view selection");
            equal(p.phaseCycles(), 0, "integer phase canonical zero");
        }
    }
    private static void rejectInvalidConfigurationAndCoordinates() {
        for (float pitch : new float[]{Float.NaN, Float.POSITIVE_INFINITY, 0, -.1f, .5f, 4097})
            invalid(() -> calibration(pitch, 0, 0, false));
        for (float tan : new float[]{Float.NaN, Float.NEGATIVE_INFINITY, -4.01f, 4.01f})
            invalid(() -> calibration(10, tan, 0, false));
        for (float phase : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1025, 1025})
            invalid(() -> calibration(10, 0, phase, false));
        invalid(() -> new PanelCalibration(10, 0, null, 0, PanelCalibration.SubpixelOrder.RGB, false, PanelCalibration.YOrigin.BOTTOM));
        invalid(() -> new PanelCalibration(10, 0, PanelCalibration.PitchUnits.SUBPIXELS, 0, null, false, PanelCalibration.YOrigin.BOTTOM));
        invalid(() -> new PanelCalibration(10, 0, PanelCalibration.PitchUnits.SUBPIXELS, 0, PanelCalibration.SubpixelOrder.RGB, false, null));
        PanelCalibration p = PanelCalibration.defaults();
        invalid(() -> p.viewIndex(-1, 0, 1, 0, 20));
        invalid(() -> p.viewIndex(16384, 0, 1, 0, 20));
        invalid(() -> p.viewIndex(0, -1, 1, 0, 20));
        invalid(() -> p.viewIndex(0, 1, 1, 0, 20));
        invalid(() -> p.viewIndex(0, 0, 0, 0, 20));
        invalid(() -> p.viewIndex(0, 0, 16385, 0, 20));
        invalid(() -> p.viewIndex(0, 0, 1, -1, 20));
        invalid(() -> p.viewIndex(0, 0, 1, 3, 20));
        invalid(() -> p.viewIndex(0, 0, 1, 0, 0));
        invalid(() -> p.viewIndex(0, 0, 1, 0, 33));
        check(p.viewIndex(16383, 16383, 16384, 2, 32) >= 0, "upper supported coordinate boundary");
        calibration(1, -4, -1024, false);
        calibration(4096, 4, 1024, false);
    }
    private static void roundingAtNegativeCycleBoundaryKeepsValidIndices() {
        PanelCalibration tinyNegativePhase = calibration(6, 0, -Float.MIN_VALUE, false);
        check(tinyNegativePhase.phaseCycles() >= 0 && tinyNegativePhase.phaseCycles() < 1,
                "phase normalization stays half-open even if subtraction rounds to one");
        // At x=.5, y=1.5, tilt slightly below -1, the spatial phase is just below zero.
        // float fract can round that value to 1.0; the actual valid view is the final layer.
        PanelCalibration edge = calibration(4096, Math.nextDown(-1f / 3f), 0, false);
        check(edge.viewIndex(0, 1, 2, 0, 20) == 19, "negative infinitesimal selects final valid view");
        PanelCalibration reversed = calibration(4096, Math.nextDown(-1f / 3f), 0, true);
        check(reversed.viewIndex(0, 1, 2, 0, 20) == 0, "reverse of negative boundary remains nonnegative");
    }
    private static void originalDefaultFormulaRandomComparison() {
        PanelCalibration p = PanelCalibration.defaults();
        Random random = new Random(0x50414e454cL);
        int[] counts = {1, 4, 16, 20, 24, 32};
        for (int i = 0; i < 250_000; i++) {
            int x = random.nextInt(1200), y = random.nextInt(1920), channel = random.nextInt(3);
            int count = counts[random.nextInt(counts.length)];
            // Directly pinned from the original shader expression, not the new configurable implementation.
            float q = (((float)x + .5f) * 3f + ((float)y + .5f) * (.2777777f * 3f) + channel) / 10f;
            int original = (int)Math.floor((q - (float)Math.floor(q)) * (float)count);
            check(p.viewIndex(x, y, 1920, channel, count) == original, "default original-formula equivalence");
        }
    }
    private static PanelCalibration calibration(float pitch, float tan, float phase, boolean reverse) {
        return new PanelCalibration(pitch, tan, PanelCalibration.PitchUnits.SUBPIXELS, phase,
                PanelCalibration.SubpixelOrder.RGB, reverse, PanelCalibration.YOrigin.BOTTOM);
    }
    private static void equal(float actual, float expected, String message) {
        check(Float.floatToIntBits(actual) == Float.floatToIntBits(expected), message + ": " + actual);
    }
    private static void check(boolean okay, String message) { checks++; if (!okay) throw new AssertionError(message); }
    private static void invalid(Runnable action) {
        try { action.run(); throw new AssertionError("Expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
}
