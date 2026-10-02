package com.mirror.bench;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable versioned settings. Reading/migrating and editing a draft never writes preferences. */
final class MirrorSettings {
    static final int SCHEMA_VERSION = 2;
    static final int[] ANALYSIS_RATES = {10, 17, 20};
    static final String[] VIEW_PRESETS = {"240x720", "320x576", "400x720"};
    final int activeFps, viewWidth, viewHeight;
    final String viewPreset, warning;
    final PanelCalibration panel;
    final boolean writable;

    private MirrorSettings(int activeFps, String viewPreset, PanelCalibration panel, boolean writable, String warning) {
        this.activeFps = allowedRate(activeFps) ? activeFps : 17;
        this.viewPreset = allowedPreset(viewPreset) ? viewPreset : "320x576";
        String[] dimensions = this.viewPreset.split("x");
        viewWidth = Integer.parseInt(dimensions[0]); viewHeight = Integer.parseInt(dimensions[1]);
        this.panel = panel; this.writable = writable; this.warning = warning;
    }
    static MirrorSettings load(Context context) { return decode(preferences(context).getAll()); }
    static MirrorSettings decode(Map<String,?> values) {
        int version;
        try { version = integer(values,"schema_version",0); }
        catch (RuntimeException bad) {
            return new MirrorSettings(17,"320x576",PanelCalibration.defaults(),false,"配置版本损坏，未覆盖原配置");
        }
        if(version < 0 || version > SCHEMA_VERSION)
            return new MirrorSettings(17,"320x576",PanelCalibration.defaults(),false,"配置版本不兼容，未覆盖原配置");
        int fps = 17; String preset = "320x576", warning = "";
        try {
            fps = integer(values,"active_fps",17); preset = string(values,"view_preset","320x576");
            if(!allowedRate(fps)||!allowedPreset(preset)) throw new IllegalArgumentException();
        } catch(RuntimeException bad) { fps=17; preset="320x576"; warning="运行配置无效，暂用默认值"; }
        PanelCalibration panel = PanelCalibration.defaults();
        if(version == SCHEMA_VERSION) try {
            panel = new PanelCalibration(decimal(values,"panel_pitch",10f), decimal(values,"panel_tan",.2777777f),
                    PanelCalibration.PitchUnits.valueOf(string(values,"panel_units","SUBPIXELS")),
                    decimal(values,"panel_phase",0f),
                    PanelCalibration.SubpixelOrder.valueOf(string(values,"panel_order","RGB")),
                    bool(values,"panel_reverse",false),
                    PanelCalibration.YOrigin.valueOf(string(values,"panel_origin","BOTTOM")));
        } catch(RuntimeException bad) { warning += (warning.isEmpty()?"":"；")+"光学配置无效，暂用默认值，保存才覆盖"; }
        return new MirrorSettings(fps,preset,panel,true,warning);
    }
    MirrorSettings withProfile(int fps,String preset) {
        requireWritable();
        if(!allowedRate(fps)||!allowedPreset(preset)) throw new IllegalArgumentException("Unsupported runtime profile");
        return new MirrorSettings(fps,preset,panel,true,"");
    }
    MirrorSettings withPanel(PanelCalibration value) {
        requireWritable();
        if(value == null) throw new IllegalArgumentException("Panel calibration required");
        return new MirrorSettings(activeFps,viewPreset,value,true,"");
    }
    Map<String,Object> toMap() {
        Map<String,Object> values=new LinkedHashMap<>();
        values.put("schema_version",SCHEMA_VERSION); values.put("active_fps",activeFps); values.put("view_preset",viewPreset);
        values.put("panel_pitch",panel.pitch()); values.put("panel_tan",panel.tan()); values.put("panel_units",panel.pitchUnits().name());
        values.put("panel_phase",panel.phaseCycles()); values.put("panel_order",panel.subpixelOrder().name());
        values.put("panel_reverse",panel.reverseViews()); values.put("panel_origin",panel.yOrigin().name());
        return values;
    }
    static void save(Context context,int fps,String preset) { write(context,load(context).withProfile(fps,preset)); }
    static void savePanel(Context context,PanelCalibration panel) { write(context,load(context).withPanel(panel)); }
    private static void write(Context context,MirrorSettings value) {
        value.requireWritable(); SharedPreferences.Editor edit=preferences(context).edit();
        for(Map.Entry<String,Object> entry:value.toMap().entrySet()) {
            Object v=entry.getValue(); String key=entry.getKey();
            if(v instanceof Integer)edit.putInt(key,(Integer)v);
            else if(v instanceof Float)edit.putFloat(key,(Float)v);
            else if(v instanceof Boolean)edit.putBoolean(key,(Boolean)v);
            else edit.putString(key,(String)v);
        }
        // Explicit save must succeed before the caller reports success/recreates the renderer.
        if(!edit.commit()) throw new IllegalStateException("配置写入失败，请重试");
    }
    private void requireWritable() { if(!writable)throw new IllegalArgumentException(warning); }
    private static SharedPreferences preferences(Context context) { return context.getSharedPreferences("mirror-runtime",Context.MODE_PRIVATE); }
    private static int integer(Map<String,?> v,String k,int fallback) { return v.containsKey(k)?(Integer)v.get(k):fallback; }
    private static float decimal(Map<String,?> v,String k,float fallback) { return v.containsKey(k)?(Float)v.get(k):fallback; }
    private static String string(Map<String,?> v,String k,String fallback) {
        String s=v.containsKey(k)?(String)v.get(k):fallback;
        if(s==null)throw new IllegalArgumentException(k); return s;
    }
    private static boolean bool(Map<String,?> v,String k,boolean fallback) { return v.containsKey(k)?(Boolean)v.get(k):fallback; }
    private static boolean allowedRate(int value) { for(int choice:ANALYSIS_RATES)if(choice==value)return true; return false; }
    private static boolean allowedPreset(String value) { for(String choice:VIEW_PRESETS)if(choice.equals(value))return true; return false; }
}
