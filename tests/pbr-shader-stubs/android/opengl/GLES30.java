package android.opengl;

import java.util.*;

/** Host boundary only: program/shader ownership and CURRENT_PROGRAM, never GLSL execution. */
public final class GLES30 {
    public static int next,current,compileCalls,linkCalls,failCompile,failLink,failDelete,glError;
    public static final Set<Integer> shaders=new HashSet<>(),programs=new HashSet<>();
    public static final Map<Integer,String> sources=new HashMap<>();
    public static final List<Integer> deleted=new ArrayList<>();
    public static void reset(){next=1;current=compileCalls=linkCalls=failCompile=failLink=failDelete=glError=0;shaders.clear();programs.clear();sources.clear();deleted.clear();}
    public static int glCreateShader(int type){int id=next++;shaders.add(id);return id;}
    public static void glShaderSource(int id,String source){sources.put(id,source);}
    public static void glCompileShader(int id){compileCalls++;}
    public static void glGetShaderiv(int id,int key,int[] out,int offset){out[offset]=compileCalls==failCompile?0:1;}
    public static String glGetShaderInfoLog(int id){return "injected compile";}
    public static void glDeleteShader(int id){if(!shaders.remove(id))throw new AssertionError("shader deleted twice "+id);}
    public static int glCreateProgram(){int id=next++;programs.add(id);return id;}
    public static void glAttachShader(int id,int shader){if(!programs.contains(id)||!shaders.contains(shader))throw new AssertionError("invalid attach");}
    public static void glLinkProgram(int id){linkCalls++;}
    public static void glGetProgramiv(int id,int key,int[] out,int offset){out[offset]=linkCalls==failLink?0:1;}
    public static String glGetProgramInfoLog(int id){return "injected link";}
    public static int glGetUniformLocation(int id,String name){return 1;}
    public static void glUseProgram(int id){if(!programs.contains(id))throw new AssertionError("unknown program");current=id;}
    public static void glDeleteProgram(int id){if(!programs.remove(id))throw new AssertionError("program deleted twice "+id);deleted.add(id);if(id==failDelete)throw new IllegalStateException("injected delete");}
    public static void glGetIntegerv(int name,int[] out,int offset){if(name!=0x8b8d)throw new AssertionError("unexpected state");out[offset]=current;}
    public static int glGetError(){int e=glError;glError=0;return e;}
}
