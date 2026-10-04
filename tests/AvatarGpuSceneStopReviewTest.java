package com.mirror.bench;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Actual scene stop/status bridge; Unsafe skips GL construction only, pose worker is real. */
public final class AvatarGpuSceneStopReviewTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    private static void set(Object owner,String name,Object value)throws Exception{
        Field field=AvatarGpuScene.class.getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
    }
    private static void await(java.util.function.BooleanSupplier condition)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean()){if(System.nanoTime()>deadline)throw new AssertionError("worker timeout");Thread.sleep(1);}
    }
    public static void main(String[] args)throws Exception{
        Field singleton=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        AvatarGpuScene scene=(AvatarGpuScene)((sun.misc.Unsafe)singleton.get(null)).allocateInstance(AvatarGpuScene.class);
        AvatarAsset asset=AvatarRigTest.fixture();String manifest=AvatarRigTest.manifest().toString();
        AvatarPoseWorker worker=new AvatarPoseWorker(asset,manifest);AvatarPoseWorker.Frame frame=null;
        try{
            set(scene,"asset",asset);set(scene,"rig",new AvatarRig(asset,manifest));set(scene,"sha256","0".repeat(64));
            set(scene,"metricsLock",new Object());set(scene,"drawMode",AvatarGpuScene.DrawMode.INDIVIDUAL);set(scene,"poseWorker",worker);
            JSONObject active=scene.status().getJSONObject("pose_worker").getJSONObject("cpu_stop");
            check(!active.getBoolean("close_requested")&&!active.getBoolean("close_succeeded"));
            worker.submit(new float[52],new float[3]);await(()->worker.status().readyCount==1);
            frame=worker.acquireLatest();check(frame!=null);
            scene.stopCpu();await(worker::isTerminated);
            JSONObject pose=scene.status().getJSONObject("pose_worker"),held=pose.getJSONObject("cpu_stop");
            check(pose.getBoolean("run_exit_marked")&&pose.getBoolean("terminated")&&pose.getString("thread_state").equals("TERMINATED"));
            check(held.getBoolean("thread_terminated")&&held.getInt("leased_count")==1&&!held.getBoolean("close_succeeded"));
            check(!held.getBoolean("ownership_stopped"));
            check(!held.getBoolean("hardware_qualified")&&held.getString("scope").equals("CPU pose thread and frame leases only"));
            check(!held.getBoolean("failed")&&held.getString("failure_type").isEmpty());
            worker.release(frame);frame=null;
            JSONObject released=scene.status().getJSONObject("pose_worker").getJSONObject("cpu_stop");
            check(released.getBoolean("close_succeeded")&&released.getInt("leased_count")==0);
            check(released.getBoolean("ownership_stopped"));
            check(held.getInt("leased_count")==1&&!held.getBoolean("close_succeeded"));
            check(active.getString("worker_id").equals(released.getString("worker_id")));
            check(released.getLong("observed_monotonic_ns")>=released.getLong("requested_monotonic_ns"));
            check(released.getString("clock").equals("System.nanoTime"));
            scene.stopCpu();JSONObject repeat=scene.status().getJSONObject("pose_worker").getJSONObject("cpu_stop");
            check(repeat.getLong("requested_monotonic_ns")==released.getLong("requested_monotonic_ns"));
            check(!repeat.getBoolean("hardware_qualified"));
        }finally{worker.close();if(frame!=null)worker.release(frame);await(worker::isTerminated);}
        System.out.println("AvatarGpuScene stop bridge checks: "+checks+"; actual stop/status and CPU Thread, no GL/Android lifecycle qualification");
    }
}
