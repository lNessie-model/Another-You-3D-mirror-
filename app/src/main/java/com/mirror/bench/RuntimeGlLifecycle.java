package com.mirror.bench;

import android.os.Bundle;

/** Context identity and first-successful-submission gate; no GL calls or per-frame allocation. */
final class RuntimeGlLifecycle {
    record Snapshot(long contextGeneration,long frameGeneration,long epoch,boolean ready) {}
    private volatile Snapshot state=new Snapshot(0,0,0,false);
    static boolean readReleaseOnPause(Bundle extras,boolean debug) {
        String key="test_release_gl_on_pause";
        if(extras==null||!extras.containsKey(key))return false;
        Object value=extras.get(key);
        if(!debug||!(value instanceof Boolean))
            throw new IllegalArgumentException("EGL release override requires debug Boolean");
        return (Boolean)value;
    }
    synchronized long contextCreated() {
        Snapshot old=state;
        state=new Snapshot(old.contextGeneration()+1,0,old.epoch()+1,false);
        return state.contextGeneration();
    }
    synchronized void invalidate() {
        Snapshot old=state;
        state=new Snapshot(old.contextGeneration(),0,old.epoch()+1,false);
    }
    long frameEpoch(){return state.epoch();}
    Snapshot snapshot(){return state;}
    void successfulFrame(long enteredEpoch) {
        if(state.ready())return;
        synchronized(this) {
            Snapshot old=state;
            if(old.contextGeneration()>0&&enteredEpoch==old.epoch()&&!old.ready())
                state=new Snapshot(old.contextGeneration(),old.contextGeneration(),old.epoch(),true);
        }
    }
}
