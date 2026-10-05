package com.mirror.bench;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Exercise the production publication/lifecycle boundary, without constructing a GL context. */
public final class RendererPacingTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        InterlaceRenderer renderer=new InterlaceRenderer(20,.25f,true);
        renderer.setRuntimeMode(true);renderer.beginMeasurement();renderer.setTargetFps(31);
        RuntimeGlLifecycle gl=(RuntimeGlLifecycle)field(renderer,"runtimeGlLifecycle");gl.contextCreated();
        long initial=epoch(renderer);
        renderer.setTargetFps(31);
        check(epoch(renderer)==initial,"same-target UI tick is not a boundary");
        frame(renderer,31,initial,100);
        frame(renderer,31,initial,200);
        check(snapshot(renderer).bucket(31).gap.count==1,"stable production publications join");

        renderer.setTargetFps(10);renderer.setTargetFps(31);
        check(epoch(renderer)!=initial,"target ABA retains boundary evidence");
        frame(renderer,31,initial,300);
        check(snapshot(renderer).transitionFrames==1,"captured old epoch cannot use current target label");
        check(snapshot(renderer).bucket(31).frames==2,"transition is not silently assigned to 31");
        frame(renderer,31,epoch(renderer),400);
        check(snapshot(renderer).bucket(31).gap.count==1,"after-transition gap is excluded");
        check(!renderer.hasRuntimeFrame(),"pacing statistics cannot imply a successful GL submission");
        gl.successfulFrame(gl.frameEpoch());
        check(renderer.hasRuntimeFrame(),"transition still represents a successful submission");

        renderer.resumeRuntimeAvatar();
        check(!renderer.hasRuntimeFrame(),"resume still waits for own first submission");
        frame(renderer,31,epoch(renderer),10_000);
        check(snapshot(renderer).bucket(31).gap.count==1,"HOME residence excluded by actual resume hook");
        check(!renderer.hasRuntimeFrame(),"post-resume timing alone stays gated");gl.successfulFrame(gl.frameEpoch());
        frame(renderer,31,epoch(renderer),10_100);
        check(snapshot(renderer).bucket(31).gap.count==2,"post-resume stable cadence counted");
        check((Long)field(renderer,"runtimeFrames")==snapshot(renderer).frames,"frame publication is coherent");
        FramePacingStats.BucketSnapshot stages=snapshot(renderer).bucket(31);
        check(stages.stageFrames==stages.frames,"production publisher includes complete stages");
        check(Math.abs(stages.preViews.totalMs+stages.avatarPrepare.totalMs+stages.viewSubmission.totalMs+
                stages.interlaceSubmission.totalMs+stages.submitTail.totalMs-stages.stageWork.totalMs)<1e-12,
                "production stages conserve work after floating millisecond conversion");
        renderer.setTargetFps(35);frame(renderer,35,epoch(renderer),10_200);
        check(snapshot(renderer).bucket(35).frames==1 && snapshot(renderer).bucket(35).gap.count==0,
                "new target has own first boundary sample");

        FramePacingStats.Snapshot prior=snapshot(renderer);
        long measurement=(Long)field(renderer,"pacingMeasurementGeneration");
        renderer.beginMeasurement();
        check(snapshot(renderer).frames==0 && (Long)field(renderer,"runtimeFrames")==0,"measurement resets both counters");
        check((Long)field(renderer,"pacingMeasurementGeneration")>measurement,"reset scope identifier advances");
        check(prior.frames==7,"old snapshot remains immutable through production reset");

        InterlaceRenderer benchmark=new InterlaceRenderer(20,.25f,true);benchmark.beginMeasurement();
        Method old=InterlaceRenderer.class.getDeclaredMethod("recordFrame",long.class,long.class,boolean.class,long.class);
        old.setAccessible(true);old.invoke(benchmark,1L,2L,false,1L);
        check(snapshot(benchmark).frames==0,"legacy benchmark bypasses pacing aggregation");
        detailedProductionPublication();
        System.out.println("PASS: "+checks+" renderer pacing/lifecycle publication assertions");
    }
    private static void detailedProductionPublication() throws Exception {
        InterlaceRenderer renderer=new InterlaceRenderer(20,.25f,true);renderer.setRuntimeMode(true);renderer.setTargetFps(31);
        ViewSubmissionTiming timing=(ViewSubmissionTiming)field(renderer,"runtimeViewTiming");
        timing.begin(0,1);timing.setupComplete(1);timing.attachmentsComplete(2);timing.cameraComplete(4);
        timing.sceneComplete(7);timing.groupComplete(8);timing.finish(10);
        long captured=epoch(renderer);frame(renderer,31,captured,100);
        FramePacingStats.BucketSnapshot b=snapshot(renderer).bucket(31);
        check(b.viewDetailFrames==1&&b.viewGroups==1,"production publisher consumes complete GL-owner detail");
        check(b.viewDetail(3).totalMs==3/1e6&&b.viewDetailWork.totalMs==10/1e6,"actual scene-draw/detail values published");
        renderer.setTargetFps(35);frame(renderer,31,captured,200);
        check(snapshot(renderer).transitionFrames==1&&snapshot(renderer).bucket(31).viewDetailFrames==1,
                "target switch cannot publish detailed stages to old bucket");
        timing.reset();frame(renderer,35,epoch(renderer),300);
        check(snapshot(renderer).bucket(35).frames==1&&snapshot(renderer).bucket(35).viewDetailFrames==0,
                "non-avatar/absent detail cannot reuse previous frame scratch");
    }
    private static void frame(InterlaceRenderer renderer,int fps,long epoch,long entry) throws Exception {
        Method method=InterlaceRenderer.class.getDeclaredMethod("recordPacedRuntimeFrame",int.class,long.class,
                long.class,long.class,long.class,long.class,int.class,long.class,boolean.class,long.class,long.class,boolean.class,
                long.class,long.class,long.class,long.class,long.class);
        method.setAccessible(true);
        method.invoke(renderer,fps,epoch,entry,entry+10,entry+50,5L,1,10L,true,0L,0L,false,10L,10L,10L,5L,5L);
    }
    private static FramePacingStats.Snapshot snapshot(InterlaceRenderer renderer) throws Exception {
        return ((FramePacingStats)field(renderer,"framePacing")).snapshot();
    }
    private static long epoch(InterlaceRenderer renderer) throws Exception{return (Long)field(renderer,"pacingEpoch");}
    private static Object field(InterlaceRenderer renderer,String name) throws Exception {
        Field field=InterlaceRenderer.class.getDeclaredField(name);field.setAccessible(true);return field.get(renderer);
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
