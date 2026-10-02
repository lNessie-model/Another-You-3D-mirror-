package com.mirror.bench;

import android.content.Context;
import android.content.SharedPreferences;

/** Small persisted allowlist; device experiments cannot inject arbitrary render dimensions. */
final class MirrorSettings {
    static final int[] ANALYSIS_RATES = {10, 17, 20};
    static final String[] VIEW_PRESETS = {"240x720", "320x576", "400x720"};
    final int activeFps, viewWidth, viewHeight;
    final String viewPreset;

    private MirrorSettings(int activeFps, String viewPreset) {
        this.activeFps = allowedRate(activeFps) ? activeFps : 17;
        this.viewPreset = allowedPreset(viewPreset) ? viewPreset : "320x576";
        String[] dimensions = this.viewPreset.split("x");
        viewWidth = Integer.parseInt(dimensions[0]);
        viewHeight = Integer.parseInt(dimensions[1]);
    }
    static MirrorSettings load(Context context) {
        SharedPreferences preferences = preferences(context);
        try { return new MirrorSettings(preferences.getInt("active_fps", 17),
                preferences.getString("view_preset", "320x576")); }
        catch (ClassCastException invalidOldConfiguration) { return new MirrorSettings(17, "320x576"); }
    }
    static void save(Context context, int activeFps, String viewPreset) {
        if (!allowedRate(activeFps) || !allowedPreset(viewPreset))
            throw new IllegalArgumentException("Unsupported runtime profile");
        preferences(context).edit().putInt("schema_version", 1).putInt("active_fps", activeFps)
                .putString("view_preset", viewPreset).apply();
    }
    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("mirror-runtime", Context.MODE_PRIVATE);
    }
    private static boolean allowedRate(int value) {
        for (int choice : ANALYSIS_RATES) if (choice == value) return true;
        return false;
    }
    private static boolean allowedPreset(String value) {
        for (String choice : VIEW_PRESETS) if (choice.equals(value)) return true;
        return false;
    }
}
