package com.mirror.bench;

import java.lang.reflect.Field;
import sun.misc.Unsafe;

/** Review reproducer: executes the real scene progress logic with every worker output leased.
 * No GL method runs because acquireLatest returns null. Android jar is used only for linkage.
 */
public final class AvatarGpuSceneProgressReviewTest {
    public static void main(String[] args)throws Exception {
        try(AvatarPoseWorker worker=new AvatarPoseWorker(AvatarRigTest.fixture(),AvatarRigTest.manifest().toString())) {
            AvatarPoseWorker.Frame[] held=new AvatarPoseWorker.Frame[3];
            try {
                for(int i=0;i<held.length;i++) {
                    worker.submit(new float[52],new float[3]);long deadline=System.nanoTime()+2_000_000_000L;
                    while((held[i]=worker.acquireLatest())==null){if(System.nanoTime()>deadline)throw new AssertionError("Fixture worker timed out");Thread.yield();}
                }
                // Allocate without an EGL constructor; only the independent CPU progress branch is exercised.
                Field singleton=Unsafe.class.getDeclaredField("theUnsafe");singleton.setAccessible(true);
                AvatarGpuScene scene=(AvatarGpuScene)((Unsafe)singleton.get(null)).allocateInstance(AvatarGpuScene.class);
                set(scene,"poseWorker",worker);long now=System.nanoTime();
                AvatarPoseProgressWatchdog progress=new AvatarPoseProgressWatchdog();progress.resume(now-10_000_000_000L);
                set(scene,"poseProgress",progress);
                boolean timedOut=false;
                try{scene.prepare(new float[52],new float[3]);}
                catch(IllegalStateException expected){timedOut=expected.getMessage().contains("no fresh progress");}
                if(!timedOut)throw new AssertionError("Ten seconds without a result was hidden by a delayed prepare; GL scheduling is not worker progress");
                scene.resumeCpuPose();scene.prepare(new float[52],new float[3]);
                if(progress.currentResultAgeMs(System.nanoTime())!=-1)throw new AssertionError("Explicit resume invented an applied result");
                System.out.println("AvatarGpuSceneProgressReviewTest: stale worker detected; explicit resume alone grants recovery grace");
            } finally {for(var frame:held)if(frame!=null)worker.release(frame);}
        }
    }
    private static void set(Object target,String name,Object value)throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
}
