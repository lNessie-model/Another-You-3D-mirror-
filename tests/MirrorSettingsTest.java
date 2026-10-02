package com.mirror.bench;

import java.util.HashMap;
import java.util.Map;

public final class MirrorSettingsTest {
    private static int checks;
    public static void main(String[] args) {
        MirrorSettings legacy = MirrorSettings.decode(Map.of("schema_version", 1,
                "active_fps", 20, "view_preset", "400x720"));
        check(legacy.activeFps == 20 && legacy.viewWidth == 400, "v1 profile survives");
        check(legacy.panel.pitch() == 10 && legacy.panel.phaseCycles() == 0, "v1 panel defaults");
        check(!legacy.panel.opticalAlignmentVerified(), "migration never verifies optics");
        PanelCalibration custom = new PanelCalibration(12.5f, -.2f, PanelCalibration.PitchUnits.PIXELS,
                -.125f, PanelCalibration.SubpixelOrder.BGR, true, PanelCalibration.YOrigin.TOP);
        MirrorSettings changed = legacy.withPanel(custom);
        check(legacy.panel.phaseCycles() == 0, "draft immutable");
        MirrorSettings roundTrip = MirrorSettings.decode(changed.toMap());
        check(roundTrip.activeFps == 20 && roundTrip.viewPreset.equals("400x720"), "panel save preserves profile");
        check(roundTrip.panel.pitch() == 12.5f && roundTrip.panel.tan() == -.2f, "numeric roundtrip");
        check(roundTrip.panel.phaseCycles() == .875f && roundTrip.panel.pitchUnits() == PanelCalibration.PitchUnits.PIXELS,
                "unit and periodic phase roundtrip");
        check(roundTrip.panel.subpixelOrder() == PanelCalibration.SubpixelOrder.BGR
                && roundTrip.panel.reverseViews() && roundTrip.panel.yOrigin() == PanelCalibration.YOrigin.TOP, "flags roundtrip");
        MirrorSettings profile = changed.withProfile(10, "240x720");
        check(profile.panel == custom && profile.activeFps == 10, "profile edit preserves panel");
        Map<String,Object> bad = new HashMap<>(changed.toMap());
        bad.put("panel_pitch", Float.NaN);
        MirrorSettings recovered = MirrorSettings.decode(bad);
        check(recovered.activeFps == 20 && recovered.panel.pitch() == 10 && !recovered.warning.isEmpty(), "corrupt panel isolated");
        bad.put("schema_version", 200);
        MirrorSettings future = MirrorSettings.decode(bad);
        check(!future.writable && !future.warning.isEmpty(), "future version protected");
        rejects(() -> future.withPanel(custom), "future calibration save");
        rejects(() -> future.withProfile(17, "320x576"), "future profile save");
        rejects(() -> changed.withProfile(18, "320x576"), "invalid fps");
        rejects(() -> changed.withProfile(17, "9999x9999"), "invalid dimensions");
        bad = new HashMap<>(changed.toMap()); bad.put("panel_reverse", "true");
        check(!MirrorSettings.decode(bad).warning.isEmpty(), "wrong stored type explicit");
        check(MirrorSettings.decode(Map.of("active_fps", "twenty")).activeFps == 17, "corrupt profile defaults");
        System.out.println("MirrorSettingsTest: " + checks + " checks passed");
    }
    private static void rejects(Runnable run,String label) {
        try { run.run(); throw new AssertionError(label); } catch (IllegalArgumentException expected) { checks++; }
    }
    private static void check(boolean value,String label) { if(!value)throw new AssertionError(label); checks++; }
}
