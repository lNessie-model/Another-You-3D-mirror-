package android.opengl;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Only upload state is modeled. Production Scene is separately compiled against the real SDK. */
public final class GLES30 {
    public static final Map<Integer,Integer> state=new HashMap<>();
    public static final List<String> calls=new ArrayList<>();
    public static final List<Integer> deleted=new ArrayList<>();
    public static int active,texture,internal,levels,width,height,rgbaUploads,rgUploads,mips,glError;
    public static boolean failRgba,failRg,failRestore,failDelete;
    public static byte[] pixels;
    private static int next;
    private static final Map<Integer,Integer> bindings=new HashMap<>();
    public static void reset(){
        state.clear();state.put(0xcf5,8);state.put(0xcf2,99);state.put(0xcf3,3);state.put(0xcf4,2);state.put(0x88ef,0);
        calls.clear();deleted.clear();rgbaUploads=rgUploads=mips=glError=texture=0;active=0x84c0;
        failRgba=failRg=failRestore=failDelete=false;pixels=null;
        next=17;bindings.clear();
    }
    public static void glGenTextures(int n,int[] ids,int offset){ids[offset]=next++;calls.add("gen");}
    public static void glDeleteTextures(int n,int[] ids,int offset){for(int i=0;i<n;i++)if(ids[offset+i]!=0)deleted.add(ids[offset+i]);calls.add("delete");if(failDelete)throw new IllegalStateException("injected delete failure");}
    public static void glActiveTexture(int unit){active=unit;calls.add("active"+unit);}
    public static void glBindTexture(int target,int id){texture=id;bindings.put(active,id);calls.add("bind"+id);}
    public static void glTexParameteri(int target,int name,int value){calls.add("param"+name+"="+value);}
    public static void glTexStorage2D(int target,int count,int format,int w,int h){
        internal=format;levels=count;width=w;height=h;calls.add("storage"+format);if(format==0x822b)pixels=new byte[w*h*2];
    }
    public static void glGetIntegerv(int name,int[] output,int offset){output[offset]=name==0x8069?bindings.getOrDefault(active,0):state.getOrDefault(name,0);calls.add("get"+name);}
    public static void glPixelStorei(int name,int value){
        state.put(name,value);calls.add("store"+name+"="+value);if(failRestore&&name==0xcf5&&value==8)glError=0x502;
    }
    public static void glTexSubImage2D(int target,int level,int x,int y,int w,int h,int format,int type,Buffer buffer){
        calls.add("rg");rgUploads++;
        if(state.get(0xcf5)!=1||state.get(0xcf2)!=0||state.get(0xcf3)!=0||state.get(0xcf4)!=0)throw new AssertionError("tight pixel unpack");
        if(format!=0x8227||type!=0x1401||level!=0||x!=0||w!=width||h>64)throw new AssertionError("RG strip format");
        ByteBuffer b=(ByteBuffer)buffer;
        if(!b.isDirect()||b.position()!=0||b.remaining()!=w*h*2)throw new AssertionError("direct exact byte range");
        for(int i=0;i<b.remaining();i++)pixels[y*width*2+i]=b.get(i);
        if(failRg)throw new IllegalStateException("injected RG upload failure");
    }
    public static void glGenerateMipmap(int target){mips++;calls.add("mipmap");}
    public static int glGetError(){int value=glError;glError=0;return value;}
    public static void glDeleteBuffers(int n,int[] ids,int offset){}
    public static void glDeleteProgram(int id){}
}
