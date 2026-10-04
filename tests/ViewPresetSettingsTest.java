package com.mirror.bench;

import android.content.ContextWrapper;
import android.content.SharedPreferences;
import java.lang.reflect.*;
import java.util.*;

/** Real settings codec/save transaction with an in-memory preferences implementation. */
public final class ViewPresetSettingsTest {
    private static int checks;
    private static int edits,commits;
    private static boolean commitOkay=true;
    private static Map<String,Object> stored;
    private static SharedPreferences preferences;
    static final class Context extends ContextWrapper {
        Context(){super(null);}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return preferences;}
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable task){try{task.run();throw new AssertionError("rejected configuration accepted");}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] args)throws Exception {
        MirrorSettings fresh=MirrorSettings.decode(Map.of());
        check(fresh.viewWidth==400&&fresh.viewHeight==640&&fresh.activeFps==17&&fresh.viewCount==16,"fresh default must be 400x640");
        for(int version=0;version<=3;version++)for(String preset:new String[]{"240x720","320x576","400x720","400x640"}){
            var values=new HashMap<String,Object>();values.put("schema_version",version);values.put("active_fps",20);values.put("view_preset",preset);
            var copy=new HashMap<>(values);MirrorSettings migrated=MirrorSettings.decode(values);
            check(migrated.writable&&migrated.viewPreset.equals(preset)&&migrated.activeFps==20&&migrated.viewCount==20,"legal old profile retained");
            check(values.equals(copy),"read/migration never writes its source");
            check(MirrorSettings.decode(migrated.toMap()).viewPreset.equals(preset),"profile roundtrip");
        }
        for(Object bad:new Object[]{"400x641",null,640,Boolean.TRUE}){
            Map<String,Object> values=new HashMap<>();values.put("view_preset",bad);var decoded=MirrorSettings.decode(values);
            check(decoded.viewPreset.equals("400x640")&&!decoded.warning.isEmpty(),"invalid profile falls back explicitly");
        }
        for(int count:new int[]{16,20}) {
            var values=new HashMap<String,Object>();values.put("schema_version",4);values.put("view_count",count);
            values.put("view_preset","400x720");values.put("active_fps",10);
            var decoded=MirrorSettings.decode(values);
            check(decoded.writable&&decoded.viewCount==count&&decoded.viewPreset.equals("400x720"),"schema4 count roundtrip");
            check(MirrorSettings.decode(decoded.toMap()).viewCount==count,"schema4 count persisted");
            check(decoded.withProfile(17,"400x640").viewCount==count,"legacy profile API preserves count");
            check(decoded.withPanel(PanelCalibration.defaults()).viewCount==count,"panel draft preserves count");
            check(decoded.withCamera(CameraControlSettings.DEFAULT).viewCount==count,"camera draft preserves count");
        }
        for(Object bad:new Object[]{17,0,24,"16",16L,16f,true,null}) {
            var values=new HashMap<String,Object>();values.put("schema_version",4);values.put("view_count",bad);
            var decoded=MirrorSettings.decode(values);
            check(!decoded.writable&&decoded.viewCount==16&&!decoded.warning.isEmpty(),"damaged schema4 count protected");
            rejects(()->decoded.withDefaultProfile());
        }
        check(!MirrorSettings.decode(Map.of("schema_version",4)).writable,"missing schema4 count protected");
        for(Object bad:new Object[]{5,-1,"4",4L,true}) {
            var values=new HashMap<String,Object>();values.put("schema_version",bad);values.put("view_count",20);
            check(!MirrorSettings.decode(values).writable&&MirrorSettings.decode(values).viewCount==16,"future/corrupt version read only");
        }
        for(int version=0;version<=3;version++) {
            check(MirrorSettings.decode(Map.of("schema_version",version,"view_count",16)).viewCount==20,"legacy ignores unknown count");
        }
        rejects(()->fresh.withProfile(17,"400x640",17));
        stored=new HashMap<>(MirrorSettings.decode(Map.of("view_preset","400x720","active_fps",20))
                .withPanel(new PanelCalibration(12.5f,-.2f,PanelCalibration.PitchUnits.PIXELS,.875f,PanelCalibration.SubpixelOrder.BGR,true,PanelCalibration.YOrigin.TOP))
                .withCamera(new CameraControlSettings("100","fixture-characteristics",640,480,90,true,true,9)).toMap());
        stored.put("schema_version",3);stored.remove("view_count");
        var original=new HashMap<>(stored);
        preferences=(SharedPreferences)Proxy.newProxyInstance(ViewPresetSettingsTest.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,input)->{
            if(method.getName().equals("getAll"))return new HashMap<>(stored);
            if(method.getName().equals("edit")){
                edits++;Map<String,Object> pending=new HashMap<>(stored);
                return Proxy.newProxyInstance(ViewPresetSettingsTest.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(editor,operation,values)->{
                    if(operation.getName().startsWith("put")){pending.put((String)values[0],values[1]);return editor;}
                    if(operation.getName().equals("commit")){commits++;if(commitOkay)stored=new HashMap<>(pending);return commitOkay;}
                    throw new AssertionError(operation.getName());
                });
            }
            throw new AssertionError(method.getName());
        });
        Field f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var unsafe=(sun.misc.Unsafe)f.get(null);
        Context context=(Context)unsafe.allocateInstance(Context.class);
        MirrorSettings saved=MirrorSettings.load(context);
        MirrorSettings draft=saved.withDefaultProfile();
        check(edits==0&&commits==0&&stored.equals(original),"draft/default/cancel never writes");
        check(saved.viewPreset.equals("400x720")&&draft.panel==saved.panel&&draft.camera==saved.camera&&draft.viewCount==16&&saved.viewCount==20,"draft preserves source and calibration");
        MirrorSettings.save(context,draft.activeFps,draft.viewPreset,draft.viewCount);
        check(edits==1&&commits==1&&MirrorSettings.load(context).viewPreset.equals("400x640")&&MirrorSettings.load(context).viewCount==16,"explicit save commits new profile");
        check(stored.get("schema_version").equals(4)&&stored.get("view_count").equals(16),"schema4 exact Integer count");
        for(String key:original.keySet())if(key.startsWith("panel_")||key.startsWith("camera_"))check(stored.get(key).equals(original.get(key)),"default profile save preserves "+key);
        original=new HashMap<>(stored);commitOkay=false;
        try{MirrorSettings.save(context,20,"400x720",20);throw new AssertionError("failed commit accepted");}catch(IllegalStateException expected){checks++;}
        check(stored.equals(original)&&MirrorSettings.load(context).viewCount==16,"failed save retains old preferences");
        stored.put("schema_version",5);original=new HashMap<>(stored);int before=edits;
        rejects(()->MirrorSettings.save(context,17,"400x640"));
        rejects(()->MirrorSettings.load(context).withDefaultProfile());
        check(edits==before&&stored.equals(original)&&!MirrorSettings.load(context).writable,"future schema remains protected");
        System.out.println("ViewPresetSettingsTest: "+checks+" checks passed; host preferences only");
    }
}
