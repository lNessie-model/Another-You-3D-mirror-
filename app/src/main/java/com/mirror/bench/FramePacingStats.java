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
        final long frames,parkCalls,fenceCalls,boundaryFrames,deadlineRebases;
        final MetricSnapshot pacerWait,fenceWait,work,gap,interval,entryLateness,startLateness;
        private BucketSnapshot(int target,Bucket b) {
            targetFps=target;frames=b.frames;parkCalls=b.parkCalls;fenceCalls=b.fenceCalls;
            boundaryFrames=b.boundaryFrames;deadlineRebases=b.deadlineRebases;
            pacerWait=new MetricSnapshot(b.pacerWait);fenceWait=new MetricSnapshot(b.fenceWait);
            work=new MetricSnapshot(b.work);gap=new MetricSnapshot(b.gap);interval=new MetricSnapshot(b.interval);
            entryLateness=new MetricSnapshot(b.entryLateness);startLateness=new MetricSnapshot(b.startLateness);
        }
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
        long frames,parkCalls,fenceCalls,boundaryFrames,deadlineRebases;
        final Metric pacerWait=new Metric(),fenceWait=new Metric(),work=new Metric(),gap=new Metric(),
                interval=new Metric(),entryLateness=new Metric(),startLateness=new Metric();
        void reset() {
            frames=parkCalls=fenceCalls=boundaryFrames=deadlineRebases=0;
            pacerWait.reset();fenceWait.reset();work.reset();gap.reset();interval.reset();
            entryLateness.reset();startLateness.reset();
        }
    }
}
