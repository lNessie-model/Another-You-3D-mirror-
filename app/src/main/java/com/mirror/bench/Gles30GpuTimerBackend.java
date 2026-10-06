package com.mirror.bench;

import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES30;

/** Core ES3 query entry points are also valid for EXT_disjoint_timer_query (spec ES3 interaction).
 * No new JNI library. 32-bit QUERY_RESULT saturates; unsigned widening plus GpuTimerProbe's strict
 * <500ms enclosing-host-age guard makes acceptance safe with a >=30-bit counter.
 * Instantiate only after explicit opt-in on the GL owner. No probes from the default-off path.
 */
public final class Gles30GpuTimerBackend implements GpuTimerProbe.Driver {
    private static final int TIME_ELAPSED=0x88BF,COUNTER_BITS=0x8864,GPU_DISJOINT=0x8FBB;
    private final EGLContext context=EGL14.eglGetCurrentContext();
    private final int[] value=new int[1];
    @Override public boolean currentContext(){return !context.equals(EGL14.EGL_NO_CONTEXT)&&context.equals(EGL14.eglGetCurrentContext());}
    @Override public int counterBits(){
        requireContext();check("entry");String extensions=GLES30.glGetString(GLES30.GL_EXTENSIONS);check("extensions");
        if(extensions==null||!(" "+extensions+" ").contains(" GL_EXT_disjoint_timer_query "))return 0;
        GLES30.glGetQueryiv(TIME_ELAPSED,COUNTER_BITS,value,0);check("counter bits");return value[0];
    }
    @Override public int[] create(int count){
        requireContext();if(count!=GpuTimerProbe.SLOTS)throw new IllegalArgumentException("Fixed query pool required");
        int[] ids=new int[count];try{GLES30.glGenQueries(count,ids,0);check("create");return ids;}
        catch(RuntimeException failure){GLES30.glDeleteQueries(count,ids,0);throw failure;}
    }
    @Override public boolean disjoint(){requireContext();GLES30.glGetIntegerv(GPU_DISJOINT,value,0);check("disjoint");return value[0]!=0;}
    @Override public void begin(int query){requireContext();GLES30.glBeginQuery(TIME_ELAPSED,query);check("begin");}
    @Override public void end(){requireContext();GLES30.glEndQuery(TIME_ELAPSED);check("end");}
    @Override public boolean available(int query){
        requireContext();GLES30.glGetQueryObjectuiv(query,GLES30.GL_QUERY_RESULT_AVAILABLE,value,0);check("available");return value[0]!=0;
    }
    @Override public long result(int query){
        if(!available(query))throw new IllegalStateException("Query became unavailable; no blocking read attempted");
        GLES30.glGetQueryObjectuiv(query,GLES30.GL_QUERY_RESULT,value,0);check("result");return unsignedResult(value[0]);
    }
    static long unsignedResult(int bits){return Integer.toUnsignedLong(bits);}
    @Override public void delete(int[] queries){requireContext();GLES30.glDeleteQueries(queries.length,queries,0);check("delete");}
    private void requireContext(){if(!currentContext())throw new IllegalStateException("Wrong or lost EGL context");}
    private static void check(String operation){int error=GLES30.glGetError();if(error!=GLES30.GL_NO_ERROR)throw new GpuTimerProbe.GlFailure(error,operation);}
}
