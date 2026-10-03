package com.mirror.bench;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.lang.reflect.*;
import java.util.*;

/** Real runtime renderer/profile-save methods and Intent factories; no Android UI or GL execution. */
public final class ProductRuntimeViewCountTest {
    private static int checks,commits;
    private static boolean commitOkay=true;
    private static Map<String,Object> stored;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static Object call(Object owner,String name,Class<?>[] types,Object... args)throws Exception {
        Method method=owner.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(owner,args);
    }
    private static void set(Object owner,String name,Object value)throws Exception {
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    public static void main(String[] args)throws Exception {
        Class<?> optionType=Class.forName("com.mirror.bench.MirrorActivity$InputOptions");
        Method parse=optionType.getDeclaredMethod("read",Bundle.class,boolean.class,int.class);parse.setAccessible(true);
        Field count=optionType.getDeclaredField("viewCount");count.setAccessible(true);
        Field unsafeField=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
        var unsafe=(sun.misc.Unsafe)unsafeField.get(null);
        SharedPreferences preferences=(SharedPreferences)Proxy.newProxyInstance(ProductRuntimeViewCountTest.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,input)->{
            if(method.getName().equals("getAll"))return new HashMap<>(stored);
            if(method.getName().equals("edit")){
                Map<String,Object> pending=new HashMap<>(stored);
                return Proxy.newProxyInstance(ProductRuntimeViewCountTest.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(editor,operation,values)->{
                    if(operation.getName().startsWith("put")){pending.put((String)values[0],values[1]);return editor;}
                    // Android can update its memory map even when disk commit reports failure.
                    if(operation.getName().equals("commit")){commits++;stored=new HashMap<>(pending);return commitOkay;}
                    throw new AssertionError(operation.getName());
                });
            }
            throw new AssertionError(method.getName());
        });
        Activity.preferences=preferences;
        PanelCalibration panel=new PanelCalibration(12.5f,-.2f,PanelCalibration.PitchUnits.PIXELS,.875f,PanelCalibration.SubpixelOrder.BGR,true,PanelCalibration.YOrigin.TOP);
        for(int savedCount:new int[]{16,20})for(boolean debug:new boolean[]{false,true}) {
            MirrorSettings saved=MirrorSettings.decode(Map.of("schema_version",4,"view_count",savedCount,"view_preset","400x640")).withPanel(panel);
            stored=new HashMap<>(saved.toMap());
            Object options=parse.invoke(null,null,debug,savedCount);
            MirrorActivity activity=(MirrorActivity)unsafe.allocateInstance(MirrorActivity.class);set(activity,"settings",saved);set(activity,"options",options);
            InterlaceRenderer renderer=(InterlaceRenderer)call(activity,"createRuntimeRenderer",new Class<?>[]{});
            check(renderer.runtimeStatus().getInt("view_count")==savedCount,"production runtime renderer uses stored count in debug/release");
            for(String size:new String[]{"requestedViewWidth","requestedViewHeight"}){
                Field field=InterlaceRenderer.class.getDeclaredField(size);field.setAccessible(true);
                check(field.getInt(renderer)==(size.equals("requestedViewWidth")?400:640),"actual requested dimensions retained");
            }
            var calibration=CalibrationActivity.intent(null,savedCount,debug);
            int calibrated=RuntimeViewCount.readConfigured(calibration.getExtras(),debug,20);
            var preview=PanelPreviewActivity.intent(null,panel,calibrated,debug);
            check(calibrated==savedCount&&RuntimeViewCount.readConfigured(preview.getExtras(),debug,20)==savedCount,"actual main/calibration/panel count chain");
            check(preview.getExtras().get("phase").equals(panel.phaseCycles())&&preview.getExtras().get("reverse").equals(true),"count propagation preserves optics");
            if(!debug)check(calibration.getExtras().get("test_view_count")==null&&preview.getExtras().get("test_view_count")==null,"release uses no debug override");
            var previewActivity=(PanelPreviewActivity)unsafe.allocateInstance(PanelPreviewActivity.class);set(previewActivity,"viewCount",calibrated);
            var previewRenderer=(InterlaceRenderer)call(previewActivity,"createRenderer",new Class<?>[]{PanelCalibration.class},panel);
            check(previewRenderer.runtimeStatus().getInt("view_count")==savedCount,"actual panel renderer receives configured count");
            if(debug) {
                Bundle extras=new Bundle();extras.putInt("test_view_count",savedCount==16?20:16);
                var snapshot=new HashMap<>(stored);set(activity,"options",parse.invoke(null,extras,true,savedCount));
                var overridden=(InterlaceRenderer)call(activity,"createRuntimeRenderer",new Class<?>[]{});
                check(overridden.runtimeStatus().getInt("view_count")!=(savedCount),"debug override reaches actual renderer");
                check(stored.equals(snapshot)&&MirrorSettings.load(activity).viewCount==savedCount,"debug renderer override never persists");
            }
        }
        MirrorSettings legacy=MirrorSettings.decode(Map.of("schema_version",3,"active_fps",20,"view_preset","400x720")).withPanel(panel);
        stored=new HashMap<>(legacy.toMap());stored.put("schema_version",3);stored.remove("view_count");var original=new HashMap<>(stored);
        MirrorActivity activity=(MirrorActivity)unsafe.allocateInstance(MirrorActivity.class);set(activity,"settings",legacy);set(activity,"options",parse.invoke(null,null,false,legacy.viewCount));
        InterlaceRenderer activeRenderer=(InterlaceRenderer)call(activity,"createRuntimeRenderer",new Class<?>[]{});set(activity,"renderer",activeRenderer);
        Activity.recreates=0;int before=commits;
        call(activity,"saveRuntimeProfile",new Class<?>[]{int.class,String.class,int.class},20,"400x720",20);
        check(commits==before+1&&Activity.recreates==0&&stored.get("schema_version").equals(4)&&stored.get("view_count").equals(20),"explicit unchanged save migrates once without recreation");
        for(String key:original.keySet())if(!key.equals("schema_version"))check(stored.get(key).equals(original.get(key)),"explicit migration preserves "+key);
        original=new HashMap<>(stored);
        MirrorSettings draft=legacy.withDefaultProfile();
        check(draft.viewCount==16&&draft.viewPreset.equals("400x640")&&stored.equals(original),"default/cancel draft preserves stored configuration");
        commitOkay=false;
        try{call(activity,"saveRuntimeProfile",new Class<?>[]{int.class,String.class,int.class},17,"400x640",16);throw new AssertionError("failed commit accepted");}
        catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalStateException,"failed commit is explicit");}
        check(Activity.recreates==0,"failed commit never recreates");
        Field active=MirrorActivity.class.getDeclaredField("options");active.setAccessible(true);
        check(count.getInt(active.get(activity))==20,"failed commit never changes active count");
        Field currentSettings=MirrorActivity.class.getDeclaredField("settings");currentSettings.setAccessible(true);
        check(currentSettings.get(activity)==legacy&&activeRenderer.runtimeStatus().getInt("view_count")==20,"failed commit leaves current settings and renderer intact");
        var calibration=CalibrationActivity.intent(null,count.getInt(active.get(activity)),false);
        check(RuntimeViewCount.readConfigured(calibration.getExtras(),false,MirrorSettings.load(activity).viewCount)==20,"failed commit memory update cannot half-switch calibration");
        var preview=PanelPreviewActivity.intent(null,panel,20,false);
        check(RuntimeViewCount.readConfigured(preview.getExtras(),false,MirrorSettings.load(activity).viewCount)==20,"failed commit memory update cannot half-switch panel preview");
        // Restore the host storage fixture; this is not a production restore operation.
        stored=new HashMap<>(original);commitOkay=true;
        call(activity,"saveRuntimeProfile",new Class<?>[]{int.class,String.class,int.class},17,"400x640",16);
        check(Activity.recreates==1&&MirrorSettings.load(activity).viewCount==16,"only successful commit requests recreation");
        check(count.getInt(active.get(activity))==20&&currentSettings.get(activity)==legacy&&activeRenderer.runtimeStatus().getInt("view_count")==20,"successful save waits for recreation to activate all runtime fields");
        check(stored.get("active_fps").equals(17)&&stored.get("view_preset").equals("400x640")&&stored.get("schema_version").equals(4),"one validated saved runtime profile");
        for(String key:original.keySet())if(key.startsWith("panel_")||key.startsWith("camera_"))check(stored.get(key).equals(original.get(key)),"save preserves "+key);
        stored.put("schema_version",5);set(activity,"settings",MirrorSettings.load(activity));Activity.recreates=0;before=commits;
        try{call(activity,"saveRuntimeProfile",new Class<?>[]{int.class,String.class,int.class},20,"400x720",20);throw new AssertionError("future save");}
        catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalArgumentException,"future save protected");}
        check(commits==before&&Activity.recreates==0,"future schema never commits or recreates");
        System.out.println("ProductRuntimeViewCountTest: "+checks+" checks passed; actual runtime methods/Intent factories, no UI/GL/lifecycle claim");
    }
}
