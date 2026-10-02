package com.mirror.bench;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Host contract checks; actual shader execution is covered by PanelPreviewActivity on the device. */
public final class PanelRendererConfigTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        InterlaceRenderer r=new InterlaceRenderer(20,.3f,false);
        check((Float)field(r,"pitch")==9.69f&&(Float)field(r,"tilt")==.28f,"bench diagnostic defaults intact");
        Method shader=InterlaceRenderer.class.getDeclaredMethod("calibrationShader",String.class);shader.setAccessible(true);
        for(String name:new String[]{"INTERLACE_FRAGMENT","LOOKUP_GENERATOR","ATLAS_FRAGMENT"}) {
            String source=(String)field(r,name);
            check(shader.invoke(r,source).equals(source),"legacy shader text unchanged: "+name);
        }
        r.setPanelCalibration(PanelCalibration.defaults());check(r.supportsSharedPhase(),"supported shared phase default");
        check(((String)shader.invoke(r,(String)field(r,"SCREEN_VERTEX"))).startsWith("#version 320 es"),"calibrated vertex uses matching ES3.20 language");
        String function=(String)field(r,"PANEL_VIEW_FUNCTION");
        for(String name:new String[]{"INTERLACE_FRAGMENT","LOOKUP_GENERATOR","ATLAS_FRAGMENT"}) {
            String compiled=(String)shader.invoke(r,(String)field(r,name));
            check(compiled.startsWith("#version 320 es"),"calibrated fragment uses ES3.20: "+name);
            check(compiled.contains("precise float q="),"phase divide/add must not contract into FMA: "+name);
            check(compiled.contains(function),"same calibrated phase function in "+name);
            check(!compiled.contains((String)field(r,"LEGACY_VIEW_FUNCTION")),"legacy formula replaced in "+name);
        }
        for(int i=0;i<5;i++) {
            r.setPanelCalibration(new PanelCalibration(i==4?11:10,.2777777f,PanelCalibration.PitchUnits.SUBPIXELS,
                    i==0?.25f:0,i==1?PanelCalibration.SubpixelOrder.BGR:PanelCalibration.SubpixelOrder.RGB,
                    i==2,i==3?PanelCalibration.YOrigin.TOP:PanelCalibration.YOrigin.BOTTOM));
            check(!r.supportsSharedPhase(),"unsupported optimized formula rejected: "+i);
        }
        r.setPanelParameters(10,.2777777f,"subpixels");
        check(field(r,"panelCalibration")==null&&r.supportsSharedPhase(),"legacy setter restores legacy path");
        Field initialized=InterlaceRenderer.class.getDeclaredField("surfaceInitialized");initialized.setAccessible(true);initialized.set(r,true);
        try {r.setPanelCalibration(PanelCalibration.defaults());throw new AssertionError("mutated active GL parameters");}
        catch(IllegalStateException expected) {checks++;}
        System.out.println("PanelRendererConfigTest: "+checks+" checks passed");
    }
    private static Object field(Object target,String name) throws Exception {
        Field f=InterlaceRenderer.class.getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    private static void check(boolean value,String label) {if(!value)throw new AssertionError(label);checks++;}
}
