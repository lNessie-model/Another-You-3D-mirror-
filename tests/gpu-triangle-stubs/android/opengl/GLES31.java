package android.opengl;
import java.util.*;

/** Models GL buffer ownership, command order and compute dataflow; does NOT compile GLSL. */
public final class GLES31 {
    public static final Map<Integer,Integer> ssbo=new HashMap<>();
    public static final int[] image={0,0,0,0,0x88b8,0x8229};
    public static final List<String> events=new ArrayList<>();
    public static int dispatches,barriers,failBarrier;
    public static boolean failDispatch,war,visible;
    public static void reset(){ssbo.clear();image[0]=image[1]=image[2]=image[3]=0;image[4]=0x88b8;image[5]=0x8229;events.clear();dispatches=barriers=failBarrier=0;failDispatch=war=visible=false;}
    public static void glGetIntegeri_v(int key,int index,int[] out,int offset){GLES30.getCalls++;out[offset]=key==0x90d3?ssbo.getOrDefault(index,0):image[switch(key){case 0x8f3a->0;case 0x8f3b->1;case 0x8f3c->2;case 0x8f3d->3;case 0x8f3e->4;case 0x906e->5;default->throw new AssertionError("unknown indexed state "+key);}];}
    public static void glBindBufferBase(int target,int index,int buffer){if(target!=0x90d2||buffer!=0&&!GLES30.buffers.contains(buffer))throw new AssertionError("SSBO ownership");ssbo.put(index,buffer);GLES30.ssbo=buffer;}
    public static void glBindImageTexture(int unit,int texture,int level,boolean layered,int layer,int access,int format){
        if(unit!=0||texture!=0&&!GLES30.textures.contains(texture))throw new AssertionError("image ownership");
        image[0]=texture;image[1]=level;image[2]=layered?1:0;image[3]=layer;image[4]=access;image[5]=format;
    }
    public static void glMemoryBarrier(int bits){
        barriers++;events.add("barrier:"+bits);if(barriers==failBarrier)throw new IllegalStateException("injected barrier failure");
        if(bits==0x20)war=true;else if(bits==8)visible=true;else throw new AssertionError("unexpected barrier");
    }
    public static void glDispatchCompute(int x,int y,int z){
        events.add("dispatch");if(failDispatch)throw new IllegalStateException("injected dispatch failure");
        if(!war||GLES30.framebuffer!=0||x!=144||y!=1||z!=1||image[0]==0||image[4]!=0x88b9||image[5]!=0x8814)throw new AssertionError("dispatch state/extent/WAR");
        String source=GLES30.linkedSources.get(GLES30.current);if(source==null||!source.contains("layout(local_size_x=64)"))throw new AssertionError("not compute program");
        float[] pn=GLES30.vertexData.get(ssbo.get(0)),uv=GLES30.vertexData.get(ssbo.get(1));int[] ibo=GLES30.indexData.get(ssbo.get(2));
        if(pn.length!=13975*6||uv.length!=13975*2||ibo.length!=9213*3)throw new AssertionError("borrowed actual buffer sizes");
        float[] world=GLES30.matrix(GLES30.current,"uWorld"),packed=new float[512*36*4];
        for(int tri=0;tri<9213;tri++){
            int a=ibo[tri*3],b=ibo[tri*3+1],c=ibo[tri*3+2];float ux=uv[b*2]-uv[a*2],uy=uv[b*2+1]-uv[a*2+1],vx=uv[c*2]-uv[a*2],vy=uv[c*2+1]-uv[a*2+1],d=ux*vy-uy*vx;
            for(int r=0;r<3;r++){
                float p=transform(pn,a,world,r),e1=transform(pn,b,world,r)-p,e2=transform(pn,c,world,r)-p;
                packed[tri*8+r]=(e1*vy-e2*uy)/d;packed[tri*8+4+r]=(e2*ux-e1*vx)/d;
            }
        }
        GLES30.textureFloats.put(image[0],packed);dispatches++;visible=false;war=false;
    }
    private static float transform(float[] pn,int vertex,float[] m,int r){int i=vertex*6;return ((m[r]*pn[i]+m[4+r]*pn[i+1])+m[8+r]*pn[i+2])+m[12+r];}
    public static void sampled(){if(!visible)throw new AssertionError("table sampled before texture-fetch visibility");events.add("sample");}
}
