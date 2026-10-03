package com.mirror.bench;

import java.util.concurrent.atomic.AtomicReference;

/** Synthetic timestamps exercise accounting; this is not a GPU or FPS benchmark. */
public final class FramePacingStatsTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        measuredDurationsAndBoundaries();
        targetResumeAndTransitionIsolation();
        badInputIsTransactional();
        boundedStorageAndImmutableSnapshots();
        concurrentSnapshots();
        nonoverlappingStagesAndMissingSamples();
        System.out.println("PASS: " + checks + " frame-pacing assertions (1,000,000 bounded records)");
    }
    private static long ms(long value) { return value * 1_000_000L; }
    private static void frame(FramePacingStats s,int fps,long epoch,long entry,long start,long end,
            long park,int parks,long fence,boolean waited,long late,long startLate,boolean rebase,boolean transition) {
        s.record(fps,epoch,ms(entry),ms(start),ms(end),ms(park),parks,ms(fence),waited,
                ms(late),ms(startLate),rebase,transition);
    }
    private static void measuredDurationsAndBoundaries() {
        FramePacingStats s = new FramePacingStats();
        frame(s,31,1,100,110,140,9,2,5,true,99,99,true,false);
        frame(s,31,1,150,153,178,2,1,0,false,4,7,true,false);
        FramePacingStats.BucketSnapshot b = s.snapshot().bucket(31);
        check(b.frames==2 && b.parkCalls==3 && b.fenceCalls==1,"actual API call counts");
        near(b.pacerWait.meanMs,5.5,"sum measured parks, not requested deadline sleep");
        near(b.pacerWait.maxMs,9,"maximum measured parks per callback");
        near(b.work.meanMs,27.5,"work excludes pacing");
        near(b.fenceWait.meanMs,2.5,"fence mean denominator includes no-wait frames");
        near(b.fenceWait.totalMs,5,"measured fence time only");
        check(b.gap.count==1 && b.interval.count==1 && b.boundaryFrames==1,"first gap excluded");
        near(b.gap.meanMs,10,"after-to-next-entry gap");
        near(b.interval.meanMs,50,"entry-to-entry interval");
        near(b.entryLateness.meanMs,4,"initial/recovery deadline debt excluded");
        near(b.startLateness.meanMs,7,"start oversleep measured separately");
        check(b.deadlineRebases==1,"initial/recovery rebase excluded");
        check(s.snapshot().frames==2 && s.snapshot().transitionFrames==0,"lifetime counts");
    }
    private static void targetResumeAndTransitionIsolation() {
        FramePacingStats s = new FramePacingStats();
        frame(s,31,1,0,0,10,0,0,0,false,0,0,false,false);
        frame(s,10,2,100,100,110,0,0,0,true,80,80,true,false);
        frame(s,35,3,200,200,210,0,0,0,false,90,90,true,false);
        frame(s,35,4,9000,9000,9010,0,0,0,false,8790,8790,true,false);
        frame(s,35,4,9020,9020,9030,0,0,0,false,0,0,false,true);
        frame(s,35,6,9040,9040,9050,0,0,0,false,0,0,false,false);
        frame(s,35,6,9060,9060,9070,0,0,0,false,0,0,false,false);
        FramePacingStats.Snapshot snap=s.snapshot();
        check(snap.frames==7 && snap.transitionFrames==1,"transition separately counted even target ABA");
        check(snap.bucket(31).gap.count==0 && snap.bucket(10).gap.count==0,"target changes exclude gaps");
        check(snap.bucket(10).fenceCalls==1,"zero-duration API wait still counted");
        FramePacingStats.BucketSnapshot b=snap.bucket(35);
        check(b.frames==4 && b.boundaryFrames==3 && b.gap.count==1,"resume and post-transition gaps excluded");
        near(b.gap.meanMs,10,"background residence not counted");
        check(b.deadlineRebases==0,"boundary deadline rebase not counted as steady overrun");
        s.reset();
        check(s.snapshot().frames==0 && s.snapshot().bucket(35).frames==0,"context reset clears all buckets");
        frame(s,35,6,9100,9100,9110,0,0,0,false,0,0,false,false);
        check(s.snapshot().bucket(35).gap.count==0,"reset excludes first gap");
        check(snap.frames==7 && snap.bucket(35).frames==4,"reset cannot mutate old snapshot");
    }
    private static void badInputIsTransactional() {
        FramePacingStats s=new FramePacingStats();
        frame(s,31,1,10,20,30,1,1,1,true,0,0,false,false);
        bad(()->frame(s,61,1,40,50,60,0,0,0,false,0,0,false,false));
        bad(()->frame(s,-1,1,40,50,60,0,0,0,false,0,0,false,false));
        bad(()->frame(s,31,1,40,39,60,0,0,0,false,0,0,false,false));
        bad(()->frame(s,31,1,40,50,49,0,0,0,false,0,0,false,false));
        bad(()->frame(s,31,1,40,50,60,11,1,0,false,0,0,false,false));
        bad(()->frame(s,31,1,40,50,60,1,0,0,false,0,0,false,false));
        bad(()->frame(s,31,1,40,50,60,0,-1,0,false,0,0,false,false));
        bad(()->frame(s,31,1,40,50,60,0,0,11,true,0,0,false,false));
        bad(()->frame(s,31,1,40,50,60,0,0,1,false,0,0,false,false));
        bad(()->frame(s,31,1,40,50,60,0,0,0,false,-1,0,false,false));
        bad(()->frame(s,31,1,29,40,50,0,0,0,false,0,0,false,false));
        check(s.snapshot().frames==1,"invalid samples never partly increment counters");
        frame(s,0,2,40,50,60,0,0,0,false,0,0,false,false);
        check(s.snapshot().bucket(0).frames==1,"unlimited target has bounded bucket too");
    }
    private static void boundedStorageAndImmutableSnapshots() {
        FramePacingStats s=new FramePacingStats();
        FramePacingStats.Snapshot empty=s.snapshot();
        for(int i=0;i<1_000_000;i++) {
            long t=i*40_000_000L;
            s.recordStages(31,1,t,t+1_000_000,t+10_000_000,500_000,1,1_000_000,true,0,0,false,false,
                    1_000_000,2_000_000,3_000_000,2_000_000,1_000_000);
        }
        FramePacingStats.Snapshot snap=s.snapshot();
        check(snap.frames==1_000_000 && snap.bucket(31).gap.count==999_999,"million frames accounted");
        check(snap.bucketCapacity()==61 && snap.retainedFrameSamples==0,"fixed buckets, no retained samples");
        check(empty.frames==0 && empty.bucket(31).frames==0,"snapshot is independent");
        near(snap.bucket(31).gap.meanMs,30,"constant synthetic gap");
        near(snap.bucket(31).work.meanMs,9,"constant synthetic work");
        check(snap.bucket(31).stageFrames==1_000_000,"million stage-complete records bounded too");
    }
    private static void concurrentSnapshots() throws Exception {
        FramePacingStats s=new FramePacingStats(); AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread writer=new Thread(()->{try {for(int i=0;i<100_000;i++) {
            long t=i*100L;s.recordStages(35,1,t,t+10,t+50,5,1,10,true,0,0,false,false,10,10,10,5,5);
        }}catch(Throwable e){failure.set(e);}});
        writer.start();
        do {
            FramePacingStats.Snapshot snap=s.snapshot(); FramePacingStats.BucketSnapshot b=snap.bucket(35);
            check(snap.frames==b.frames && b.frames==b.pacerWait.count && b.frames==b.fenceCalls,"coherent concurrent snapshot");
            check(b.frames==b.stageFrames && b.frames==b.submitTail.count,"stage publication shares the same atomic snapshot");
            if(b.frames>0) check(b.gap.count==b.frames-1,"gap count shares frame publication");
            Thread.yield();
        } while(writer.isAlive());
        writer.join();if(failure.get()!=null)throw new AssertionError(failure.get());
        check(s.snapshot().frames==100_000,"concurrent writer complete");
    }
    private static void nonoverlappingStagesAndMissingSamples() {
        FramePacingStats s=new FramePacingStats();
        // One old caller has no stage data; a missing measurement must not become a zero-duration sample.
        frame(s,31,1,0,10,50,1,1,2,true,0,0,false,false);
        staged(s,31,1,100,false,4,8,17,6,5);
        staged(s,31,1,200,false,2,10,20,5,3);
        FramePacingStats.BucketSnapshot b=s.snapshot().bucket(31);
        check(b.frames==3 && b.stageFrames==2,"stage denominator independent of legacy record");
        near(b.preViews.meanMs,3,"pre-view mean");near(b.avatarPrepare.meanMs,9,"prepare mean");
        near(b.viewSubmission.meanMs,18.5,"multiview submission mean");
        near(b.interlaceSubmission.meanMs,5.5,"interlace submission mean");near(b.submitTail.meanMs,4,"tail mean");
        near(b.preViews.totalMs+b.avatarPrepare.totalMs+b.viewSubmission.totalMs+
                b.interlaceSubmission.totalMs+b.submitTail.totalMs,80,"five nonoverlap stages conserve work");
        check(b.stageWork.count==b.stageFrames,"stage work has exactly same sample denominator");
        near(b.stageWork.totalMs,80,"work for stage-complete subset only");
        staged(s,31,1,300,true,4,8,17,6,5);
        check(s.snapshot().transitionFrames==1 && s.snapshot().bucket(31).stageFrames==2,"epoch transition excludes all stages");
        staged(s,35,2,400,false,0,0,0,0,40);
        check(s.snapshot().bucket(35).stageFrames==1,"new target boundary retains work stages");
        FramePacingStats.Snapshot stable=s.snapshot();
        bad(()->staged(s,35,2,500,false,-1,0,0,0,41));
        bad(()->staged(s,35,2,500,false,1,0,0,0,40));
        bad(()->staged(s,35,2,500,false,1,0,0,0,38));
        bad(()->s.recordStages(35,2,600,610,650,0,0,0,false,0,0,false,false,
                Long.MAX_VALUE,Long.MAX_VALUE,2,0,0));
        bad(()->s.recordStages(35,2,600,610,650,0,0,3,true,0,0,false,false,2,8,20,5,5));
        check(s.snapshot().frames==stable.frames && s.snapshot().bucket(35).stageFrames==1,"bad stage durations are transactional");
        s.reset();check(s.snapshot().bucket(31).stageFrames==0 && s.snapshot().bucket(31).stageWork.count==0,"stage reset bounded");
        check(stable.bucket(31).stageFrames==2,"stage snapshot remains immutable");
    }
    private static void staged(FramePacingStats s,int fps,long epoch,long entry,boolean transition,
            long pre,long prepare,long views,long interlace,long tail) {
        s.recordStages(fps,epoch,ms(entry),ms(entry+10),ms(entry+50),ms(1),1,ms(Math.max(0,Math.min(2,pre))),true,0,0,false,transition,
                ms(pre),ms(prepare),ms(views),ms(interlace),ms(tail));
    }
    private static void bad(Runnable action) {
        try {action.run();throw new AssertionError("expected invalid sample rejection");}
        catch(IllegalArgumentException expected){checks++;}
    }
    private static void near(double actual,double expected,String label) {check(Math.abs(actual-expected)<1e-9,label+": "+actual);}
    private static void check(boolean pass,String label) {if(!pass)throw new AssertionError(label);checks++;}
}
