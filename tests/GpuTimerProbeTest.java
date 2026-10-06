package com.mirror.bench;

import java.util.Arrays;
import java.util.function.LongSupplier;

/** Deterministic real scheduler tests; GL is intentionally faked, not claimed as device evidence. */
public final class GpuTimerProbeTest {
    static int checks;
    static void ok(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    static final class Clock implements LongSupplier {long now=1_000_000;public long getAsLong(){return now++;}void step(long n){now+=n;}}
    static final class Driver implements GpuTimerProbe.Driver {
        final boolean[] ready=new boolean[GpuTimerProbe.SLOTS+1];
        int bits=64,allocated,begins,ends,availability,reads,deletes,disjointCalls,active;
        boolean context=true,disjoint,throwResult,throwGl,throwBegin,throwEnd;int disjointOnCall=-1;long value=0;
        int endGl,deleteGl,disjointGl;
        public int counterBits(){return bits;}
        public int[] create(int n){allocated=n;int[] a=new int[n];for(int i=0;i<n;i++)a[i]=i+1;return a;}
        public boolean currentContext(){return context;}
        public boolean disjoint(){disjointCalls++;if(disjointGl!=0)throw new GpuTimerProbe.GlFailure(disjointGl,"disjoint");boolean v=disjoint||disjointCalls==disjointOnCall;disjoint=false;return v;}
        public void begin(int q){if(active!=0)throw new AssertionError("nested query");active=q;begins++;ready[q]=false;if(throwBegin)throw new IllegalStateException("begin after issuing");}
        public void end(){if(active==0)throw new IllegalStateException("no query");active=0;ends++;if(endGl!=0)throw new GpuTimerProbe.GlFailure(endGl,"end");if(throwEnd)throw new IllegalStateException("end after issuing");}
        public boolean available(int q){availability++;return ready[q];}
        public long result(int q){if(!ready[q])throw new AssertionError("would block");reads++;if(throwGl)throw new GpuTimerProbe.GlFailure(0x502,"draw or query");if(throwResult)throw new IllegalStateException("injected");return value;}
        public void delete(int[] ids){deletes+=ids.length;if(deleteGl!=0)throw new GpuTimerProbe.GlFailure(deleteGl,"delete");}
        void readyAll(){Arrays.fill(ready,true);}
    }
    static GpuTimerProbe make(Driver d,Clock c,int rate){return new GpuTimerProbe(true,7,d,c,rate);}
    static long submit(GpuTimerProbe p,long frame,GpuTimerProbe.Stage s){long t=p.begin(s,frame,3);p.end(t);return t;}
    public static void main(String[] args)throws Exception {
        disabled();asynchronous();disjoint();bounded();overflowAndTimeout();contextAndClose();errorsAndEpochs();cleanupErrors();
        System.out.println("GpuTimerProbeTest PASS "+checks+" checks");
    }
    static void disabled(){
        Clock c=new Clock();Driver d=new Driver();GpuTimerProbe p=new GpuTimerProbe(false,7,d,c,8);
        ok(submit(p,0,GpuTimerProbe.Stage.VIEWS_ALL)==0);p.poll(0);p.close();ok(d.allocated==0&&d.disjointCalls==0&&d.deletes==0);
        ok(p.snapshot().state.equals("disabled"));d.bits=0;p=make(d,c,1);ok(p.snapshot().state.equals("unsupported"));ok(d.allocated==0);
        d.bits=29;p=make(d,c,1);ok(p.snapshot().state.equals("unsupported"));
    }
    static void asynchronous(){
        Clock c=new Clock();Driver d=new Driver();d.value=2_000_000;GpuTimerProbe p=make(d,c,8);
        ok(d.allocated==16);ok(submit(p,1,GpuTimerProbe.Stage.VIEWS_ALL)==0);
        ok(submit(p,8,GpuTimerProbe.Stage.VIEWS_ALL)>0);ok(submit(p,8,GpuTimerProbe.Stage.INTERLACE_WITH_BACKGROUND)>0);
        p.poll(8);ok(d.availability==0);p.poll(9);ok(d.availability==2&&d.reads==0);p.poll(9);ok(d.availability==2);
        d.readyAll();c.step(5_000_000);p.poll(10);var s=p.snapshot();
        ok(d.reads==2&&s.pending==0&&s.events.length==2);ok(s.events[0].gpuNs==2_000_000&&s.events[0].frameId==8);
        ok(s.events[0].contextGeneration==7&&s.events[0].scopeEpoch==3);ok(s.events[0].status==GpuTimerProbe.Status.VALID);
        ok(s.events[0].sequence==1&&s.events[1].sequence==2);
        ok(s.validCounts[0]==1&&s.totalGpuNs[0]==2_000_000);s.validCounts[0]=100;ok(p.snapshot().validCounts[0]==1);
        p.close();ok(d.deletes==16&&p.snapshot().state.equals("closed"));p.close();ok(d.deletes==16);
    }
    static void disjoint(){
        Clock c=new Clock();Driver d=new Driver();GpuTimerProbe p=make(d,c,1);
        submit(p,1,GpuTimerProbe.Stage.VIEWS_ALL);d.disjoint=true;p.poll(2);ok(p.snapshot().pending==1);
        d.readyAll();p.poll(3);ok(d.reads==0&&p.snapshot().events[0].status==GpuTimerProbe.Status.DISJOINT);
        submit(p,4,GpuTimerProbe.Stage.VIEWS_ALL);d.readyAll();d.disjointOnCall=d.disjointCalls+2;p.poll(5);
        ok(d.reads==1);ok(p.snapshot().events[1].status==GpuTimerProbe.Status.DISJOINT);ok(p.snapshot().validCounts[0]==0);p.close();
    }
    static void bounded(){
        Clock c=new Clock();Driver d=new Driver();GpuTimerProbe p=make(d,c,1);
        for(int i=0;i<16;i++)ok(submit(p,i,GpuTimerProbe.Stage.VIEWS_ALL)>0);
        ok(submit(p,17,GpuTimerProbe.Stage.VIEWS_ALL)==0);ok(p.snapshot().poolFull==1&&d.allocated==16&&d.begins==16);
        d.readyAll();p.poll(18);ok(p.snapshot().pending==0);
        for(int i=20;i<320;i++){submit(p,i,GpuTimerProbe.Stage.VIEWS_ALL);d.readyAll();p.poll(i+1);}
        var s=p.snapshot();ok(s.events.length==GpuTimerProbe.HISTORY);ok(s.historyOverwritten>0&&s.validCounts[0]==316);p.close();
    }
    static void overflowAndTimeout(){
        Clock c=new Clock();Driver d=new Driver();d.bits=30;GpuTimerProbe p=make(d,c,1);
        submit(p,1,GpuTimerProbe.Stage.VIEWS_ALL);d.readyAll();d.value=(1L<<30)-1;p.poll(2);
        ok(p.snapshot().events[0].status==GpuTimerProbe.Status.INVALID_RESULT);ok(p.snapshot().validCounts[0]==0);
        submit(p,3,GpuTimerProbe.Stage.VIEWS_ALL);c.step(600_000_000);p.poll(4);
        ok(p.snapshot().state.equals("active"));ok(p.snapshot().events[1].status==GpuTimerProbe.Status.TIMEOUT_OR_OVERFLOW);
        ok(p.snapshot().tombstones==1&&p.snapshot().pending==1);ok(submit(p,5,GpuTimerProbe.Stage.VIEWS_ALL)>0);
        d.readyAll();p.poll(6);ok(p.snapshot().tombstones==0);p.close();
        d=new Driver();c=new Clock();p=make(d,c,1);submit(p,0,GpuTimerProbe.Stage.VIEWS_ALL);d.readyAll();d.value=-1;p.poll(1);
        ok(p.snapshot().events[0].status==GpuTimerProbe.Status.INVALID_RESULT);p.close();
        d=new Driver();c=new Clock();p=make(d,c,1);
        for(int i=0;i<16;i++)submit(p,i,GpuTimerProbe.Stage.VIEWS_ALL);c.step(600_000_000);p.poll(17);
        ok(p.snapshot().tombstones==16);ok(submit(p,18,GpuTimerProbe.Stage.VIEWS_ALL)==0&&d.allocated==16&&d.deletes==0);
        int count=p.snapshot().events.length;p.poll(19);ok(p.snapshot().events.length==count&&d.reads==0);p.close();ok(d.deletes==16);
        ok(Gles30GpuTimerBackend.unsignedResult(-1)==0xffffffffL);
        ok(Gles30GpuTimerBackend.unsignedResult(Integer.MIN_VALUE)==0x80000000L);
        d=new Driver();c=new Clock();p=make(d,c,1);submit(p,0,GpuTimerProbe.Stage.VIEWS_ALL);d.readyAll();d.value=100_000_000;p.poll(1);
        ok(p.snapshot().events[0].status==GpuTimerProbe.Status.INVALID_RESULT);p.close();
    }
    static void contextAndClose()throws Exception {
        Clock c=new Clock();Driver d=new Driver();GpuTimerProbe p=make(d,c,1);submit(p,1,GpuTimerProbe.Stage.VIEWS_ALL);
        d.context=false;p.poll(2);ok(p.snapshot().state.equals("context_lost"));ok(d.deletes==0);p.close();ok(d.deletes==0);
        ok(p.snapshot().events[0].status==GpuTimerProbe.Status.CONTEXT_LOST);
        d=new Driver();c=new Clock();p=make(d,c,1);p.begin(GpuTimerProbe.Stage.VIEWS_ALL,1,3);p.close();
        ok(d.ends==1&&d.deletes==16);ok(p.snapshot().events[0].status==GpuTimerProbe.Status.CLOSED);
        final GpuTimerProbe other=make(new Driver(),new Clock(),1);final boolean[] rejected={false};
        Thread t=new Thread(()->{try{other.poll(1);}catch(IllegalStateException expected){rejected[0]=true;}});t.start();t.join();ok(rejected[0]);other.close();
    }
    static void errorsAndEpochs(){
        Clock c=new Clock();Driver d=new Driver();GpuTimerProbe p=make(d,c,1);
        long token=p.begin(GpuTimerProbe.Stage.VIEWS_ALL,4,3);try{p.begin(GpuTimerProbe.Stage.BACKGROUND_GEOMETRY,4,3);throw new AssertionError();}catch(IllegalStateException expected){checks++;}
        try{p.end(token+1);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}p.abort(token);
        d.readyAll();p.poll(5);ok(p.snapshot().events[0].status==GpuTimerProbe.Status.ABORTED);
        token=p.begin(GpuTimerProbe.Stage.INTERLACE_WITH_BACKGROUND,6,4);p.end(token);d.readyAll();d.throwResult=true;p.poll(7);
        ok(p.snapshot().state.equals("failed"));ok(p.snapshot().events[1].scopeEpoch==4&&p.snapshot().events[1].status==GpuTimerProbe.Status.DRIVER_ERROR);p.close();
        d=new Driver();c=new Clock();p=make(d,c,1);submit(p,0,GpuTimerProbe.Stage.VIEWS_ALL);d.readyAll();d.throwGl=true;
        try{p.poll(1);throw new AssertionError("GL error swallowed");}catch(GpuTimerProbe.GlFailure expected){ok(expected.errorCode==0x502);}
        ok(p.snapshot().state.equals("failed"));p.close();
        d=new Driver();c=new Clock();d.throwBegin=true;p=make(d,c,1);ok(submit(p,1,GpuTimerProbe.Stage.VIEWS_ALL)==0);
        ok(p.snapshot().events.length==1);p.close();ok(d.ends==1&&d.deletes==16&&p.snapshot().events.length==1);
        d=new Driver();c=new Clock();d.throwEnd=true;p=make(d,c,1);submit(p,1,GpuTimerProbe.Stage.VIEWS_ALL);
        ok(p.snapshot().events.length==1);p.close();ok(d.deletes==16&&p.snapshot().events.length==1);
    }
    static void cleanupErrors(){
        Clock c=new Clock();Driver d=new Driver();GpuTimerProbe p=make(d,c,1);submit(p,1,GpuTimerProbe.Stage.VIEWS_ALL);d.deleteGl=0x502;
        try{p.close();throw new AssertionError("Cleanup GL error swallowed");}catch(GpuTimerProbe.GlFailure e){ok(e.errorCode==0x502);}
        ok(d.deletes==16&&p.snapshot().state.equals("closed"));p.close();ok(d.deletes==16);
        d=new Driver();c=new Clock();p=make(d,c,1);p.begin(GpuTimerProbe.Stage.VIEWS_ALL,1,1);d.endGl=0x502;d.deleteGl=0x505;
        try{p.close();throw new AssertionError("End failure swallowed");}catch(GpuTimerProbe.GlFailure e){ok(e.errorCode==0x502);ok(e.getSuppressed().length==1&&((GpuTimerProbe.GlFailure)e.getSuppressed()[0]).errorCode==0x505);}
        ok(d.ends==1&&d.deletes==16);
        d=new Driver();c=new Clock();d.disjointGl=0x502;d.deleteGl=0x505;
        try{make(d,c,1);throw new AssertionError("Constructor original GL failure masked");}catch(GpuTimerProbe.GlFailure e){ok(e.errorCode==0x502);ok(e.getSuppressed().length==1&&((GpuTimerProbe.GlFailure)e.getSuppressed()[0]).errorCode==0x505);}
        ok(d.deletes==16);
    }
}
