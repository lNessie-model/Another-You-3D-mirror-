package com.mirror.bench;

import android.os.Bundle;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Host gate for the replay-only expression candidate entry point. */
public final class CalibratedExpressionConfigTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static Object invokeRead(Method method,Bundle extras,boolean debug)throws Exception{
        try{return method.invoke(null,extras,debug);}
        catch(InvocationTargetException error){throw error;}
    }
    private static void rejects(Method method,Bundle extras,boolean debug,String message)throws Exception{
        try{invokeRead(method,extras,debug);throw new AssertionError(message);}
        catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalArgumentException,message+" type");}
    }
    public static void main(String[] args)throws Exception{
        Class<?> type=Class.forName("com.mirror.bench.MirrorActivity$InputOptions");
        Method read=type.getDeclaredMethod("read",Bundle.class,boolean.class);read.setAccessible(true);
        Field enabled=type.getDeclaredField("calibratedExpressionCandidate");enabled.setAccessible(true);
        Field config=type.getDeclaredField("calibratedExpressionConfig");config.setAccessible(true);
        check(!enabled.getBoolean(invokeRead(read,null,true)),"ordinary defaults keep candidate disabled");

        Bundle replay=new Bundle(); replay.putString("runtime_input","replay"); replay.putBoolean("test_calibrated_expression",true);
        Object options=invokeRead(read,replay,true); check(enabled.getBoolean(options),"debug replay enables candidate");
        Object candidateConfig=config.get(options); check(candidateConfig!=null,"candidate receives audit config");
        Field gamma=candidateConfig.getClass().getDeclaredField("eyeWideGamma");gamma.setAccessible(true);
        check(Math.abs(gamma.getFloat(candidateConfig)-3.5f)<1e-6f,"audit eye-wide gamma is 3.5");

        Bundle camera=new Bundle(); camera.putBoolean("test_calibrated_expression",true);
        rejects(read,camera,true,"live camera candidate rejected");
        Bundle release=new Bundle(); release.putString("runtime_input","replay"); release.putBoolean("test_calibrated_expression",true);
        rejects(read,release,false,"release candidate rejected");
        Bundle cameraReplay=new Bundle();cameraReplay.putString("runtime_input","camera_replay");cameraReplay.putBoolean("test_calibrated_expression",true);
        check(enabled.getBoolean(invokeRead(read,cameraReplay,true)),"camera replay enables candidate");

        Bundle tuned=new Bundle();tuned.putString("runtime_input","replay");tuned.putBoolean("test_calibrated_expression",true);tuned.putFloat("test_expression_eye_wide_gamma",2.25f);
        Object tunedOptions=invokeRead(read,tuned,true);Object tunedConfig=config.get(tunedOptions);gamma=tunedConfig.getClass().getDeclaredField("eyeWideGamma");gamma.setAccessible(true);
        check(Math.abs(gamma.getFloat(tunedConfig)-2.25f)<1e-6f,"debug eye-wide parameter is applied");
        Bundle parameterWithoutSwitch=new Bundle();parameterWithoutSwitch.putString("runtime_input","replay");parameterWithoutSwitch.putFloat("test_expression_eye_wide_gamma",2.25f);
        rejects(read,parameterWithoutSwitch,true,"parameter without candidate switch rejected");
        Bundle invalid=new Bundle();invalid.putString("runtime_input","replay");invalid.putBoolean("test_calibrated_expression",true);invalid.putFloat("test_expression_eye_wide_gamma",4.5f);
        rejects(read,invalid,true,"out-of-range gamma rejected");
        InterlaceRenderer renderer=new InterlaceRenderer(16,.3f,true);renderer.setRuntimeMode(true);
        renderer.setCalibratedExpressionCandidate(true,FacePlaybackCandidate.Config.auditDefaults());
        check(renderer.runtimeStatus().getBoolean("calibrated_expression_candidate"),"renderer publishes candidate mode");
        Field initialized=InterlaceRenderer.class.getDeclaredField("surfaceInitialized");initialized.setAccessible(true);initialized.setBoolean(renderer,true);
        try{renderer.setCalibratedExpressionCandidate(false,null);throw new AssertionError("candidate changed after GL init");}
        catch(IllegalStateException expected){checks++;}
        System.out.println("CalibratedExpressionConfigTest: "+checks+" replay-only candidate checks passed");
    }
}
