package com.mirror.bench;

import android.opengl.GLES30;
import android.opengl.Matrix;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.json.JSONObject;

/** Opt-in GL-thread batch renderer. Only immutable layout data is packed on the CPU. */
final class AvatarBatchGpu {
    private final AvatarBatchLayout layout;
    private final int[] buffers; // dynamic xyz/nxyz, original COLOR_0, integer id, index, optional static UV
    private final boolean atlas;
    private final boolean pbr;
    private final float[] pbrParams;
    private Program single,multiview;
    private Program comparisonSingle,comparisonMultiview;
    private final boolean pbrFastMath;
    private boolean selectedFastMath;
    private final float[] worlds,normals,colors,params,fit=new float[16],inverse=new float[16];
    private final int vertexLimit,fragmentLimit,varyingLimit,requiredVertexVectors;
    private boolean disposed;
    AvatarBatchGpu(AvatarAsset asset,AvatarRig rig,boolean useMultiview) {
        this(asset,rig,useMultiview,false);
    }
    AvatarBatchGpu(AvatarAsset asset,AvatarRig rig,boolean useMultiview,boolean pbrFastMath) {
        this.pbrFastMath=pbrFastMath;selectedFastMath=pbrFastMath;
        atlas=asset.albedoAtlas()!=null;pbr=asset.normalMap()!=null;buffers=new int[atlas?5:4];
        boolean[] active=new boolean[asset.nodes().size()];for(int n=0;n<active.length;n++)active[n]=rig.activeNode(n);
        layout=new AvatarBatchLayout(asset,active);int count=layout.entries().size();
        worlds=new float[count*16];normals=new float[count*9];colors=new float[count*4];params=new float[count*4];
        pbrParams=pbr?new float[count*4]:null;
        int[] value=new int[1];GLES30.glGetIntegerv(GLES30.GL_MAX_VERTEX_UNIFORM_VECTORS,value,0);vertexLimit=value[0];
        GLES30.glGetIntegerv(GLES30.GL_MAX_FRAGMENT_UNIFORM_VECTORS,value,0);fragmentLimit=value[0];
        GLES30.glGetIntegerv(GLES30.GL_MAX_VARYING_VECTORS,value,0);varyingLimit=value[0];
        requiredVertexVectors=count*(pbr?10:9)+(useMultiview?16:4);
        if(vertexLimit<requiredVertexVectors)throw new IllegalStateException("Debug avatar batch exceeds vertex uniform limits");
        if(varyingLimit<(pbr?7:atlas?6:5))throw new IllegalStateException("Debug avatar batch exceeds varying limits");
        GLES30.glGetIntegerv(GLES30.GL_MAX_VERTEX_ATTRIBS,value,0);
        if(value[0]<(atlas?5:4))throw new IllegalStateException("Debug avatar batch exceeds vertex attribute limits");
        for(int i=0;i<count;i++) {
            var e=layout.entries().get(i);var primitive=asset.meshes().get(e.mesh).primitives().get(e.primitive);
            var material=asset.materials().get(primitive.materialIndex());material.baseColor().get(colors,i*4,4);
            params[i*4]=material.unlit()?1:0;params[i*4+1]=material.roughness();
            params[i*4+2]=material.textured()?1:0;
            if(pbr){pbrParams[i*4]=material.normalScale();pbrParams[i*4+1]=material.occlusionStrength();pbrParams[i*4+2]=material.metallic();pbrParams[i*4+3]=material.pbrMaps()?1:0;}
        }
        try {
            single=new Program(false,count,atlas,pbr,pbrFastMath);multiview=useMultiview?new Program(true,count,atlas,pbr,pbrFastMath):null;
            GLES30.glGenBuffers(buffers.length,buffers,0);
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,buffers[0]);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,layout.vertexCount()*24,null,GLES30.GL_DYNAMIC_DRAW);
            FloatBuffer rgba=direct(layout.colors());GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,buffers[1]);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,rgba.capacity()*4,rgba,GLES30.GL_STATIC_DRAW);
            IntBuffer ids=direct(layout.ids());GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,buffers[2]);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,ids.capacity()*4,ids,GLES30.GL_STATIC_DRAW);
            IntBuffer indices=direct(layout.indices());GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER,buffers[3]);
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER,indices.capacity()*4,indices,GLES30.GL_STATIC_DRAW);
            if(atlas){FloatBuffer uv=direct(layout.uvs());GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,buffers[4]);
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,uv.capacity()*4,uv,GLES30.GL_STATIC_DRAW);}
            checkGl("batch construction");
        } catch(RuntimeException|Error failure){dispose();throw failure;}
    }
    /** Already-computed floats go directly into subranges; no per-frame CPU repacking. */
    long upload(int mesh,int primitive,FloatBuffer source) {
        long bytes=0;
        for(int i=0;i<layout.entries().size();i++) {
            var e=layout.entries().get(i);if(e.mesh!=mesh||e.primitive!=primitive)continue;
            if(source.capacity()!=e.vertexCount*6)throw new IllegalArgumentException("Batch pose buffer count mismatch");
            source.position(0);source.limit(source.capacity());GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,buffers[0]);
            int size=e.vertexCount*24;GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER,e.firstVertex*24,size,source);bytes+=size;
        }
        return bytes;
    }
    void draw(float[] viewProjections,int viewCount,float aspect,AvatarFraming framing,float[] displayedWorlds) {
        draw(viewProjections,viewCount,aspect,framing,displayedWorlds,null);
    }
    void draw(float[] viewProjections,int viewCount,float aspect,AvatarFraming framing,float[] displayedWorlds,float[] suppliedFit) {
        if(disposed)throw new IllegalStateException("Disposed avatar batch");
        if((viewCount!=1&&viewCount!=4)||viewProjections.length<viewCount*16)throw new IllegalArgumentException("Invalid batch view group");
        Program program=selectedProgram(viewCount);
        if(suppliedFit==null)framing.copyFitMatrix(aspect,fit);else {
            if(suppliedFit.length!=16)throw new IllegalArgumentException("Fit matrix requires sixteen entries");
            System.arraycopy(suppliedFit,0,fit,0,16);
        }
        int count=layout.entries().size();
        for(int i=0;i<count;i++) {
            var e=layout.entries().get(i);Matrix.multiplyMM(worlds,i*16,fit,0,displayedWorlds,e.node*16);
            if(!Matrix.invertM(inverse,0,worlds,i*16))throw new IllegalStateException("Singular avatar batch node transform");
            for(int col=0;col<3;col++)for(int row=0;row<3;row++)normals[i*9+col*3+row]=inverse[row*4+col];
        }
        GLES30.glUseProgram(program.id);GLES30.glUniformMatrix4fv(program.vp,viewCount,false,viewProjections,0);
        GLES30.glUniformMatrix4fv(program.world,count,false,worlds,0);GLES30.glUniformMatrix3fv(program.normal,count,false,normals,0);
        GLES30.glUniform4fv(program.color,count,colors,0);GLES30.glUniform4fv(program.params,count,params,0);
        if(atlas)GLES30.glUniform1i(program.sampler,0);
        if(pbr){GLES30.glUniform4fv(program.pbr,count,pbrParams,0);GLES30.glUniform1i(program.normalSampler,1);GLES30.glUniform1i(program.ormSampler,2);}
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);GLES30.glEnable(GLES30.GL_CULL_FACE);
        GLES30.glFrontFace(GLES30.GL_CCW);GLES30.glCullFace(GLES30.GL_BACK);GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,buffers[0]);
        GLES30.glEnableVertexAttribArray(0);GLES30.glVertexAttribPointer(0,3,GLES30.GL_FLOAT,false,24,0);
        GLES30.glEnableVertexAttribArray(1);GLES30.glVertexAttribPointer(1,3,GLES30.GL_FLOAT,false,24,12);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,buffers[1]);
        GLES30.glEnableVertexAttribArray(2);GLES30.glVertexAttribPointer(2,4,GLES30.GL_FLOAT,false,16,0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,buffers[2]);
        GLES30.glEnableVertexAttribArray(3);GLES30.glVertexAttribIPointer(3,1,GLES30.GL_UNSIGNED_INT,4,0);
        if(atlas){GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,buffers[4]);GLES30.glEnableVertexAttribArray(4);GLES30.glVertexAttribPointer(4,2,GLES30.GL_FLOAT,false,8,0);}
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER,buffers[3]);
        GLES30.glDrawElements(GLES30.GL_TRIANGLES,layout.indexCount(),GLES30.GL_UNSIGNED_INT,0);
        for(int i=0;i<(atlas?5:4);i++)GLES30.glDisableVertexAttribArray(i);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0);GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER,0);
    }
    JSONObject status()throws Exception {
        return new JSONObject().put("draw_items",layout.entries().size()).put("draw_calls_per_view_group",1)
                .put("vertices",layout.vertexCount()).put("indices",layout.indexCount()).put("extra_id_bytes",layout.vertexCount()*4)
                .put("max_vertex_uniform_vectors",vertexLimit).put("required_vertex_uniform_vectors",requiredVertexVectors)
                .put("max_fragment_uniform_vectors",fragmentLimit).put("required_fragment_uniform_vectors",0)
                .put("max_varying_vectors",varyingLimit).put("required_varying_vectors",pbr?7:atlas?6:5)
                .put("albedo_atlas",atlas).put("static_uv_bytes",atlas?layout.vertexCount()*8:0)
                .put("material_selection_stage","vertex_flat")
                .put("pbr_fast_math_requested",pbrFastMath).put("pbr_shader_variant",AvatarPbrShaderVariant.name(pbr,selectedFastMath));
    }
    /** Only the owning current EGL context may delete these names. */
    void dispose() {
        if(disposed)return;disposed=true;GLES30.glDeleteBuffers(buffers.length,buffers,0);
        endPbrComparison();
        if(single!=null){GLES30.glDeleteProgram(single.id);single=null;}
        if(multiview!=null){GLES30.glDeleteProgram(multiview.id);multiview=null;}
    }
    static String vertexSource(boolean multiview,int count) {
        String source=AvatarGpuScene.VERTEX.replace("layout(location=2) in vec4 aColor;",
                "layout(location=2) in vec4 aColor;\nlayout(location=3) in highp uint aPrimitive;\nflat out highp vec4 vMaterialColor;\nflat out highp vec2 vMaterialParams;\nuniform vec4 uColors["+count+"];uniform vec4 uParams["+count+"];")
                .replace("uniform mat4 uWorld;","uniform mat4 uWorld["+count+"];")
                .replace("uniform mat3 uNormal;","uniform mat3 uNormal["+count+"];")
                .replace("void main(){","void main(){vMaterialColor=uColors[int(aPrimitive)];vMaterialParams=uParams[int(aPrimitive)].xy;")
                .replace("uWorld*","uWorld[int(aPrimitive)]*").replace("uNormal*","uNormal[int(aPrimitive)]*");
        if(multiview)source=source.replace("#version 300 es","#version 300 es\n#extension GL_OVR_multiview2 : require\nlayout(num_views=4) in;")
                .replace("uniform mat4 uViewProjection;","uniform mat4 uViewProjection[4];")
                .replace("uViewProjection*","uViewProjection[gl_ViewID_OVR]*");
        return source;
    }
    static String fragmentSource(int count) {
        return AvatarGpuScene.FRAGMENT.replace("uniform vec4 uColor;uniform float uUnlit;uniform float uRoughness;",
                "flat in highp vec4 vMaterialColor;\nflat in highp vec2 vMaterialParams;")
                .replace("void main(){","void main(){vec4 uColor=vMaterialColor;float uUnlit=vMaterialParams.x;float uRoughness=vMaterialParams.y;");
    }
    static String vertexSource(boolean multiview,int count,boolean atlas){
        String source=vertexSource(multiview,count);if(!atlas)return source;
        return AvatarGpuScene.atlasVertex(source).replace("flat out highp vec2 vMaterialParams;","flat out highp vec3 vMaterialParams;")
                .replace("vMaterialParams=uParams[int(aPrimitive)].xy;","vMaterialParams=uParams[int(aPrimitive)].xyz;");
    }
    static String fragmentSource(int count,boolean atlas){
        String source=fragmentSource(count);if(!atlas)return source;
        return AvatarGpuScene.atlasFragment(source).replace("flat in highp vec2 vMaterialParams;","flat in highp vec3 vMaterialParams;")
                .replace("uniform float uUseTexture;","").replace("uUseTexture>0.5","vMaterialParams.z>0.5");
    }
    static String vertexSource(boolean multiview,int count,boolean atlas,boolean pbr){
        String source=vertexSource(multiview,count,atlas);if(!pbr)return source;
        return source.replace("void main(){","flat out highp vec4 vPbrParams;\nuniform vec4 uPbrValues["+count+"];\nvoid main(){vPbrParams=uPbrValues[int(aPrimitive)];");
    }
    static String fragmentSource(int count,boolean atlas,boolean pbr){
        return fragmentSource(count,atlas,pbr,false);
    }
    static String fragmentSource(int count,boolean atlas,boolean pbr,boolean fastMath){
        if(!pbr)return fragmentSource(count,atlas);
        return AvatarPbrShaderVariant.fragment(fastMath).replace("uniform vec4 uColor;uniform float uUnlit;uniform float uRoughness;","flat in highp vec4 vMaterialColor;flat in highp vec3 vMaterialParams;")
                .replace("uniform float uUseTexture;","").replace("uniform vec4 uPbrParams;","flat in highp vec4 vPbrParams;")
                .replace("uPbrParams","vPbrParams").replace("uUseTexture>0.5","vMaterialParams.z>0.5")
                .replace("void main(){","void main(){vec4 uColor=vMaterialColor;float uUnlit=vMaterialParams.x;float uRoughness=vMaterialParams.y;");
    }
    private static final class Program {
        final int id,vp,world,normal,color,params,sampler,pbr,normalSampler,ormSampler;
        Program(boolean multiview,int count,boolean atlas,boolean hasPbr,boolean fastMath) {
            int vs=0,fs=0,created=0;
            try {
                vs=AvatarGpuScene.shader(GLES30.GL_VERTEX_SHADER,vertexSource(multiview,count,atlas,hasPbr));
                fs=AvatarGpuScene.shader(GLES30.GL_FRAGMENT_SHADER,fragmentSource(count,atlas,hasPbr,fastMath));
                created=GLES30.glCreateProgram();GLES30.glAttachShader(created,vs);GLES30.glAttachShader(created,fs);GLES30.glLinkProgram(created);
                int[] ok=new int[1];GLES30.glGetProgramiv(created,GLES30.GL_LINK_STATUS,ok,0);
                if(ok[0]==0)throw new IllegalStateException("Avatar batch program: "+GLES30.glGetProgramInfoLog(created));
                id=created;vp=location(id,"uViewProjection");world=location(id,"uWorld[0]");normal=location(id,"uNormal[0]");
                color=location(id,"uColors[0]");params=location(id,"uParams[0]");
                sampler=atlas?location(id,"uAtlas"):-1;
                pbr=hasPbr?location(id,"uPbrValues[0]"):-1;normalSampler=hasPbr?location(id,"uNormalMap"):-1;ormSampler=hasPbr?location(id,"uOrmMap"):-1;
            } catch(RuntimeException|Error failure){if(created!=0)GLES30.glDeleteProgram(created);throw failure;}
            finally {if(vs!=0)GLES30.glDeleteShader(vs);if(fs!=0)GLES30.glDeleteShader(fs);}
        }
        private static int location(int program,String name){int value=GLES30.glGetUniformLocation(program,name);if(value<0)throw new IllegalStateException("Missing batch uniform "+name);return value;}
    }
    private Program selectedProgram(int viewCount){
        if(viewCount!=1&&viewCount!=4)throw new IllegalArgumentException("Invalid batch view group");
        Program program=selectedFastMath==pbrFastMath?(viewCount==4?multiview:single):(viewCount==4?comparisonMultiview:comparisonSingle);
        if(program==null)throw new IllegalStateException("Selected batch program not initialized");
        return program;
    }
    void beginPbrComparison(){
        if(disposed||!pbr||comparisonSingle!=null)throw new IllegalStateException("Batch PBR comparison unavailable");
        try{
            comparisonSingle=new Program(false,layout.entries().size(),atlas,true,!pbrFastMath);
            if(multiview!=null)comparisonMultiview=new Program(true,layout.entries().size(),atlas,true,!pbrFastMath);
        }catch(RuntimeException|Error failure){try{endPbrComparison();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    void selectPbrVariant(boolean fast){
        if(disposed||comparisonSingle==null)throw new IllegalStateException("Live batch PBR comparison required");
        selectedFastMath=fast;
    }
    int selectedPbrProgramId(int count){return selectedProgram(count).id;}
    String pbrFragmentSha256(boolean fast){return AvatarPbrShaderVariant.sha256(fragmentSource(layout.entries().size(),atlas,pbr,fast));}
    void endPbrComparison(){
        selectedFastMath=pbrFastMath;
        Program a=comparisonSingle,b=comparisonMultiview;comparisonSingle=null;comparisonMultiview=null;
        ResourceCleanup cleanup=new ResourceCleanup(null);
        if(a!=null)cleanup.close("batch PBR single",()->GLES30.glDeleteProgram(a.id));
        if(b!=null)cleanup.close("batch PBR multiview",()->GLES30.glDeleteProgram(b.id));
        Throwable failure=cleanup.failure();
        if(failure instanceof Error)throw (Error)failure;
        if(failure instanceof RuntimeException)throw (RuntimeException)failure;
        if(failure!=null)throw new IllegalStateException(failure);
    }
    private static FloatBuffer direct(FloatBuffer source){var b=ByteBuffer.allocateDirect(source.remaining()*4).order(ByteOrder.nativeOrder()).asFloatBuffer();b.put(source);b.flip();return b;}
    private static IntBuffer direct(IntBuffer source){var b=ByteBuffer.allocateDirect(source.remaining()*4).order(ByteOrder.nativeOrder()).asIntBuffer();b.put(source);b.flip();return b;}
    private static void checkGl(String label){int error=GLES30.glGetError();if(error!=GLES30.GL_NO_ERROR)throw new IllegalStateException(label+" GL error "+error);}
}
