package android.opengl;

import java.nio.Buffer;
import java.util.*;

/** GL ownership/dispatch boundary fake. Does not compile GLSL or claim pixels/GPU speed. */
public final class GLES30 {
    public static int getCalls,uniformReads; public static boolean failPnUpload; private static final Map<Integer,Integer> samplerValues=new HashMap<>();
    public static int failDraw,active=0x84c0,unpack;
    public static final Map<Integer,Integer> textureBindings=new HashMap<>(),samplers=new HashMap<>(),pixelStore=new HashMap<>();
    public static final Map<Integer,float[]> textureFloats=new HashMap<>();
    public static int textureUploads;
    public static boolean wrongCurrent,failUpload,failStorage;
    public static final Set<Integer> textures=new HashSet<>();
    public static final Map<Integer,float[]> vertexData=new HashMap<>();
    public static final Map<Integer,int[]> indexData=new HashMap<>();
    public static int next,current,array,index,compileCalls,linkCalls,failCompile,failLink,failDelete,glError,bufferDataCalls;
    public static final Set<Integer> shaders=new HashSet<>(),programs=new HashSet<>(),buffers=new HashSet<>();
    public static final Map<Integer,String> sources=new HashMap<>(),uniformNames=new HashMap<>();
    public static final Map<Integer,String> linkedSources=new HashMap<>();
    private static final Map<Integer,List<Integer>> attachments=new HashMap<>();
    private static final Map<String,float[]> matrices=new HashMap<>();
    public static final List<Draw> draws=new ArrayList<>();
    public static final Set<Integer> enabled=new HashSet<>();
    private static final Map<Integer,Integer> attribBuffers=new HashMap<>();
    public record Draw(int program,int count,int byteOffset,int indexBuffer,Map<Integer,Integer> attributes,float[] vp,float[] world,float[] normal){}
    public static void reset(){getCalls=uniformReads=0;failPnUpload=false;samplerValues.clear();next=1;failUpload=failStorage=false;active=0x84c0;unpack=0;textureUploads=0;textureBindings.clear();samplers.clear();pixelStore.clear();textureFloats.clear();failDraw=0;wrongCurrent=false;textures.clear();vertexData.clear();indexData.clear();current=array=index=compileCalls=linkCalls=failCompile=failLink=failDelete=glError=bufferDataCalls=0;shaders.clear();programs.clear();buffers.clear();sources.clear();uniformNames.clear();linkedSources.clear();attachments.clear();matrices.clear();draws.clear();enabled.clear();attribBuffers.clear();}
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
    public static int glGetUniformLocation(int id,String name){int value=next++;uniformNames.put(value,name);return value;}
    public static void glUseProgram(int id){if(!programs.contains(id))throw new AssertionError("unknown program");current=id;}
    public static void glDeleteProgram(int id){if(!programs.remove(id))throw new AssertionError("program deleted twice "+id);if(id==failDelete)throw new IllegalStateException("injected delete");}
    public static void glGetIntegerv(int name,int[] out,int offset){getCalls++;out[offset]=switch(name){case 0x8C1D -> boundArray;case 0x9631 -> 4;case 0x8b8d -> wrongCurrent?0:current;case 0x821b -> 3;case 0x821c -> 2;case 0x84e0 -> active;case 0x8069 -> textureBindings.getOrDefault(active,0);case 0x8919 -> samplers.getOrDefault(active-0x84c0,0);case 0x88ef -> unpack;case 0x0cf5 -> pixelStore.getOrDefault(name,4);case 0x0cf2,0x0cf3,0x0cf4 -> pixelStore.getOrDefault(name,0);default ->1024;};}
    public static int glGetError(){int e=glError;glError=0;return e;}
    public static void glGenBuffers(int count,int[] values,int offset){for(int i=0;i<count;i++){int id=next++;buffers.add(id);values[offset+i]=id;}}
    public static void glDeleteBuffers(int count,int[] values,int offset){for(int i=0;i<count;i++)if(values[offset+i]!=0&&!buffers.remove(values[offset+i]))throw new AssertionError("invalid buffer deletion");}
    public static void glBindBuffer(int target,int id){if(target==0x88ec){unpack=id;return;}if(id!=0&&!buffers.contains(id))throw new AssertionError("unowned buffer");if(target==0x8892)array=id;else if(target==0x8893)index=id;else throw new AssertionError("unexpected buffer target");}
    public static void glBufferData(int target,int bytes,Buffer source,int usage){
        if(bytes<=0)throw new AssertionError("empty buffer");bufferDataCalls++;
        if(target==0x8892){float[] out=new float[bytes/4];if(source instanceof java.nio.FloatBuffer f)for(int i=0;i<out.length;i++)out[i]=f.get(i);vertexData.put(array,out);}
        else {int[] out=new int[bytes/4];if(source instanceof java.nio.IntBuffer b)for(int i=0;i<out.length;i++)out[i]=b.get(i);indexData.put(index,out);}
    }
    public static void glBufferSubData(int target,int offset,int size,Buffer data){if(failPnUpload){glError=0x502;return;}float[] out=vertexData.get(array);java.nio.FloatBuffer f=(java.nio.FloatBuffer)data;for(int i=0;i<size/4;i++)out[offset/4+i]=f.get(i);}
    public static void glGenTextures(int count,int[] ids,int offset){for(int i=0;i<count;i++){int id=next++;textures.add(id);ids[offset+i]=id;}}
    public static void glDeleteTextures(int count,int[] ids,int offset){for(int i=0;i<count;i++)if(ids[offset+i]!=0&&!textures.remove(ids[offset+i]))throw new AssertionError("unknown texture deletion");}
    public static void glActiveTexture(int unit){active=unit;}
    public static void glBindSampler(int unit,int name){samplers.put(unit,name);}
    public static void glPixelStorei(int key,int value){pixelStore.put(key,value);}
    public static void glTexSubImage2D(int target,int level,int x,int y,int w,int h,int format,int type,Buffer value){if(failUpload)throw new IllegalStateException("injected upload failure");if(unpack!=0||pixelStore.getOrDefault(0x0cf2,0)!=0||pixelStore.getOrDefault(0x0cf3,0)!=0||pixelStore.getOrDefault(0x0cf4,0)!=0)throw new AssertionError("upload unpack override missing");java.nio.FloatBuffer f=((java.nio.FloatBuffer)value).duplicate();float[] b=new float[w*h*4];f.get(b);textureFloats.put(textureBindings.get(active),b);textureUploads++;}
    public static void glBindTexture(int target,int id){if(id!=0&&!textures.contains(id))throw new AssertionError("unknown texture binding");if(target==0x8C1A)boundArray=id;else textureBindings.put(active,id);}
    public static void glTexParameteri(int target,int name,int value){}
    public static void glTexStorage2D(int target,int count,int format,int width,int height){if(failStorage)throw new IllegalStateException("injected storage failure");}
    public static void glGenerateMipmap(int target){}
    public static void glUniform1f(int loc,float value){}
    public static void glVertexAttrib2f(int loc,float x,float y){}
    public static void glVertexAttrib4f(int loc,float x,float y,float z,float w){}
    public static void glEnable(int cap){}
    public static void glDisable(int cap){}
    public static void glFrontFace(int mode){if(mode!=0x901)throw new AssertionError("changed winding");}
    public static void glCullFace(int mode){if(mode!=0x405)throw new AssertionError("changed cull face");}
    public static void glEnableVertexAttribArray(int location){enabled.add(location);}
    public static void glDisableVertexAttribArray(int location){enabled.remove(location);}
    public static void glVertexAttribPointer(int loc,int size,int type,boolean normalized,int stride,int offset){attribBuffers.put(loc,array);}
    public static void glVertexAttribIPointer(int loc,int size,int type,int stride,int offset){attribBuffers.put(loc,array);}
    public static void glUniformMatrix4fv(int loc,int count,boolean transpose,float[] values,int offset){if(transpose)throw new AssertionError("transpose changed");matrices.put(current+":"+uniformNames.get(loc),Arrays.copyOfRange(values,offset,offset+count*16));}
    public static void glUniformMatrix3fv(int loc,int count,boolean transpose,float[] values,int offset){if(transpose)throw new AssertionError("transpose changed");matrices.put(current+":"+uniformNames.get(loc),Arrays.copyOfRange(values,offset,offset+count*9));}
    public static void glUniform4fv(int loc,int count,float[] values,int offset){}
    public static void glUniform1i(int loc,int value){samplerValues.put(loc,value);}
    public static void glGetUniformiv(int program,int loc,int[] out,int offset){uniformReads++;out[offset]=samplerValues.getOrDefault(loc,-1);}
    public static void glDrawElements(int mode,int count,int type,int offset){
        if(draws.size()+1==failDraw)throw new IllegalStateException("injected draw failure");
        if(mode!=4||type!=0x1405||index==0||!programs.contains(current))throw new AssertionError("unexpected draw");
        Map<Integer,Integer> attributes=new HashMap<>();for(int id:enabled)attributes.put(id,attribBuffers.get(id));
        draws.add(new Draw(current,count,offset,index,attributes,matrices.get(current+":uViewProjection"),matrices.get(current+":uWorld"),matrices.get(current+":uNormal")));
    }
    public static boolean write;public static int boundArray,attachment,layer,format,decode,framebuffer,finish;
    public static final List<Boolean> writeAtDraw=new ArrayList<>(),writeAtClear=new ArrayList<>(),writeAtFinal=new ArrayList<>();
    public static boolean glIsEnabled(int cap){return cap==0x8DB9&&write;}
    public static void glGetTexParameteriv(int t,int key,int[] out,int off){out[off]=decode;}
    public static int glCheckFramebufferStatus(int t){return 0x8CD5;}
    public static void glGetFramebufferAttachmentParameteriv(int t,int a,int key,int[] out,int off){
      int[] data=attachmentsByFbo.getOrDefault(framebuffer+":"+a,new int[4]);
      out[off]=key==0x8CD0?0x1702:key==0x8CD1?data[0]:key==0x8CD2?0:key==0x8210?(formats.getOrDefault(data[0],0)==0x8C43?0x8C40:0x2601):key==0x8CD4?data[1]:key==0x9632?data[1]:key==0x9630?data[2]:0;
    }
    public static void glGenFramebuffers(int count,int[] out,int off){for(int i=0;i<count;i++)out[off+i]=next++;}
    public static void glDeleteFramebuffers(int n,int[] out,int off){}
    public static void glBindFramebuffer(int target,int id){framebuffer=id;framebufferBinds++;}
    public static void glFramebufferTextureLayer(int target,int at,int texture,int level,int n){attachment=texture;layer=n;attachmentsByFbo.put(framebuffer+":"+at,new int[]{texture,n,0,0});}
    public static void glFramebufferRenderbuffer(int a,int b,int c,int d){}
    public static void glGenRenderbuffers(int count,int[] out,int off){for(int i=0;i<count;i++)out[off+i]=next++;}
    public static void glDeleteRenderbuffers(int n,int[] out,int off){}
    public static void glBindRenderbuffer(int a,int b){}
    public static void glRenderbufferStorage(int a,int b,int c,int d){}
    public static void glTexStorage3D(int target,int levels,int internal,int w,int h,int d){format=internal;formats.put(boundArray,internal);}
    public static void glViewport(int x,int y,int w,int h){}
    public static void glClearColor(float r,float g,float b,float a){}
    public static void glClear(int mask){writeAtClear.add(write);}
    public static void glInvalidateFramebuffer(int t,int n,int[] at,int offset){}
    public static void glFinish(){finish++;}
    public static void glDrawArrays(int mode,int start,int count){if(framebuffer!=0)throw new AssertionError("final framebuffer");writeAtFinal.add(write);finalPrograms.add(current);}

    public static int framebufferBinds;
    public static final Map<String,int[]> attachmentsByFbo=new HashMap<>();
    public static final Map<Integer,Integer> formats=new HashMap<>();
    public static final List<Integer> finalPrograms=new ArrayList<>();
    public static String glGetString(int key){return key==0x1F03?"GL_OVR_multiview GL_OVR_multiview2 GL_EXT_texture_sRGB_decode GL_EXT_sRGB_write_control":"Host GL boundary (not driver)";}
    public static void attachMultiview(int at,int texture,int base,int count){attachmentsByFbo.put(framebuffer+":"+at,new int[]{texture,base,count,0});}
}
