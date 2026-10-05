package com.mirror.bench;

/** Real production startup tickets: late/paused/destroyed results cannot start hardware. */
public final class MirrorStartupGateTest {
    private static int checks;
    private static void require(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static void rejected(MirrorStartupGate gate,int token,String why){
        try{gate.markReady(token);throw new AssertionError(why);}catch(IllegalStateException expected){checks++;}
    }
    public static void main(String[] args){
        MirrorStartupGate normal=new MirrorStartupGate();
        require(normal.beginRead()<0,"A created but not resumed page must not read/start");
        normal.resume();int active=normal.beginRead();require(active>=0,"Resumed startup starts metadata read");
        require(normal.beginRead()<0,"One metadata owner only");
        require(normal.complete(active)==MirrorStartupGate.Completion.APPLY,"Current resumed metadata can configure GL");
        require(normal.canApply(active),"Actual attach gate accepts current ticket");normal.markReady(active);
        require(normal.ready(),"GL configured exactly once");require(normal.beginRead()<0,"Ready screen cannot start another read");
        require(normal.complete(active)==MirrorStartupGate.Completion.IGNORE,"Duplicate callback cannot start a second renderer");

        MirrorStartupGate paused=new MirrorStartupGate();paused.resume();int pausedToken=paused.beginRead();paused.pause();
        require(paused.complete(pausedToken)==MirrorStartupGate.Completion.DEFER,"Paused callback must defer");
        require(!paused.canApply(pausedToken),"Paused attach is forbidden");rejected(paused,pausedToken,"Paused GL attach accepted");
        require(paused.beginRead()<0,"Paused retry must not start a new job");
        paused.resume();int refreshed=paused.beginRead();require(refreshed!=pausedToken,"Resume obtains a fresh selection ticket");
        require(paused.complete(refreshed)==MirrorStartupGate.Completion.APPLY,"Fresh selection can resume startup");
        paused.markReady(refreshed);require(paused.ready(),"Resume reaches actual ready state");

        MirrorStartupGate stale=new MirrorStartupGate();stale.resume();int beforePause=stale.beginRead();stale.pause();stale.resume();
        require(stale.complete(beforePause)==MirrorStartupGate.Completion.REREAD,"Callback spanning a pause must re-read selection");
        require(!stale.canApply(beforePause),"Old selection cannot attach after returning");
        int reread=stale.beginRead();require(reread!=beforePause,"Re-read uses the active lifecycle generation");
        require(stale.complete(reread)==MirrorStartupGate.Completion.APPLY,"Re-read result becomes eligible");

        MirrorStartupGate closed=new MirrorStartupGate();closed.resume();int destroyedToken=closed.beginRead();closed.destroy();
        require(closed.complete(destroyedToken)==MirrorStartupGate.Completion.IGNORE,"Destroyed page ignores callbacks");
        require(!closed.canApply(destroyedToken),"Destroyed page cannot attach hardware");
        rejected(closed,destroyedToken,"Destroyed GL attach accepted");closed.resume();require(closed.beginRead()<0,"Destroy cannot be undone by a late resume");

        MirrorStartupGate diagnostic=new MirrorStartupGate();diagnostic.markDiagnosticReady();
        require(diagnostic.ready(),"Explicit diagnostic retains its immediate entry");
        diagnostic.resume();require(diagnostic.beginRead()<0,"Diagnostic is never superseded by bundled selection");
        System.out.println("MirrorStartupGateTest: "+checks+" checks passed");
    }
}
