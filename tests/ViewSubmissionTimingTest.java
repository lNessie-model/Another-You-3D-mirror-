package com.mirror.bench;

/** Exact integer timestamp partitions, no GL execution and no timing-performance assertion. */
public final class ViewSubmissionTimingTest {
    private static int checks;
    public static void main(String[] args) {
        groupPartitionAndReuse();badOrderAndTimestamps();publicationAndMissingSamples();boundedRepeatedFrames();
        System.out.println("ViewSubmissionTimingTest: "+checks+" assertions, including 100,000 detailed frame publications");
    }
    private static ViewSubmissionTiming fixture(int groups) {
        ViewSubmissionTiming t=new ViewSubmissionTiming();fill(t,groups);return t;
    }
    private static void fill(ViewSubmissionTiming t,int groups) {
        t.begin(100,groups);t.setupComplete(105);
        long now=105;
        for(int i=0;i<groups;i++) {
            t.attachmentsComplete(now+=2);t.cameraComplete(now+=3);t.sceneComplete(now+=7);t.groupComplete(now+=1);
        }
        t.finish(now+4);
    }
    private static void groupPartitionAndReuse() {
        ViewSubmissionTiming t=fixture(5);
        check(t.isStarted()&&t.isComplete()&&t.groups()==5,"actual five OVR groups complete");
        long[] expected={5,10,15,35,5,4};long sum=0;
        for(int i=0;i<6;i++){check(t.durationNs(i)==expected[i],"stage "+i);sum+=t.durationNs(i);}
        check(sum==t.totalNs()&&sum==74,"all stages exactly cover the enclosing interval");
        fill(t,4);check(t.totalNs()==61&&t.groups()==4,"reuse clears previous frame, supports 16-view groups");
        t.reset();check(!t.isStarted()&&!t.isComplete(),"no-avatar frame has no detailed measurement");
        bad(()->t.durationNs(0));bad(t::totalNs);
    }
    private static void badOrderAndTimestamps() {
        ViewSubmissionTiming t=new ViewSubmissionTiming();
        bad(()->t.begin(-1,5));bad(()->t.begin(0,0));bad(()->t.begin(0,33));
        bad(()->t.setupComplete(1));
        t.begin(10,1);bad(()->t.begin(11,1));bad(()->t.finish(12));
        bad(()->t.setupComplete(9));t.setupComplete(11);
        bad(()->t.cameraComplete(12));t.attachmentsComplete(12);
        bad(()->t.sceneComplete(13));t.cameraComplete(13);t.sceneComplete(14);
        bad(()->t.finish(15));t.groupComplete(15);
        bad(()->t.attachmentsComplete(16));t.finish(16);
        bad(()->t.finish(17));bad(()->t.groupComplete(17));
        check(t.totalNs()==6,"rejected operations leave valid state unchanged");
    }
    private static void record(FramePacingStats stats,ViewSubmissionTiming t,long entry,boolean transition) {
        stats.recordDetailedStages(31,1,entry,entry+10,entry+110,2,1,1,true,0,0,false,transition,
                2,10,74,8,6,t);
    }
    private static void publicationAndMissingSamples() {
        FramePacingStats stats=new FramePacingStats();ViewSubmissionTiming t=fixture(5);
        record(stats,t,0,false);
        FramePacingStats.BucketSnapshot old=stats.snapshot().bucket(31);
        check(old.viewDetailFrames==1&&old.viewGroups==5,"one callback records five groups, not five callbacks");
        double total=0;for(int i=0;i<6;i++) {
            check(old.viewDetail(i).count==1,"one sample for aggregated group phase "+i);
            total+=old.viewDetail(i).totalMs;
        }
        near(total,old.viewDetailWork.totalMs,"detail subset work conservation");
        near(total,old.viewSubmission.totalMs,"all detailed frame view work represented");
        t.reset();check(old.viewDetail(3).totalMs==35/1e6,"publication does not retain mutable scratch");
        record(stats,null,200,false);
        check(stats.snapshot().bucket(31).frames==2&&stats.snapshot().bucket(31).viewDetailFrames==1,"missing is not zero sample");
        fill(t,5);record(stats,t,400,true);
        check(stats.snapshot().transitionFrames==1&&stats.snapshot().bucket(31).viewDetailFrames==1,"transition excludes details too");
        ViewSubmissionTiming wrong=fixture(4);bad(()->record(stats,wrong,600,false));
        wrong.reset();wrong.begin(0,1);bad(()->record(stats,wrong,600,false));
        check(stats.snapshot().frames==3,"invalid detail cannot partly publish coarse counters");
        stats.reset();check(stats.snapshot().bucket(31).viewDetailFrames==0&&stats.snapshot().bucket(31).viewDetailWork.count==0,"reset clears detailed subset");
        check(old.viewDetailFrames==1,"snapshot independent of reset");
    }
    private static void boundedRepeatedFrames() {
        FramePacingStats stats=new FramePacingStats();ViewSubmissionTiming t=new ViewSubmissionTiming();
        for(int i=0;i<100_000;i++){fill(t,5);record(stats,t,i*200L,false);}
        FramePacingStats.Snapshot snapshot=stats.snapshot();var b=snapshot.bucket(31);
        check(snapshot.bucketCapacity()==61&&snapshot.retainedFrameSamples==0,"no per-frame retention");
        check(b.viewDetailFrames==100_000&&b.viewGroups==500_000,"bounded stage/group aggregate counts");
        near(b.viewDetail(3).meanMs,35/1e6,"stable aggregate mean");
    }
    private static void near(double a,double b,String label){check(Math.abs(a-b)<1e-10,label);}
    private static void bad(Runnable action){try{action.run();throw new AssertionError("expected rejected timing");}catch(IllegalArgumentException|IllegalStateException expected){checks++;}}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
}
