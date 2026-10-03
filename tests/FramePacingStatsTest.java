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
            s.record(31,1,t,t+1_000_000,t+10_000_000,500_000,1,1_000_000,true,0,0,false,false);
        }
        FramePacingStats.Snapshot snap=s.snapshot();
        check(snap.frames==1_000_000 && snap.bucket(31).gap.count==999_999,"million frames accounted");
        check(snap.bucketCapacity()==61 && snap.retainedFrameSamples==0,"fixed buckets, no retained samples");
        check(empty.frames==0 && empty.bucket(31).frames==0,"snapshot is independent");
        near(snap.bucket(31).gap.meanMs,30,"constant synthetic gap");
        near(snap.bucket(31).work.meanMs,9,"constant synthetic work");
    }
    private static void concurrentSnapshots() throws Exception {
        FramePacingStats s=new FramePacingStats(); AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread writer=new Thread(()->{try {for(int i=0;i<100_000;i++) {
            long t=i*100L;s.record(35,1,t,t+10,t+50,5,1,10,true,0,0,false,false);
        }}catch(Throwable e){failure.set(e);}});
        writer.start();
        do {
            FramePacingStats.Snapshot snap=s.snapshot(); FramePacingStats.BucketSnapshot b=snap.bucket(35);
            check(snap.frames==b.frames && b.frames==b.pacerWait.count && b.frames==b.fenceCalls,"coherent concurrent snapshot");
            if(b.frames>0) check(b.gap.count==b.frames-1,"gap count shares frame publication");
            Thread.yield();
        } while(writer.isAlive());
        writer.join();if(failure.get()!=null)throw new AssertionError(failure.get());
        check(s.snapshot().frames==100_000,"concurrent writer complete");
    }
    private static void bad(Runnable action) {
        try {action.run();throw new AssertionError("expected invalid sample rejection");}
        catch(IllegalArgumentException expected){checks++;}
    }
    private static void near(double actual,double expected,String label) {check(Math.abs(actual-expected)<1e-9,label+": "+actual);}
    private static void check(boolean pass,String label) {if(!pass)throw new AssertionError(label);checks++;}
}
