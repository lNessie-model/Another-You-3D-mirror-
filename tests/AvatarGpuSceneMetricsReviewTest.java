package com.mirror.bench;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import sun.misc.Unsafe;

/** Exercises actual scene metric publication concurrently, without constructing an EGL scene. */
public final class AvatarGpuSceneMetricsReviewTest {
    public static void main(String[] args)throws Exception {
        Field singleton=Unsafe.class.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        AvatarGpuScene scene=(AvatarGpuScene)((Unsafe)singleton.get(null)).allocateInstance(AvatarGpuScene.class);
        AvatarAsset asset=AvatarRigTest.fixture();set(scene,"asset",asset);
        set(scene,"rig",new AvatarRig(asset,AvatarRigTest.manifest().toString()));set(scene,"sha256","host-metrics-fixture");
        set(scene,"metricsLock",new Object());set(scene,"drawMode",AvatarGpuScene.DrawMode.INDIVIDUAL);
        Method record=AvatarGpuScene.class.getDeclaredMethod("recordPose",double.class,double.class,double.class,double.class,double.class,long.class,long.class,long.class);
        record.setAccessible(true);AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread writer=new Thread(()->{
            try{for(int i=1;i<=100_000;i++)record.invoke(scene,(double)i,(double)i*2,(double)i*3,(double)i*4,(double)i*5,(long)i,2L,100L);}
            catch(Throwable error){failure.set(error);}
        },"scene-metrics-review-writer");
        writer.start();int snapshots=0;
        while(writer.isAlive()) {verify(scene.status());snapshots++;}
        writer.join();if(failure.get()!=null)throw new AssertionError("Writer failed",failure.get());
        verify(scene.status());
        if(scene.status().getLong("morph_updates")!=100_000)throw new AssertionError("Writer did not complete");
        System.out.println("AvatarGpuSceneMetricsReviewTest: "+snapshots+" concurrent snapshots + 100000 updates coherent");
    }
    private static void verify(JSONObject s)throws Exception {
        long n=s.getLong("morph_updates");
        equal(s.getLong("applied_pose_input_id"),n,"input/update count");
        equal(s.getLong("changed_meshes"),2*n,"changed mesh count");equal(s.getLong("uploaded_bytes"),100*n,"uploaded byte count");
        equal(s.getDouble("last_rig_ms"),n,"last rig time");equal(s.getDouble("last_morph_ms"),2*n,"last morph time");
        equal(s.getDouble("last_deform_ms"),3*n,"last combined time");equal(s.getDouble("pose_copy_ms"),3*n,"last copy time");
        equal(s.getDouble("last_upload_ms"),4*n,"last upload time");equal(s.getDouble("applied_pose_age_ms"),5*n,"last age");
        equal(s.getDouble("applied_pose_age_max_ms"),5*n,"maximum age");
        double mean=n==0?0:(n+1)*.5;
        equal(s.getDouble("rig_mean_ms"),mean,"mean rig time");equal(s.getDouble("morph_mean_ms"),2*mean,"mean morph time");
        equal(s.getDouble("pose_copy_mean_ms"),3*mean,"mean copy time");equal(s.getDouble("upload_mean_ms"),4*mean,"mean upload time");
        equal(s.getDouble("applied_pose_age_mean_ms"),5*mean,"mean age");
    }
    private static void equal(double actual,double expected,String label){if(actual!=expected)throw new AssertionError(label+": "+actual+" != "+expected);}
    private static void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
}
