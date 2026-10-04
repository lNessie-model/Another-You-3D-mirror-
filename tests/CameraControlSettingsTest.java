package com.mirror.bench;

import java.util.HashMap;
import java.util.Map;

public final class CameraControlSettingsTest {
    private static int checks;
    public static void main(String[] args) {
        var defaults=CameraControlSettings.DEFAULT;
        check(defaults.rotationDegrees==0&&!defaults.reflectInput&&!defaults.mirrorInteraction&&defaults.revision==0,"legacy installation remains identity");
        check(defaults.cameraId.isEmpty()&&defaults.fingerprint.isEmpty()&&!defaults.isBound(),"default does not claim known hardware");
        var camera=new CameraControlSettings("100","chars-sha256:abc",640,480,90,true,true,23);
        check(camera.isBound()&&camera.matches("100","chars-sha256:abc",640,480),"binding requires exact characteristics and dimensions");
        check(!camera.matches("101","chars-sha256:abc",640,480)&&!camera.matches("100","chars-sha256:changed",640,480)&&!camera.matches("100","chars-sha256:abc",480,640),"identity or dimensions mismatch cannot reuse calibration");
        var input=camera.withInput("200","chars-sha256:def",800,600,270,false,24);
        check(input.mirrorInteraction&&input.rotationDegrees==270&&input.revision==24&&camera.rotationDegrees==90,"input draft immutable and preserves action mirror");
        var mirror=input.withMirror(false,25);
        check(!mirror.mirrorInteraction&&mirror.cameraId.equals("200")&&mirror.rotationDegrees==270&&mirror.revision==25,"mirror draft preserves installation");
        rejects(()->new CameraControlSettings(null,"",640,480,0,false,false,0),"null identity");
        rejects(()->new CameraControlSettings("100","",640,480,0,false,false,0),"partially bound identity");
        rejects(()->new CameraControlSettings("","hash",640,480,0,false,false,0),"partially bound fingerprint");
        rejects(()->new CameraControlSettings("100\n","hash",640,480,0,false,false,0),"control characters in identity");
        rejects(()->new CameraControlSettings("100","x".repeat(257),640,480,0,false,false,0),"bounded fingerprint");
        rejects(()->new CameraControlSettings("","",0,480,0,false,false,0),"zero dimensions");
        rejects(()->new CameraControlSettings("","",Integer.MAX_VALUE,Integer.MAX_VALUE,0,false,false,0),"pixel multiplication overflow");
        rejects(()->new CameraControlSettings("","",640,480,45,false,false,0),"unsupported direction");
        rejects(()->new CameraControlSettings("","",640,480,0,false,false,-1),"negative revision");
        for(int version=0;version<=2;version++){
            Map<String,Object> old=panelFixture();old.put("schema_version",version);
            MirrorSettings decoded=MirrorSettings.decode(old);
            check(decoded.writable&&decoded.activeFps==20&&decoded.viewWidth==400,"old profile preserved v"+version);
            check(decoded.camera==CameraControlSettings.DEFAULT,"old version gets explicit unbound default v"+version);
            check(decoded.panel.pitch()==(version==2?12.5f:10f),"v2 optical settings survive schema increment");
        }
        MirrorSettings v2=MirrorSettings.decode(panelFixture());
        Map<String,Object> v3=v2.withCamera(camera).toMap();
        check(v3.get("schema_version").equals(4)&&v3.get("camera_revision").equals(23L),"schema4 and long revision written exactly");
        MirrorSettings recovered=MirrorSettings.decode(v3);
        sameCamera(camera,recovered.camera);
        check(recovered.panel.pitch()==12.5f&&recovered.panel.phaseCycles()==.875f&&recovered.panel.subpixelOrder()==PanelCalibration.SubpixelOrder.BGR&&recovered.panel.yOrigin()==PanelCalibration.YOrigin.TOP,"v2 custom optical data fully retained");
        check(recovered.withProfile(10,"240x720").camera==recovered.camera,"profile save preserves camera immutable value");
        check(recovered.withPanel(PanelCalibration.defaults()).camera==recovered.camera,"panel save preserves camera immutable value");
        check(v2.camera==CameraControlSettings.DEFAULT&&recovered.activeFps==20,"camera edit leaves source/profile unchanged");
        for(String field:new String[]{"camera_rotation_degrees","camera_revision","camera_reflect_input"}){
            Map<String,Object> bad=new HashMap<>(v3);bad.put(field,"bad");var decoded=MirrorSettings.decode(bad);
            check(decoded.camera==CameraControlSettings.DEFAULT&&!decoded.warning.isEmpty()&&decoded.panel.pitch()==12.5f,"corrupt camera isolated: "+field);
        }
        Map<String,Object> future=new HashMap<>(v3);future.put("schema_version",5);var protectedSettings=MirrorSettings.decode(future);
        check(!protectedSettings.writable,"future schema not writable");
        rejects(()->protectedSettings.withCamera(camera),"future camera save");
        rejects(()->protectedSettings.withProfile(17,"320x576"),"future profile save");
        rejects(()->protectedSettings.withPanel(PanelCalibration.defaults()),"future panel save");
        for(String key:v3.keySet())check(!key.contains("neutral")&&!key.contains("baseline")&&!key.contains("pose"),"personal controls must not enter preferences: "+key);
        System.out.println("CameraControlSettingsTest: "+checks+" checks passed");
    }
    private static Map<String,Object> panelFixture(){Map<String,Object> v=new HashMap<>();v.put("schema_version",2);v.put("active_fps",20);v.put("view_preset","400x720");v.put("panel_pitch",12.5f);v.put("panel_tan",-.2f);v.put("panel_units","PIXELS");v.put("panel_phase",.875f);v.put("panel_order","BGR");v.put("panel_reverse",true);v.put("panel_origin","TOP");return v;}
    private static void sameCamera(CameraControlSettings a,CameraControlSettings b){check(a.cameraId.equals(b.cameraId)&&a.fingerprint.equals(b.fingerprint)&&a.width==b.width&&a.height==b.height&&a.rotationDegrees==b.rotationDegrees&&a.reflectInput==b.reflectInput&&a.mirrorInteraction==b.mirrorInteraction&&a.revision==b.revision,"camera round trip");}
    private static void rejects(Runnable r,String m){try{r.run();throw new AssertionError("Accepted "+m);}catch(IllegalArgumentException expected){checks++;}}
    private static void check(boolean value,String m){checks++;if(!value)throw new AssertionError(m);}
}
