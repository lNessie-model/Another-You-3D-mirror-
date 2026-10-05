package com.mirror.bench;

/** UI-thread lifecycle ticket for background metadata; never owns GL or native input. */
final class MirrorStartupGate {
    enum Completion { APPLY, DEFER, REREAD, IGNORE }
    private int generation;
    private boolean resumed,destroyed,reading,ready;
    void resume(){if(destroyed)return;resumed=true;generation++;}
    void pause(){resumed=false;generation++;}
    void destroy(){destroyed=true;resumed=false;reading=false;generation++;}
    int beginRead(){
        if(destroyed||ready||reading||!resumed)return -1;
        reading=true;return generation;
    }
    Completion complete(int token){
        if(destroyed||ready)return Completion.IGNORE;
        reading=false;
        if(!resumed)return Completion.DEFER;
        return token==generation?Completion.APPLY:Completion.REREAD;
    }
    boolean canApply(int token){return !destroyed&&resumed&&!ready&&token==generation;}
    void markReady(int token){
        if(!canApply(token))throw new IllegalStateException("Role startup requires the current resumed ticket");
        ready=true;
    }
    /** Preserves the existing immediate, explicit private-head diagnostic entry. */
    void markDiagnosticReady(){
        if(destroyed||reading||ready)throw new IllegalStateException("Diagnostic startup already consumed");
        ready=true;
    }
    boolean ready(){return ready;}
}
