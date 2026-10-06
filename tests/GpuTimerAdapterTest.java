package com.mirror.bench;
import android.opengl.*;
public final class GpuTimerAdapterTest {
    static int checks;static void ok(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
    public static void main(String[] args){
        Gles30GpuTimerBackend b=new Gles30GpuTimerBackend();ok(b.currentContext());ok(b.counterBits()==64);
        GLES30.extensions="GL_EXT_disjoint_timer_query_suffix";ok(b.counterBits()==0);
        GLES30.extensions="GL_A GL_EXT_disjoint_timer_query GL_B";ok(b.counterBits()==64);
        int[] ids=b.create(16);ok(ids.length==16&&ids[15]==16&&GLES30.creates==1);
        ok(!b.available(ids[0]));try{b.result(ids[0]);throw new AssertionError();}catch(IllegalStateException expected){checks++;}
        ok(GLES30.resultReads==0);GLES30.ready=true;GLES30.result=-1;ok(b.result(ids[0])==0xffffffffL);
        GLES30.result=Integer.MIN_VALUE;ok(b.result(ids[0])==0x80000000L);ok(GLES30.availableReads==4&&GLES30.resultReads==2);
        GLES30.disjoint=true;ok(b.disjoint());ok(!b.disjoint());
        b.begin(ids[0]);GLES30.error=0x502;
        try{b.end();throw new AssertionError("Prior draw error swallowed");}catch(GpuTimerProbe.GlFailure expected){ok(expected.errorCode==0x502);}
        b.delete(ids);ok(GLES30.deletes==16);
        EGL14.current=new EGLContext(2);ok(!b.currentContext());
        try{b.delete(ids);throw new AssertionError("Old ids deleted on new context");}catch(IllegalStateException expected){checks++;}
        ok(GLES30.deletes==16);EGL14.current=EGL14.EGL_NO_CONTEXT;b=new Gles30GpuTimerBackend();ok(!b.currentContext());
        System.out.println("GpuTimerAdapterTest PASS "+checks+" checks (fake GL calls; not GPU execution)");
    }
}
