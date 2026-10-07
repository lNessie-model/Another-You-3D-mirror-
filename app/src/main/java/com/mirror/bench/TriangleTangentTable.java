package com.mirror.bench;

import java.nio.*;

/** Diagnostic-only immutable topology and bounded transactional world-space dP/dUV table.
 * Java 17 float expressions are binary32; GPU vertex multiply/derivatives need device comparison.
 */
final class TriangleTangentTable {
    private static final int WIDTH=512,MAX_TRIANGLES=65_536;
    private final int vertices,triangles,height;
    private final int[] indices;
    private final float[] uv,positions,scratch,key=new float[16];
    private final FloatBuffer packed;
    private long revision,rebuilds;
    private boolean ready;
    TriangleTangentTable(int vertices,IntBuffer index,FloatBuffer coords){
        if(vertices<3||vertices>262_144||index==null||coords==null||index.remaining()==0||index.remaining()%3!=0||index.remaining()/3>MAX_TRIANGLES||coords.remaining()!=vertices*2)
            throw new IllegalArgumentException("Bounded triangle topology and complete UV required");
        this.vertices=vertices;triangles=index.remaining()/3;height=(triangles*2+WIDTH-1)/WIDTH;
        indices=new int[index.remaining()];index.duplicate().get(indices);uv=new float[vertices*2];coords.duplicate().get(uv);
        for(float value:uv)finite(value);
        for(int i:indices)if(i<0||i>=vertices)throw new IllegalArgumentException("Triangle index outside vertices");
        for(int tri=0;tri<triangles;tri++){float d=det(tri);if(!Float.isFinite(d)||d==0)throw new IllegalArgumentException("Degenerate/nonfinite UV triangle "+tri);}
        positions=new float[vertices*3];scratch=new float[WIDTH*height*4];
        packed=ByteBuffer.allocateDirect(scratch.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }
    int width(){return WIDTH;}int height(){return height;}int triangles(){return triangles;}long rebuilds(){return rebuilds;}
    FloatBuffer packed(){if(!ready)throw new IllegalStateException("Table not built");FloatBuffer result=packed.asReadOnlyBuffer();result.position(0);return result;}
    boolean update(FloatBuffer pn,float[] world,long revision){
        if(pn==null||pn.remaining()!=vertices*6||world==null||world.length!=16||revision<0)throw new IllegalArgumentException("Actual uploaded PN, affine world and revision required");
        for(float f:world)finite(f);
        if(world[3]!=0||world[7]!=0||world[11]!=0||world[15]!=1)throw new IllegalArgumentException("Affine world required");
        boolean same=ready&&this.revision==revision;
        for(int i=0;i<16;i++)same&=Float.floatToRawIntBits(world[i])==Float.floatToRawIntBits(key[i]);
        if(same)return false;
        int base=pn.position();
        for(int i=0;i<vertices;i++){
            float x=pn.get(base+i*6),y=pn.get(base+i*6+1),z=pn.get(base+i*6+2);finite(x);finite(y);finite(z);
            for(int r=0;r<3;r++)positions[i*3+r]=finite(((world[r]*x+world[4+r]*y)+world[8+r]*z)+world[12+r]);
        }
        for(int tri=0;tri<triangles;tri++){
            int i0=indices[tri*3],i1=indices[tri*3+1],i2=indices[tri*3+2];
            float ux=uv[i1*2]-uv[i0*2],uy=uv[i1*2+1]-uv[i0*2+1],vx=uv[i2*2]-uv[i0*2],vy=uv[i2*2+1]-uv[i0*2+1],d=det(tri);
            for(int r=0;r<3;r++){
                float e1=positions[i1*3+r]-positions[i0*3+r],e2=positions[i2*3+r]-positions[i0*3+r];
                scratch[tri*8+r]=finite((e1*vy-e2*uy)/d);
                scratch[tri*8+4+r]=finite((e2*ux-e1*vx)/d);
            }
        }
        packed.position(0);packed.put(scratch);packed.position(0);
        System.arraycopy(world,0,key,0,16);this.revision=revision;ready=true;rebuilds++;return true;
    }
    private float det(int tri){int a=indices[tri*3]*2,b=indices[tri*3+1]*2,c=indices[tri*3+2]*2;return (uv[b]-uv[a])*(uv[c+1]-uv[a+1])-(uv[b+1]-uv[a+1])*(uv[c]-uv[a]);}
    private static float finite(float x){if(!Float.isFinite(x))throw new IllegalArgumentException("Nonfinite tangent source/result");return x;}
}
