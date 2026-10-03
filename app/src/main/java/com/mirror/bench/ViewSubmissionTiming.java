package com.mirror.bench;

/**
 * Reusable GL-owner scratch for one avatar view-submission interval. No clocks, allocations per
 * frame, GL calls or retained history. Markers partition CPU wall time, including scheduling and
 * implicit driver stalls; they are not GPU timers. Consume after finish and before begin/reset.
 */
final class ViewSubmissionTiming {
    static final int STAGES=6;
    private static final String[] NAMES={"setup","attach_clear","camera_matrices","scene_draw","invalidate","tail"};
    private final long[] durations=new long[STAGES];
    private long start,last;
    private int phase,expectedGroups,groups;
    private boolean started,complete;

    void reset() {
        for(int i=0;i<STAGES;i++)durations[i]=0;
        start=last=0;phase=expectedGroups=groups=0;started=complete=false;
    }
    void begin(long now,int expectedGroups) {
        if(now<0||expectedGroups<1||expectedGroups>32)throw new IllegalArgumentException("Invalid view timing start/groups");
        if(started&&!complete)throw new IllegalStateException("Previous view timing is unfinished");
        reset();started=true;start=last=now;this.expectedGroups=expectedGroups;
    }
    void setupComplete(long now){mark(now,0,1);}
    void attachmentsComplete(long now) {
        if(groups>=expectedGroups)throw new IllegalStateException("Too many view groups");
        mark(now,1,2);
    }
    void cameraComplete(long now){mark(now,2,3);}
    void sceneComplete(long now){mark(now,3,4);}
    void groupComplete(long now){mark(now,4,1);groups++;}
    void finish(long now) {
        if(!started||complete||phase!=1||groups!=expectedGroups)
            throw new IllegalStateException("View timing must finish after every expected group");
        if(now<last)throw new IllegalArgumentException("View timing clock moved backward");
        durations[5]=now-last;last=now;complete=true;
    }
    private void mark(long now,int expectedPhase,int nextPhase) {
        if(!started||complete||phase!=expectedPhase)throw new IllegalStateException("View timing marker is out of order");
        if(now<last)throw new IllegalArgumentException("View timing clock moved backward");
        durations[expectedPhase]+=now-last;last=now;phase=nextPhase;
    }
    boolean isStarted(){return started;}
    boolean isComplete(){return complete;}
    int groups(){requireComplete();return groups;}
    long durationNs(int index){requireComplete();return durations[index];}
    long totalNs(){requireComplete();return last-start;}
    static String stageName(int index){return NAMES[index];}
    private void requireComplete(){if(!complete)throw new IllegalStateException("View timing is incomplete");}
}
