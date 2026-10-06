package android.opengl;

import java.nio.Buffer;
import java.util.*;

/** GL ownership/dispatch boundary fake. Does not compile GLSL or claim pixels/GPU speed. */
public final class GLES30 {
    public static int next,current,array,index,compileCalls,linkCalls,failCompile,failLink,failDelete,glError,bufferDataCalls;
    public static int attributeQueries;public static boolean wrongConstantAttribute,wrongInterpolatedAttribute;
    public static final Set<Integer> shaders=new HashSet<>(),programs=new HashSet<>(),buffers=new HashSet<>();
    public static final Map<Integer,String> sources=new HashMap<>(),uniformNames=new HashMap<>();
    public static final Map<Integer,String> linkedSources=new HashMap<>();
    private static final Map<Integer,List<Integer>> attachments=new HashMap<>();
    private static final Map<String,float[]> matrices=new HashMap<>();
    private static final Map<String,Integer> integers=new HashMap<>();
    private static final Map<Integer,Integer> uniformOwners=new HashMap<>();
    public static final Map<Integer,Integer> textureBindings=new HashMap<>();
    public static final List<Call> calls=new ArrayList<>();
    private static final Map<String,Integer> attempts=new HashMap<>();
    public static String failCall;public static int failAt,activeTexture;
    public record Call(String name,int program,String uniform,int count){}
    public static final List<Draw> draws=new ArrayList<>();
    public static final Set<Integer> enabled=new HashSet<>();
    private static final Map<Integer,Integer> attribBuffers=new HashMap<>();
    public record Draw(int program,int count,int byteOffset,int indexBuffer,Map<Integer,Integer> attributes,float[] vp,float[] world,float[] normal,Map<String,Integer> samplers,Map<Integer,Integer> textures){}
    public static void reset(){next=1;attributeQueries=0;failCall=null;failAt=0;activeTexture=0x84c0;wrongConstantAttribute=wrongInterpolatedAttribute=false;current=array=index=compileCalls=linkCalls=failCompile=failLink=failDelete=glError=bufferDataCalls=0;shaders.clear();programs.clear();buffers.clear();sources.clear();uniformNames.clear();linkedSources.clear();attachments.clear();matrices.clear();draws.clear();enabled.clear();attribBuffers.clear();integers.clear();uniformOwners.clear();textureBindings.clear();calls.clear();attempts.clear();}
    private static void before(String call){int attempt=attempts.merge(call,1,Integer::sum);if(call.equals(failCall)&&attempt==failAt)throw new IllegalStateException("injected "+call);}
    private static String uniform(int location){if(!Objects.equals(uniformOwners.get(location),current))throw new AssertionError("uniform for wrong current program");return uniformNames.get(location);}
    public static int glGetAttribLocation(int id,String name){
        attributeQueries++;if(!name.equals("aColor"))throw new AssertionError("unexpected attribute query");
        boolean active=linkedSources.get(id).contains("layout(location=2) in vec4 aColor;");
        return active?(wrongInterpolatedAttribute?-1:2):(wrongConstantAttribute?2:-1);
    }
    public static int glCreateShader(int type){int id=next++;shaders.add(id);return id;}
    public static void glShaderSource(int id,String source){sources.put(id,source);}
    public static void glCompileShader(int id){compileCalls++;}
    public static void glGetShaderiv(int id,int key,int[] out,int offset){out[offset]=compileCalls==failCompile?0:1;}
    public static String glGetShaderInfoLog(int id){return "injected compile";}
    public static void glDeleteShader(int id){if(!shaders.remove(id))throw new AssertionError("shader deleted twice "+id);}
    public static int glCreateProgram(){int id=next++;programs.add(id);attachments.put(id,new ArrayList<>());return id;}
    public static void glAttachShader(int id,int shader){if(!programs.contains(id)||!shaders.contains(shader))throw new AssertionError("invalid attach");attachments.get(id).add(shader);}
    public static void glLinkProgram(int id){linkCalls++;StringBuilder source=new StringBuilder();for(int shader:attachments.get(id))source.append(sources.get(shader));linkedSources.put(id,source.toString());}
    public static void glGetProgramiv(int id,int key,int[] out,int offset){out[offset]=linkCalls==failLink?0:1;}
    public static String glGetProgramInfoLog(int id){return "injected link";}
    public static int glGetUniformLocation(int id,String name){int value=next++;uniformNames.put(value,name);uniformOwners.put(value,id);return value;}
    public static void glUseProgram(int id){before("use");if(!programs.contains(id))throw new AssertionError("unknown program");current=id;calls.add(new Call("use",current,"",0));}
    public static void glDeleteProgram(int id){if(!programs.remove(id))throw new AssertionError("program deleted twice "+id);if(id==failDelete)throw new IllegalStateException("injected delete");}
    public static void glGetIntegerv(int name,int[] out,int offset){out[offset]=name==0x8b8d?current:1024;}
    public static int glGetError(){int e=glError;glError=0;return e;}
    public static void glGenBuffers(int count,int[] values,int offset){for(int i=0;i<count;i++){int id=next++;buffers.add(id);values[offset+i]=id;}}
    public static void glDeleteBuffers(int count,int[] values,int offset){for(int i=0;i<count;i++)if(values[offset+i]!=0&&!buffers.remove(values[offset+i]))throw new AssertionError("invalid buffer deletion");}
    public static void glBindBuffer(int target,int id){if(id!=0&&!buffers.contains(id))throw new AssertionError("unowned buffer");if(target==0x8892)array=id;else if(target==0x8893)index=id;else throw new AssertionError("unexpected buffer target");}
    public static void glBufferData(int target,int bytes,Buffer source,int usage){if(bytes<=0)throw new AssertionError("empty buffer");bufferDataCalls++;}
    public static void glBufferSubData(int target,int offset,int size,Buffer data){}
    public static void glEnable(int cap){}
    public static void glDisable(int cap){}
    public static void glFrontFace(int mode){if(mode!=0x901)throw new AssertionError("changed winding");}
    public static void glCullFace(int mode){if(mode!=0x405)throw new AssertionError("changed cull face");}
    public static void glEnableVertexAttribArray(int location){enabled.add(location);}
    public static void glDisableVertexAttribArray(int location){enabled.remove(location);}
    public static void glVertexAttribPointer(int loc,int size,int type,boolean normalized,int stride,int offset){attribBuffers.put(loc,array);}
    public static void glVertexAttribIPointer(int loc,int size,int type,int stride,int offset){attribBuffers.put(loc,array);}
    public static void glUniformMatrix4fv(int loc,int count,boolean transpose,float[] values,int offset){String name=uniform(loc),call=name.equals("uViewProjection")?"vp":"world";before(call);if(transpose)throw new AssertionError("transpose changed");matrices.put(current+":"+name,Arrays.copyOfRange(values,offset,offset+count*16));calls.add(new Call(call,current,name,count));}
    public static void glUniformMatrix3fv(int loc,int count,boolean transpose,float[] values,int offset){String name=uniform(loc);before("normal");if(transpose)throw new AssertionError("transpose changed");matrices.put(current+":"+name,Arrays.copyOfRange(values,offset,offset+count*9));calls.add(new Call("normal",current,name,count));}
    public static void glUniform4fv(int loc,int count,float[] values,int offset){}
    public static void glUniform1i(int loc,int value){String name=uniform(loc);before("sampler");integers.put(current+":"+name,value);calls.add(new Call("sampler",current,name,1));}
    public static void glActiveTexture(int unit){activeTexture=unit;}
    public static void glBindTexture(int target,int texture){if(target!=0x0de1)throw new AssertionError("unexpected texture target");textureBindings.put(activeTexture,texture);}
    public static void glDrawElements(int mode,int count,int type,int offset){
        before("draw");
        if(mode!=4||type!=0x1405||index==0||!programs.contains(current))throw new AssertionError("unexpected draw");
        Map<Integer,Integer> attributes=new HashMap<>();for(int id:enabled)attributes.put(id,attribBuffers.get(id));
        Map<String,Integer> samplers=new HashMap<>();for(String name:List.of("uAtlas","uNormalMap","uOrmMap"))if(integers.containsKey(current+":"+name))samplers.put(name,integers.get(current+":"+name));
        draws.add(new Draw(current,count,offset,index,attributes,matrices.get(current+":uViewProjection"),matrices.get(current+":uWorld"),matrices.get(current+":uNormal"),Map.copyOf(samplers),Map.copyOf(textureBindings)));
        calls.add(new Call("draw",current,"",count));
    }
}
