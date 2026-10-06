package android.opengl;
/** Fake call log for adapter tests only; no rendering or timing semantics are simulated. */
public final class GLES30 {
    public static final int GL_EXTENSIONS=0x1f03,GL_QUERY_RESULT=0x8866,GL_QUERY_RESULT_AVAILABLE=0x8867,GL_NO_ERROR=0;
    public static String extensions="GL_EXT_disjoint_timer_query";
    public static int bits=64,error,availableReads,resultReads,deletes,creates,result;
    public static boolean ready,disjoint;
    public static String glGetString(int name){if(name!=GL_EXTENSIONS)throw new AssertionError();return extensions;}
    public static void glGetQueryiv(int target,int name,int[] out,int offset){if(target!=0x88bf||name!=0x8864)throw new AssertionError();out[offset]=bits;}
    public static void glGetIntegerv(int name,int[] out,int offset){if(name!=0x8fbb)throw new AssertionError();out[offset]=disjoint?1:0;disjoint=false;}
    public static void glGenQueries(int count,int[] out,int offset){creates++;for(int i=0;i<count;i++)out[offset+i]=i+1;}
    public static void glDeleteQueries(int count,int[] out,int offset){deletes+=count;}
    public static void glBeginQuery(int target,int query){if(target!=0x88bf)throw new AssertionError();}
    public static void glEndQuery(int target){if(target!=0x88bf)throw new AssertionError();}
    public static void glGetQueryObjectuiv(int query,int name,int[] out,int offset){
        if(name==GL_QUERY_RESULT_AVAILABLE){availableReads++;out[offset]=ready?1:0;}
        else if(name==GL_QUERY_RESULT){if(!ready)throw new AssertionError("Blocking fetch attempted");resultReads++;out[offset]=result;}
        else throw new AssertionError();
    }
    public static int glGetError(){int prior=error;error=0;return prior;}
}
