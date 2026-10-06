package com.mirror.bench;

import android.os.Bundle;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.HashSet;
import org.json.JSONObject;

/** Actual option parsing, bounded matrices, report contract; no claim of host GL execution. */
public final class RuntimeViewCountTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    private static Method read;
    private static Field count;
    private static int parsed(Bundle bundle,boolean debug)throws Exception{return count.getInt(read.invoke(null,bundle,debug));}
    private static void rejected(Bundle bundle,boolean debug)throws Exception {
        try{parsed(bundle,debug);throw new AssertionError("Accepted invalid view override");}
        catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalArgumentException);}
    }
    public static void main(String[] args)throws Exception {
        Class<?> type=Class.forName("com.mirror.bench.MirrorActivity$InputOptions");
        read=type.getDeclaredMethod("read",Bundle.class,boolean.class);read.setAccessible(true);
        count=type.getDeclaredField("viewCount");count.setAccessible(true);
        Method configuredRead=type.getDeclaredMethod("read",Bundle.class,boolean.class,int.class);configuredRead.setAccessible(true);
        for(int saved:new int[]{16,20}) {
            check(count.getInt(configuredRead.invoke(null,null,false,saved))==saved);
            check(count.getInt(configuredRead.invoke(null,new Bundle(),true,saved))==saved);
            for(int override:new int[]{16,20}) {
                Bundle changed=new Bundle();changed.putInt("test_view_count",override);
                check(count.getInt(configuredRead.invoke(null,changed,true,saved))==override);
                try{configuredRead.invoke(null,changed,false,saved);throw new AssertionError("release debug override");}
                catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalArgumentException);}
            }
        }

        // The calibration transport cannot override the main release settings.
        Bundle internal=new Bundle();internal.putInt("runtime_view_count",16);
        check(count.getInt(configuredRead.invoke(null,internal,false,20))==20);
        check(RuntimeViewCount.read(internal,false)==20);
        check(RuntimeViewCount.readConfigured(null,false,16)==16);
        check(RuntimeViewCount.readConfigured(null,false,20)==20);
        for(Object bad:new Object[]{17,0,24,"16",16L,16f,true,null}) {
            Bundle wrong=new Bundle();
            if(bad instanceof Integer)wrong.putInt("runtime_view_count",(Integer)bad);
            else if(bad instanceof Long)wrong.putLong("runtime_view_count",(Long)bad);
            else if(bad instanceof Float)wrong.putFloat("runtime_view_count",(Float)bad);
            else if(bad instanceof Boolean)wrong.putBoolean("runtime_view_count",(Boolean)bad);
            else wrong.putString("runtime_view_count",(String)bad);
            for(boolean debug:new boolean[]{false,true}) {
                try{RuntimeViewCount.readConfigured(wrong,debug,20);throw new AssertionError("invalid calibration count");}
                catch(IllegalArgumentException expected){checks++;}
            }
        }
        internal.putInt("test_view_count",20);
        try{RuntimeViewCount.readConfigured(internal,true,16);throw new AssertionError("ambiguous count");}
        catch(IllegalArgumentException expected){checks++;}
        check(parsed(null,true)==20);check(parsed(null,false)==20);check(parsed(new Bundle(),true)==20);
        Bundle bundle=new Bundle();
        for(int value:new int[]{16,20}){bundle.putInt("test_view_count",value);check(parsed(bundle,true)==value);rejected(bundle,false);}
        for(int value:new int[]{Integer.MIN_VALUE,0,1,4,15,17,19,21,24,32,Integer.MAX_VALUE}){bundle.putInt("test_view_count",value);rejected(bundle,true);}
        bundle.putString("test_view_count","16");rejected(bundle,true);
        bundle.putBoolean("test_view_count",true);rejected(bundle,true);
        bundle.putLong("test_view_count",16L);rejected(bundle,true);
        bundle.putFloat("test_view_count",16f);rejected(bundle,true);
        bundle.putString("test_view_count",null);rejected(bundle,true);rejected(bundle,false);
        Bundle dimensions=new Bundle();dimensions.putInt("test_view_count",16);dimensions.putString("test_view_preset","400x640");
        Object options=read.invoke(null,dimensions,true);
        Field preset=type.getDeclaredField("viewPreset");preset.setAccessible(true);
        check(preset.get(options).equals("400x640")&&count.getInt(options)==16);rejected(dimensions,false);
        // Proportional candidates are debug session overrides, never saved product profiles.
        for(String candidate:new String[]{"240x384","200x320"}){
            Bundle trial=new Bundle();trial.putInt("test_view_count",16);trial.putString("test_view_preset",candidate);
            Object selected=read.invoke(null,trial,true);
            check(preset.get(selected).equals(candidate)&&count.getInt(selected)==16);
            rejected(trial,false);
            check(!java.util.Arrays.asList(MirrorSettings.VIEW_PRESETS).contains(candidate));
            MirrorSettings saved=MirrorSettings.decode(java.util.Map.of("view_preset",candidate));
            check(saved.viewWidth==400&&saved.viewHeight==640&&!saved.warning.isEmpty());
            try{MirrorSettings.decode(java.util.Map.of()).withProfile(17,candidate);throw new AssertionError("Debug tile saved as product profile");}
            catch(IllegalArgumentException expected){checks++;}
        }
        for(String bad:new String[]{"240x385","200x321","0x0","240X384","240x384 ","8000x8000"}){
            Bundle trial=new Bundle();trial.putString("test_view_preset",bad);rejected(trial,true);
        }
        for(Object bad:new Object[]{null,240,240L,240f,true}){
            Bundle trial=new Bundle();
            if(bad instanceof Integer)trial.putInt("test_view_preset",(Integer)bad);
            else if(bad instanceof Long)trial.putLong("test_view_preset",(Long)bad);
            else if(bad instanceof Float)trial.putFloat("test_view_preset",(Float)bad);
            else if(bad instanceof Boolean)trial.putBoolean("test_view_preset",(Boolean)bad);
            else trial.putString("test_view_preset",null);
            rejected(trial,true);rejected(trial,false);
        }
        InterlaceRenderer configured=new InterlaceRenderer(16,.3f,true);configured.setViewSize(400,640);
        for(String field:new String[]{"requestedViewWidth","requestedViewHeight"}){
            Field size=InterlaceRenderer.class.getDeclaredField(field);size.setAccessible(true);
            check(size.getInt(configured)==(field.equals("requestedViewWidth")?400:640));
        }
        try{AvatarMultiviewCheck.runSixteen(null,400,641,()->false);throw new AssertionError("invalid strict diagnostic dimensions");}
        catch(IllegalArgumentException expected){checks++;}
        for(int value:new int[]{16,20}){
            InterlaceRenderer renderer=new InterlaceRenderer(value,.3f,true);renderer.setRuntimeMode(true);
            check(renderer.runtimeStatus().getInt("view_count")==value);
            check(renderer.runtimeStatus().getString("camera_vp_requested").equals("per_frame"));
            check(renderer.runtimeStatus().getString("multiview_fbo_requested").equals("legacy"));
        }
        // Real diagnostic metadata factory is used before any GL work, with preset zero tolerance.
        JSONObject profile=AvatarMultiviewCheck.sixteenProfile();
        check(profile.getInt("views")==16);check(profile.getInt("expected_layer_comparisons")==69*16);
        check(profile.getInt("reference_views_per_draw")==1);check(profile.getInt("candidate_views_per_draw")==4);
        check(profile.getInt("candidate_view_groups")==4);check(profile.getInt("max_rgb_error_allowed")==0);
        check(profile.getDouble("rmse_allowed")==0);check(profile.getInt("alpha_error_allowed")==0);
        check(profile.getString("verification_profile").equals("avatar_16views_strict"));
        var method=AvatarMultiviewCheck.class.getDeclaredMethod("viewMatrices",int.class,float.class);method.setAccessible(true);
        float[] sixteen=(float[])method.invoke(null,16,.625f),twenty=(float[])method.invoke(null,20,.625f);
        JSONObject gate=AvatarMultiviewCheck.sixteenMatrixGate(sixteen);
        check(gate.getBoolean("passed"));check(gate.getInt("compared_float_values")==256);
        check(gate.getInt("distinct_actual_matrices")==16);check(gate.getBoolean("actual_projection_order_monotonic"));
        float[] bad=sixteen.clone();System.arraycopy(bad,3*16,bad,4*16,16);
        check(!AvatarMultiviewCheck.sixteenMatrixGate(bad).getBoolean("passed"));
        bad=sixteen.clone();bad[7*16]=Math.nextUp(bad[7*16]);check(!AvatarMultiviewCheck.sixteenMatrixGate(bad).getBoolean("passed"));
        bad=sixteen.clone();bad[10]=Float.NaN;check(!AvatarMultiviewCheck.sixteenMatrixGate(bad).getBoolean("passed"));
        AvatarCameraProjectionCache cache=new AvatarCameraProjectionCache();cache.prepare(16,1200,1920);
        float[] group=new float[64];HashSet<String> views=new HashSet<>();
        for(int base=0;base<16;base+=4){cache.copyGroup(16,1200,1920,base,4,group);
            for(int i=0;i<64;i++)check(Float.floatToRawIntBits(group[i])==Float.floatToRawIntBits(sixteen[base*16+i]));}
        for(int view=0;view<16;view++)views.add(java.util.Arrays.toString(java.util.Arrays.copyOfRange(sixteen,view*16,view*16+16)));
        check(views.size()==16);
        for(int i=0;i<16;i++){check(Float.floatToRawIntBits(sixteen[i])==Float.floatToRawIntBits(twenty[i]));
            check(Float.floatToRawIntBits(sixteen[15*16+i])==Float.floatToRawIntBits(twenty[19*16+i]));}
        check(!java.util.Arrays.equals(java.util.Arrays.copyOfRange(sixteen,16,32),java.util.Arrays.copyOfRange(twenty,16,32)));
        // CPU optical mapping uses the actual count and reaches all layers without changing calibration.
        PanelCalibration panel=PanelCalibration.defaults();boolean[] selected=new boolean[16];
        for(int x=0;x<64;x++)for(int y=0;y<64;y++)for(int c=0;c<3;c++){
            int layer=panel.viewIndex(x,y,1920,c,16);check(layer>=0&&layer<16);selected[layer]=true;}
        for(boolean seen:selected)check(seen);
        check(panel.pitch()==10&&panel.tan()==.2777777f&&!panel.opticalAlignmentVerified());
        ByteBuffer a=ByteBuffer.allocateDirect(400),b=ByteBuffer.allocateDirect(400);
        for(int i=0;i<100;i++){a.putInt(i*4,0x503020ff);b.putInt(i*4,0x503020ff);}
        check(AvatarMultiviewCheck.strictPixelsMatch(AvatarPixelComparison.compare(a,b,10,10)));
        b.put(0,(byte)0x51);var difference=AvatarPixelComparison.compare(a,b,10,10);
        check(difference.passed());check(!AvatarMultiviewCheck.strictPixelsMatch(difference));
        b.put(0,a.get(0));b.put(3,(byte)254);check(!AvatarMultiviewCheck.strictPixelsMatch(AvatarPixelComparison.compare(a,b,10,10)));
        Field unsafeField=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
        var unsafe=(sun.misc.Unsafe)unsafeField.get(null);var preview=(AvatarPreviewActivity)unsafe.allocateInstance(AvatarPreviewActivity.class);
        Method filename=AvatarPreviewActivity.class.getDeclaredMethod("verificationFile");filename.setAccessible(true);
        check(filename.invoke(preview).equals("avatar-multiview-check.json"));
        Field sixteenFlag=AvatarPreviewActivity.class.getDeclaredField("verifyMultiview16");sixteenFlag.setAccessible(true);sixteenFlag.setBoolean(preview,true);
        check(filename.invoke(preview).equals("avatar-multiview16-check.json"));
        // Both real Intent factories propagate only debug counts; host Intent fixture is a value container.
        for(int value:new int[]{16,20}) {
            android.content.Intent calibration=CalibrationActivity.intent(null,value,true);
            check(RuntimeViewCount.read(calibration.getExtras(),true)==value);
            android.content.Intent panelIntent=PanelPreviewActivity.intent(null,panel,
                    RuntimeViewCount.read(calibration.getExtras(),true),true);
            check(RuntimeViewCount.read(panelIntent.getExtras(),true)==value);
            check(panelIntent.getExtras().get("pitch").equals(panel.pitch()));
            check(panelIntent.getExtras().get("tan").equals(panel.tan()));
            check(panelIntent.getExtras().get("phase").equals(panel.phaseCycles()));
            check(PanelPreviewActivity.previewLabel(value).contains("01–"+value));
            check(CalibrationActivity.previewLabel(value).contains(value+"视点"));
            var panelPreview=(PanelPreviewActivity)unsafe.allocateInstance(PanelPreviewActivity.class);
            Field panelCount=PanelPreviewActivity.class.getDeclaredField("viewCount");panelCount.setAccessible(true);panelCount.setInt(panelPreview,value);
            Method makeRenderer=PanelPreviewActivity.class.getDeclaredMethod("createRenderer",PanelCalibration.class);makeRenderer.setAccessible(true);
            InterlaceRenderer panelRenderer=(InterlaceRenderer)makeRenderer.invoke(panelPreview,panel);
            check(panelRenderer.runtimeStatus().getInt("view_count")==value);
            Method panelFile=PanelPreviewActivity.class.getDeclaredMethod("verificationFile");panelFile.setAccessible(true);
            check(panelFile.invoke(panelPreview).equals(value==16?"panel-pixel16-check.json":"panel-pixel-check.json"));
        }
        check(CalibrationActivity.intent(null,20,false).getExtras().get("runtime_view_count").equals(20));
        android.content.Intent releasePanel=PanelPreviewActivity.intent(null,panel,20,false);
        check(releasePanel.getExtras().get("test_view_count")==null);
        check(RuntimeViewCount.read(releasePanel.getExtras(),false)==20);
        check(CalibrationActivity.intent(null,16,false).getExtras().get("runtime_view_count").equals(16));
        android.content.Intent configuredPanel=PanelPreviewActivity.intent(null,panel,16,false);
        check(configuredPanel.getExtras().get("test_view_count")==null);
        check(RuntimeViewCount.read(configuredPanel.getExtras(),false,16)==16);
        try{CalibrationActivity.intent(null,17,true);throw new AssertionError("invalid outgoing17");}
        catch(IllegalArgumentException expected){checks++;}
        // Each of the diagnostic's six optical configurations selects every view; codes/cards distinguish them.
        PanelCalibration[] cases={panel,
                new PanelCalibration(10,.2777777f,PanelCalibration.PitchUnits.SUBPIXELS,.137f,PanelCalibration.SubpixelOrder.RGB,false,PanelCalibration.YOrigin.BOTTOM),
                new PanelCalibration(10,.2777777f,PanelCalibration.PitchUnits.SUBPIXELS,0,PanelCalibration.SubpixelOrder.BGR,false,PanelCalibration.YOrigin.BOTTOM),
                new PanelCalibration(10,.2777777f,PanelCalibration.PitchUnits.SUBPIXELS,0,PanelCalibration.SubpixelOrder.RGB,true,PanelCalibration.YOrigin.BOTTOM),
                new PanelCalibration(10,.2777777f,PanelCalibration.PitchUnits.SUBPIXELS,0,PanelCalibration.SubpixelOrder.RGB,false,PanelCalibration.YOrigin.TOP),
                new PanelCalibration(3.5f,-.125f,PanelCalibration.PitchUnits.PIXELS,.375f,PanelCalibration.SubpixelOrder.BGR,true,PanelCalibration.YOrigin.TOP)};
        for(int n:new int[]{16,20}) {
            for(PanelCalibration sample:cases) {
                boolean[] reached=new boolean[n];
                for(int y=0;y<64;y++)for(int x=0;x<64;x++)for(int c=0;c<3;c++)reached[sample.viewIndex(x,y,1920,c,n)]=true;
                for(boolean seen:reached)check(seen);
            }
            HashSet<String> cards=new HashSet<>();
            for(int v=0;v<n;v++)cards.add(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(PanelTestImages.card(v,32,64))));
            check(cards.size()==n);
            for(int c=0;c<3;c++){HashSet<Integer> codes=new HashSet<>();for(int v=0;v<n;v++)codes.add(PanelTestImages.code(v,c));check(codes.size()==n);}
        }
        System.out.println("RuntimeViewCountTest: "+checks+" checks passed; host Matrix is a fixture, device 16-layer GL gate remains required");
    }
}
