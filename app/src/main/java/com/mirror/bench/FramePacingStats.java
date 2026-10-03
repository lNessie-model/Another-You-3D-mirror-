package com.mirror.bench;

/**
 * Constant-space successful-callback accounting. No clock, Android, GL or pacing policy lives here.
 * Durations are actual monotonic wall time around APIs, not requested sleep or GPU execution time.
 * A gap spans the previous measured work end to the next callback entry, including publication,
 * EGL swap, framework dispatch and OS scheduling. It cannot isolate any of those components.
 */
final class FramePacingStats {
    private final Bucket[] buckets=new Bucket[61];
    private long frames,transitionFrames,lastEpoch,lastEntry,lastEnd;
    private int lastTarget;
    private boolean hasPrevious;

    FramePacingStats() {for(int i=0;i<buckets.length;i++)buckets[i]=new Bucket();}

    /**
     * epoch changes on target/lifecycle boundaries. transition marks an epoch change inside a
     * callback; that callback is counted separately and is not assigned to either target bucket.
     * Initial/boundary callbacks retain work/waits, but omit gaps and stale deadline debt.
     */
    synchronized void record(int target,long epoch,long entry,long start,long end,long pacerWait,
            int parkCalls,long fenceWait,boolean fenceCalled,long entryLate,long startLate,
            boolean rebased,boolean transition) {
        if(target<0||target>=buckets.length||entry<0||start<entry||end<start||pacerWait<0
                ||pacerWait>start-entry||parkCalls<0||(parkCalls==0&&pacerWait!=0)
                ||fenceWait<0||fenceWait>end-start||(!fenceCalled&&fenceWait!=0)
                ||entryLate<0||startLate<0)
            throw new IllegalArgumentException("Invalid frame-pacing measurement");
        boolean continuous=hasPrevious&&epoch==lastEpoch&&target==lastTarget;
        if(continuous&&entry<lastEnd)throw new IllegalArgumentException("Overlapping frame-pacing measurement");
        frames++;
        if(transition) {transitionFrames++;hasPrevious=false;return;}
        Bucket b=buckets[target];b.frames++;b.parkCalls+=parkCalls;if(fenceCalled)b.fenceCalls++;
        b.pacerWait.add(pacerWait);b.fenceWait.add(fenceWait);b.work.add(end-start);
        if(continuous) {
            b.gap.add(entry-lastEnd);b.interval.add(entry-lastEntry);
            b.entryLateness.add(entryLate);b.startLateness.add(startLate);
            if(rebased)b.deadlineRebases++;
        } else b.boundaryFrames++;
        lastEpoch=epoch;lastTarget=target;lastEntry=entry;lastEnd=end;hasPrevious=true;
    }
    /**
     * Five contiguous wall-time segments of exactly start..end. Validation precedes publication,
     * including transition frames. Existing record callers retain their original no-stage semantics.
     * Fence wait is already inside preViews and must never be added as a sixth segment.
     */
    synchronized void recordStages(int target,long epoch,long entry,long start,long end,long pacerWait,
            int parkCalls,long fenceWait,boolean fenceCalled,long entryLate,long startLate,
            boolean rebased,boolean transition,long preViews,long avatarPrepare,long viewSubmission,
            long interlaceSubmission,long submitTail) {
        if(start<0||end<start)throw new IllegalArgumentException("Invalid staged callback interval");
        long remaining=end-start;
        remaining=subtractStage(remaining,preViews);remaining=subtractStage(remaining,avatarPrepare);
        remaining=subtractStage(remaining,viewSubmission);remaining=subtractStage(remaining,interlaceSubmission);
        remaining=subtractStage(remaining,submitTail);
        if(remaining!=0)throw new IllegalArgumentException("Callback stages do not cover work interval");
        if(fenceWait>preViews)throw new IllegalArgumentException("Fence wait exceeds its enclosing pre-view stage");
        record(target,epoch,entry,start,end,pacerWait,parkCalls,fenceWait,fenceCalled,entryLate,startLate,rebased,transition);
        if(transition)return;
        Bucket b=buckets[target];b.stageFrames++;
        b.preViews.add(preViews);b.avatarPrepare.add(avatarPrepare);b.viewSubmission.add(viewSubmission);
        b.interlaceSubmission.add(interlaceSubmission);b.submitTail.add(submitTail);b.stageWork.add(end-start);
    }
    private static long subtractStage(long remaining,long stage) {
        if(stage<0||stage>remaining)throw new IllegalArgumentException("Callback stage is negative or exceeds work interval");
        return remaining-stage;
    }
    /** Optional avatar-only detail. The GL owner retains scratch ownership; no reference is stored. */
    synchronized void recordDetailedStages(int target,long epoch,long entry,long start,long end,long pacerWait,
            int parkCalls,long fenceWait,boolean fenceCalled,long entryLate,long startLate,
            boolean rebased,boolean transition,long preViews,long avatarPrepare,long viewSubmission,
            long interlaceSubmission,long submitTail,ViewSubmissionTiming detail) {
        if(detail!=null) {
            if(!detail.isComplete()||detail.totalNs()!=viewSubmission)
                throw new IllegalArgumentException("View detail must cover the complete view-submission interval");
            long remaining=viewSubmission;
            for(int i=0;i<ViewSubmissionTiming.STAGES;i++)remaining=subtractStage(remaining,detail.durationNs(i));
            if(remaining!=0)throw new IllegalArgumentException("View detail stages do not cover their interval");
        }
        recordStages(target,epoch,entry,start,end,pacerWait,parkCalls,fenceWait,fenceCalled,entryLate,startLate,
                rebased,transition,preViews,avatarPrepare,viewSubmission,interlaceSubmission,submitTail);
        if(transition||detail==null)return;
        Bucket b=buckets[target];b.viewDetailFrames++;b.viewGroups+=detail.groups();
        for(int i=0;i<ViewSubmissionTiming.STAGES;i++)b.viewDetails[i].add(detail.durationNs(i));
        b.viewDetailWork.add(viewSubmission);
    }
    synchronized void reset() {
        frames=transitionFrames=lastEpoch=lastEntry=lastEnd=0;lastTarget=0;hasPrevious=false;
        for(Bucket bucket:buckets)bucket.reset();
    }
    synchronized Snapshot snapshot() {
        BucketSnapshot[] copy=new BucketSnapshot[buckets.length];
        for(int i=0;i<copy.length;i++)copy[i]=new BucketSnapshot(i,buckets[i]);
        return new Snapshot(frames,transitionFrames,copy);
    }
    static final class Snapshot {
        final long frames,transitionFrames;
        final int retainedFrameSamples=0;
        private final BucketSnapshot[] buckets;
        private Snapshot(long frames,long transitionFrames,BucketSnapshot[] buckets) {
            this.frames=frames;this.transitionFrames=transitionFrames;this.buckets=buckets;
        }
        int bucketCapacity(){return buckets.length;}
        BucketSnapshot bucket(int target){return buckets[target];}
    }
    static final class BucketSnapshot {
        final int targetFps;
        final long frames,parkCalls,fenceCalls,boundaryFrames,deadlineRebases,stageFrames,viewDetailFrames,viewGroups;
        final MetricSnapshot pacerWait,fenceWait,work,gap,interval,entryLateness,startLateness;
        final MetricSnapshot preViews,avatarPrepare,viewSubmission,interlaceSubmission,submitTail,stageWork;
        final MetricSnapshot viewDetailWork;
        private final MetricSnapshot[] viewDetails=new MetricSnapshot[ViewSubmissionTiming.STAGES];
        private BucketSnapshot(int target,Bucket b) {
            targetFps=target;frames=b.frames;parkCalls=b.parkCalls;fenceCalls=b.fenceCalls;
            boundaryFrames=b.boundaryFrames;deadlineRebases=b.deadlineRebases;
            stageFrames=b.stageFrames;
            viewDetailFrames=b.viewDetailFrames;viewGroups=b.viewGroups;
            pacerWait=new MetricSnapshot(b.pacerWait);fenceWait=new MetricSnapshot(b.fenceWait);
            work=new MetricSnapshot(b.work);gap=new MetricSnapshot(b.gap);interval=new MetricSnapshot(b.interval);
            entryLateness=new MetricSnapshot(b.entryLateness);startLateness=new MetricSnapshot(b.startLateness);
            preViews=new MetricSnapshot(b.preViews);avatarPrepare=new MetricSnapshot(b.avatarPrepare);
            viewSubmission=new MetricSnapshot(b.viewSubmission);interlaceSubmission=new MetricSnapshot(b.interlaceSubmission);
            submitTail=new MetricSnapshot(b.submitTail);stageWork=new MetricSnapshot(b.stageWork);
            viewDetailWork=new MetricSnapshot(b.viewDetailWork);
            for(int i=0;i<viewDetails.length;i++)viewDetails[i]=new MetricSnapshot(b.viewDetails[i]);
        }
        MetricSnapshot viewDetail(int stage){return viewDetails[stage];}
    }
    static final class MetricSnapshot {
        final long count;
        final double totalMs,meanMs,maxMs;
        private MetricSnapshot(Metric m) {
            count=m.count;totalMs=m.totalNs/1e6;meanMs=count==0?0:totalMs/count;maxMs=m.maxNs/1e6;
        }
    }
    private static final class Metric {
        long count,maxNs;double totalNs;
        void add(long ns){count++;totalNs+=ns;maxNs=Math.max(maxNs,ns);}
        void reset(){count=maxNs=0;totalNs=0;}
    }
    private static final class Bucket {
        long frames,parkCalls,fenceCalls,boundaryFrames,deadlineRebases,stageFrames,viewDetailFrames,viewGroups;
        final Metric pacerWait=new Metric(),fenceWait=new Metric(),work=new Metric(),gap=new Metric(),
                interval=new Metric(),entryLateness=new Metric(),startLateness=new Metric();
        final Metric preViews=new Metric(),avatarPrepare=new Metric(),viewSubmission=new Metric(),
                interlaceSubmission=new Metric(),submitTail=new Metric(),stageWork=new Metric();
        final Metric viewDetailWork=new Metric();
        final Metric[] viewDetails=new Metric[ViewSubmissionTiming.STAGES];
        Bucket(){for(int i=0;i<viewDetails.length;i++)viewDetails[i]=new Metric();}
        void reset() {
            frames=parkCalls=fenceCalls=boundaryFrames=deadlineRebases=stageFrames=viewDetailFrames=viewGroups=0;
            pacerWait.reset();fenceWait.reset();work.reset();gap.reset();interval.reset();
            entryLateness.reset();startLateness.reset();
            preViews.reset();avatarPrepare.reset();viewSubmission.reset();interlaceSubmission.reset();
            submitTail.reset();stageWork.reset();
            viewDetailWork.reset();for(Metric metric:viewDetails)metric.reset();
        }
    }
}
