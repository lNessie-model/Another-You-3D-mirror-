package com.mirror.bench;

import android.opengl.GLES30;
import android.opengl.GLES31;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.json.JSONObject;

/** One GL owner. Borrows actual uploaded vertex/UV/index buffers; never reads a worker lease.
 * Dispatch must occur before the multiview render pass. No CPU readback/wait or GPU timer.
 */
final class GpuTriangleTangentTable implements AutoCloseable {
    static final int WIDTH=512,HEIGHT=36,TRIANGLES=9213,VERTICES=13975;
    private final Thread owner=Thread.currentThread();
    private final int pnBuffer,uvBuffer,indexBuffer;
    private final int[] scratch=new int[1],imageState=new int[6];
    private static final int[] IMAGE_KEYS={GLES31.GL_IMAGE_BINDING_NAME,GLES31.GL_IMAGE_BINDING_LEVEL,
        GLES31.GL_IMAGE_BINDING_LAYERED,GLES31.GL_IMAGE_BINDING_LAYER,GLES31.GL_IMAGE_BINDING_ACCESS,GLES31.GL_IMAGE_BINDING_FORMAT};
    private final float[] key=new float[16];
    private final double positionBound,uvFactor;
    private final Object metrics=new Object();
    private int program,worldLocation;
    private volatile int texture;
    private volatile long preparedRevision=-1,uploadedRevision=-1;
    private volatile String error="";
    private volatile boolean closed;
    private boolean bound,ready;
    private long dispatches,reuses;
    private double lastSubmitMs,meanSubmitMs;

    GpuTriangleTangentTable(AvatarAsset.Primitive source,int pnBuffer,int uvBuffer,int indexBuffer){
        if(source.vertexCount()!=VERTICES||source.indices().remaining()!=TRIANGLES*3||pnBuffer<=0||uvBuffer<=0||indexBuffer<=0
                ||pnBuffer==uvBuffer||pnBuffer==indexBuffer||uvBuffer==indexBuffer)
            throw new IllegalArgumentException("Exact primary topology and distinct uploaded buffers required");
        this.pnBuffer=pnBuffer;this.uvBuffer=uvBuffer;this.indexBuffer=indexBuffer;
        double[] bounds=sourceBounds(source);positionBound=bounds[0];uvFactor=bounds[1];
        if(integer(GLES30.GL_MAJOR_VERSION)<3||(integer(GLES30.GL_MAJOR_VERSION)==3&&integer(GLES30.GL_MINOR_VERSION)<2))
            throw new IllegalStateException("GPU tangent requires actual GLES3.2");
        if(integer(GLES31.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS)<3||integer(GLES31.GL_MAX_COMPUTE_IMAGE_UNIFORMS)<1)
            throw new IllegalStateException("Insufficient compute storage/image resources");
        validateBuffers();
        int active=integer(GLES30.GL_ACTIVE_TEXTURE);Throwable primary=null;boolean changed=false;
        try{
            check("before GPU tangent construction");
            GLES30.glActiveTexture(GLES30.GL_TEXTURE3);
            if(integer(GLES30.GL_TEXTURE_BINDING_2D)!=0||integer(GLES30.GL_SAMPLER_BINDING)!=0)
                throw new IllegalStateException("GPU tangent requires unused sampler/texture unit3");
            program=compile();worldLocation=GLES30.glGetUniformLocation(program,"uWorld");
            if(worldLocation<0)throw new IllegalStateException("Missing compute world uniform");
            GLES30.glGenTextures(1,scratch,0);texture=scratch[0];if(texture==0)throw new IllegalStateException("No GPU table texture");
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture);changed=true;
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D,1,GLES30.GL_RGBA32F,WIDTH,HEIGHT);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_NEAREST);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_NEAREST);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
            check("GPU tangent storage");
        }catch(RuntimeException|Error failure){primary=failure;fail(failure);try{close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
        finally{
            try{if(changed)GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0);GLES30.glActiveTexture(active);}
            catch(RuntimeException|Error cleanup){if(primary!=null)primary.addSuppressed(cleanup);else{fail(cleanup);try{close();}catch(RuntimeException|Error suppressed){cleanup.addSuppressed(suppressed);}throw cleanup;}}
        }
    }
    /** Called only after glBufferSubData + checkGl succeeded for this exact primary VBO. */
    void uploaded(long revision){requireOwner();if(revision<0)throw new IllegalArgumentException("Uploaded revision required");uploadedRevision=revision;}
    void prepare(float[] world,long revision){
        requireOwner();long start=System.nanoTime();
        try{
            validateWorld(world);
            if(revision<0||revision!=uploadedRevision)throw new IllegalStateException("Compute revision differs from successfully uploaded primary");
            if(matches(world,revision)){synchronized(metrics){reuses++;}return;}
            int oldProgram=integer(GLES30.GL_CURRENT_PROGRAM),oldSsbo=integer(GLES31.GL_SHADER_STORAGE_BUFFER_BINDING);
            for(int i=0;i<3;i++)if(indexed(GLES31.GL_SHADER_STORAGE_BUFFER_BINDING,i)!=0)
                throw new IllegalStateException("Compute reserved SSBO slots0..2 already bound");
            for(int i=0;i<IMAGE_KEYS.length;i++)imageState[i]=indexed(IMAGE_KEYS[i],0);
            if(imageState[0]!=0)throw new IllegalStateException("Compute reserved image unit0 already bound");
            check("before GPU tangent dispatch");
            Throwable primary=null;
            try{
                GLES30.glUseProgram(program);GLES30.glUniformMatrix4fv(worldLocation,1,false,world,0);
                GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER,0,pnBuffer);
                GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER,1,uvBuffer);
                GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER,2,indexBuffer);
                GLES31.glBindImageTexture(0,texture,0,false,0,GLES31.GL_WRITE_ONLY,GLES30.GL_RGBA32F);
                // WAR: the previous frame may still be sampling this same fixed image.
                GLES31.glMemoryBarrier(GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
                GLES31.glDispatchCompute((TRIANGLES+63)/64,1,1);
                GLES31.glMemoryBarrier(GLES31.GL_TEXTURE_FETCH_BARRIER_BIT);
                check("GPU tangent dispatch/fetch visibility");
            }catch(RuntimeException|Error failure){primary=failure;throw failure;}
            finally{
                Throwable cleanup=null;
                try{GLES31.glBindImageTexture(0,imageState[0],imageState[1],imageState[2]!=0,imageState[3],imageState[4],imageState[5]);}catch(RuntimeException|Error e){cleanup=e;}
                for(int i=0;i<3;i++)try{GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER,i,0);}catch(RuntimeException|Error e){cleanup=append(cleanup,e);}
                try{GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER,oldSsbo);}catch(RuntimeException|Error e){cleanup=append(cleanup,e);}
                try{GLES30.glUseProgram(oldProgram);}catch(RuntimeException|Error e){cleanup=append(cleanup,e);}
                try{check("GPU tangent restore");}catch(RuntimeException|Error e){cleanup=append(cleanup,e);}
                if(cleanup!=null){if(primary!=null)primary.addSuppressed(cleanup);else rethrow(cleanup);}
            }
            System.arraycopy(world,0,key,0,16);preparedRevision=revision;ready=true;
            synchronized(metrics){dispatches++;lastSubmitMs=(System.nanoTime()-start)/1e6;meanSubmitMs+=(lastSubmitMs-meanSubmitMs)/dispatches;}
        }catch(RuntimeException|Error failure){fail(failure);throw failure;}
    }
    void requirePrepared(float[] world,long revision){requireOwner();if(!matches(world,revision)||revision!=uploadedRevision)throw new IllegalStateException("GPU table was not prepared for displayed PN/world before drawing");}
    private boolean matches(float[] world,long revision){if(!ready||world==null||world.length!=16||preparedRevision!=revision)return false;for(int i=0;i<16;i++)if(Float.floatToRawIntBits(world[i])!=Float.floatToRawIntBits(key[i]))return false;return true;}
    void bind(){requireOwner();if(!ready)throw new IllegalStateException("GPU table not prepared");GLES30.glActiveTexture(GLES30.GL_TEXTURE3);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture);bound=true;GLES30.glActiveTexture(GLES30.GL_TEXTURE0);}
    void unbind(){if(Thread.currentThread()!=owner)throw new IllegalStateException("GPU tangent owner differs");if(bound){GLES30.glActiveTexture(GLES30.GL_TEXTURE3);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0);bound=false;GLES30.glActiveTexture(GLES30.GL_TEXTURE0);}}
    int texture(){return texture;}boolean healthy(){return !closed&&error.isEmpty();}
    void fail(Throwable failure){if(error.isEmpty())error=failure.toString();ready=false;}
    private void requireOwner(){if(Thread.currentThread()!=owner||!healthy())throw new IllegalStateException("GPU tangent owner failed/closed/differs: "+error);}
    JSONObject status()throws Exception{synchronized(metrics){return new JSONObject().put("requested",true).put("backend","gles_compute_triangle_tb")
        .put("uploaded_revision",uploadedRevision).put("prepared_revision",preparedRevision).put("dispatches",dispatches).put("reuses",reuses)
        .put("table_texture",texture).put("compute_program",program).put("dispatch_groups",(TRIANGLES+63)/64).put("triangles",TRIANGLES)
        .put("table_width",WIDTH).put("table_height",HEIGHT).put("vertices",VERTICES)
        .put("cpu_submit_last_ms",lastSubmitMs).put("cpu_submit_mean_ms",meanSubmitMs)
        .put("timing_scope","GL-owner CPU submission wall time including API waits; not GPU execution time")
        .put("logical_gpu_table_bytes",(long)WIDTH*HEIGHT*16).put("owned_pn_snapshot_bytes",0).put("cpu_table_upload_bytes",0)
        .put("logical_bytes_scope","Sized-format payload only, not measured GPU residency")
        .put("finite_scope","Exact asset morph bounds plus affine-world arithmetic envelope; driver numeric validation is a separate diagnostic gate")
        .put("error",error).put("closed",closed);}}
    @Override public void close(){
        if(Thread.currentThread()!=owner)throw new IllegalStateException("GPU tangent close owner differs");if(closed)return;closed=true;ready=false;
        Throwable failure=null;try{unbind();}catch(RuntimeException|Error e){failure=e;}
        int t=texture;texture=0;if(t!=0)try{scratch[0]=t;GLES30.glDeleteTextures(1,scratch,0);}catch(RuntimeException|Error e){failure=append(failure,e);}
        int p=program;program=0;if(p!=0)try{GLES30.glDeleteProgram(p);}catch(RuntimeException|Error e){failure=append(failure,e);}
        try{check("GPU tangent close");}catch(RuntimeException|Error e){failure=append(failure,e);}
        if(failure!=null)rethrow(failure);
    }
    private void validateWorld(float[] world){
        if(world==null||world.length!=16)throw new IllegalArgumentException("Affine world required");
        for(float x:world)if(!Float.isFinite(x))throw new IllegalArgumentException("Nonfinite world");
        if(world[3]!=0||world[7]!=0||world[11]!=0||world[15]!=1)throw new IllegalArgumentException("Affine world required");
        double bound=0;for(int r=0;r<3;r++)bound=Math.max(bound,(Math.abs((double)world[r])+Math.abs((double)world[4+r])+Math.abs((double)world[8+r]))*positionBound+Math.abs((double)world[12+r]));
        if(bound>1e8||2*bound*uvFactor>1e12)throw new IllegalArgumentException("World exceeds conservative finite tangent envelope");
    }
    private static double[] sourceBounds(AvatarAsset.Primitive source){
        FloatBuffer positions=source.positions();double[] bounds=new double[VERTICES*3];
        for(int i=0;i<bounds.length;i++)bounds[i]=Math.abs((double)positions.get(i));
        for(var morph:source.morphs()){FloatBuffer delta=morph.positions();if(delta!=null)for(int i=0;i<bounds.length;i++)bounds[i]+=Math.abs((double)delta.get(i));}
        double bound=0;for(double x:bounds){if(!Double.isFinite(x))throw new IllegalArgumentException("Nonfinite source bound");bound=Math.max(bound,x);}
        IntBuffer indices=source.indices();FloatBuffer uv=source.texCoords();if(uv==null||uv.remaining()!=VERTICES*2)throw new IllegalArgumentException("Complete UV required");
        double factor=0;
        for(int tri=0;tri<TRIANGLES;tri++){
            int a=indices.get(tri*3),b=indices.get(tri*3+1),c=indices.get(tri*3+2);
            if(a<0||b<0||c<0||a>=VERTICES||b>=VERTICES||c>=VERTICES)throw new IllegalArgumentException("Invalid triangle index");
            float ux=uv.get(b*2)-uv.get(a*2),uy=uv.get(b*2+1)-uv.get(a*2+1),vx=uv.get(c*2)-uv.get(a*2),vy=uv.get(c*2+1)-uv.get(a*2+1),det=ux*vy-uy*vx;
            if(!Float.isFinite(det)||det==0)throw new IllegalArgumentException("Invalid triangle UV determinant");
            double f=Math.max(Math.abs((double)uy)+Math.abs((double)vy),Math.abs((double)ux)+Math.abs((double)vx))/Math.abs((double)det);
            if(!Double.isFinite(f))throw new IllegalArgumentException("Invalid UV bound");factor=Math.max(factor,f);
        }
        return new double[]{bound,factor};
    }
    private int integer(int name){GLES30.glGetIntegerv(name,scratch,0);return scratch[0];}
    private void validateBuffers(){
        int previous=integer(GLES31.GL_SHADER_STORAGE_BUFFER_BINDING);Throwable primary=null;
        try{bufferSize(pnBuffer,VERTICES*24);bufferSize(uvBuffer,VERTICES*8);bufferSize(indexBuffer,TRIANGLES*12);check("GPU tangent borrowed storage");}
        catch(RuntimeException|Error failure){primary=failure;throw failure;}
        finally{try{GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER,previous);check("GPU tangent borrowed storage restore");}
            catch(RuntimeException|Error cleanup){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}}
    }
    private void bufferSize(int buffer,int bytes){GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER,buffer);GLES30.glGetBufferParameteriv(GLES31.GL_SHADER_STORAGE_BUFFER,GLES30.GL_BUFFER_SIZE,scratch,0);if(scratch[0]!=bytes)throw new IllegalStateException("Actual borrowed buffer size differs");}
    private int indexed(int name,int i){GLES31.glGetIntegeri_v(name,i,scratch,0);return scratch[0];}
    private static void check(String label){int e=GLES30.glGetError();if(e!=GLES30.GL_NO_ERROR)throw new IllegalStateException(label+": GL 0x"+Integer.toHexString(e));}
    private static Throwable append(Throwable first,Throwable next){if(first==null)return next;first.addSuppressed(next);return first;}
    private static void rethrow(Throwable failure){if(failure instanceof Error e)throw e;if(failure instanceof RuntimeException r)throw r;throw new IllegalStateException(failure);}
    private int compile(){
        int shader=0,made=0;Throwable primary=null;
        try{shader=GLES30.glCreateShader(GLES31.GL_COMPUTE_SHADER);GLES30.glShaderSource(shader,SOURCE);GLES30.glCompileShader(shader);GLES30.glGetShaderiv(shader,GLES30.GL_COMPILE_STATUS,scratch,0);
            if(scratch[0]==0)throw new IllegalStateException("GPU tangent compute: "+GLES30.glGetShaderInfoLog(shader));
            made=GLES30.glCreateProgram();GLES30.glAttachShader(made,shader);GLES30.glLinkProgram(made);GLES30.glGetProgramiv(made,GLES30.GL_LINK_STATUS,scratch,0);
            if(scratch[0]==0)throw new IllegalStateException("GPU tangent link: "+GLES30.glGetProgramInfoLog(made));return made;
        }catch(RuntimeException|Error failure){primary=failure;if(made!=0)try{GLES30.glDeleteProgram(made);}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
        finally{if(shader!=0)try{GLES30.glDeleteShader(shader);}catch(RuntimeException|Error cleanup){if(primary!=null)primary.addSuppressed(cleanup);else{if(made!=0)try{GLES30.glDeleteProgram(made);}catch(RuntimeException|Error suppressed){cleanup.addSuppressed(suppressed);}throw cleanup;}}}
    }
    static final String SOURCE="""
        #version 320 es
        precision highp float;precision highp int;
        layout(local_size_x=64) in;
        layout(std430,binding=0) readonly buffer Positions {float pn[];};
        layout(std430,binding=1) readonly buffer UVs {float uv[];};
        layout(std430,binding=2) readonly buffer Indices {uint indices[];};
        layout(rgba32f,binding=0) writeonly uniform highp image2D tangentTable;
        uniform mat4 uWorld;
        vec3 worldPosition(uint vertex){
            float x=pn[vertex*6u],y=pn[vertex*6u+1u],z=pn[vertex*6u+2u];
            precise vec3 p;
            p=((uWorld[0].xyz*x+uWorld[1].xyz*y)+uWorld[2].xyz*z)+uWorld[3].xyz;
            return p;
        }
        void main(){
            uint triangle=gl_GlobalInvocationID.x;
            highp int texel=int(triangle)*2;highp ivec2 coord=ivec2(texel%512,texel/512);
            // 144x64 invocations cover all9216 storage pairs, including the three padding pairs.
            if(triangle>=9213u){imageStore(tangentTable,coord,vec4(0.0));imageStore(tangentTable,coord+ivec2(1,0),vec4(0.0));return;}
            uint a=indices[triangle*3u],b=indices[triangle*3u+1u],c=indices[triangle*3u+2u];
            precise vec2 du=vec2(uv[b*2u],uv[b*2u+1u])-vec2(uv[a*2u],uv[a*2u+1u]);
            precise vec2 dv=vec2(uv[c*2u],uv[c*2u+1u])-vec2(uv[a*2u],uv[a*2u+1u]);
            precise float d=du.x*dv.y-du.y*dv.x;
            vec3 p0=worldPosition(a);precise vec3 e1=worldPosition(b)-p0,e2=worldPosition(c)-p0;
            precise vec3 t=(e1*dv.y-e2*du.y)/d,basisB=(e2*du.x-e1*dv.x)/d;
            imageStore(tangentTable,coord,vec4(t,0.0));imageStore(tangentTable,coord+ivec2(1,0),vec4(basisB,0.0));
        }
        """;
}
