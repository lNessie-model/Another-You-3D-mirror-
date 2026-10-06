package com.mirror.bench;

import android.opengl.GLES30;
import android.opengl.Matrix;
import org.json.JSONArray;
import org.json.JSONObject;

/** Diagnostic ID/footprint draw using the batch's already uploaded geometry and original position expression. */
final class AvatarMaterialCoverageGpu implements AutoCloseable {
    private final AvatarBatchLayout layout;
    private final int[] buffers;
    private final float[] worlds;
    private final int single, multiview;
    private boolean closed;
    AvatarMaterialCoverageGpu(AvatarBatchLayout layout, int[] buffers, boolean hasMultiview) {
        this.layout=layout; this.buffers=buffers; worlds=new float[layout.entries().size()*16];
        var primary=layout.entries().get(0);
        if (primary.mesh!=0 || primary.primitive!=0 || buffers.length!=5 || layout.entries().size()>254)
            throw new IllegalArgumentException("Textured primary Geralt batch required");
        int a=0,b=0;
        try { a=program(false); if(hasMultiview)b=program(true); }
        catch(RuntimeException|Error failure) { if(a!=0)GLES30.glDeleteProgram(a);if(b!=0)GLES30.glDeleteProgram(b);throw failure; }
        single=a;multiview=b;
    }
    void draw(float[] vp,int count,float[] fit,float[] displayedWorlds,int maskTexture) {
        if(closed || (count!=1&&count!=4) || vp.length<count*16 || fit.length!=16 || maskTexture==0)
            throw new IllegalArgumentException("Live diagnostic draw and full matrices/mask required");
        int program=count==4?multiview:single;if(program==0)throw new IllegalStateException("Diagnostic multiview program unavailable");
        for(int i=0;i<layout.entries().size();i++)Matrix.multiplyMM(worlds,i*16,fit,0,displayedWorlds,layout.entries().get(i).node*16);
        GLES30.glUseProgram(program);
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program,"uViewProjection"),count,false,vp,0);
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program,"uWorld[0]"),layout.entries().size(),false,worlds,0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,maskTexture);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"uValidity"),3);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);GLES30.glEnable(GLES30.GL_CULL_FACE);GLES30.glFrontFace(GLES30.GL_CCW);
        GLES30.glCullFace(GLES30.GL_BACK);GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,buffers[0]);GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0,3,GLES30.GL_FLOAT,false,24,0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,buffers[2]);GLES30.glEnableVertexAttribArray(3);
        GLES30.glVertexAttribIPointer(3,1,GLES30.GL_UNSIGNED_INT,4,0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,buffers[4]);GLES30.glEnableVertexAttribArray(4);
        GLES30.glVertexAttribPointer(4,2,GLES30.GL_FLOAT,false,8,0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER,buffers[3]);
        GLES30.glDrawElements(GLES30.GL_TRIANGLES,layout.indexCount(),GLES30.GL_UNSIGNED_INT,0);
        for(int i:new int[]{0,3,4})GLES30.glDisableVertexAttribArray(i);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0);GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER,0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,0);GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        int[] actual=new int[1];GLES30.glGetIntegerv(GLES30.GL_CURRENT_PROGRAM,actual,0);
        if(actual[0]!=program || GLES30.glGetError()!=GLES30.GL_NO_ERROR)throw new IllegalStateException("Diagnostic program/buffer draw failed");
    }
    JSONArray entries() throws Exception {
        JSONArray result=new JSONArray();int i=0;
        for(var e:layout.entries())result.put(new JSONObject().put("id",i++).put("node",e.node).put("mesh",e.mesh).put("primitive",e.primitive));
        return result;
    }
    private int program(boolean mv) {
        String vertex=AvatarBatchGpu.vertexSource(mv,layout.entries().size(),true,true)
                .replace("void main(){","flat out highp uint vItem;\nvoid main(){vItem=aPrimitive;");
        int vs=0,fs=0,id=0;
        try {
            vs=AvatarGpuScene.shader(GLES30.GL_VERTEX_SHADER,vertex);fs=AvatarGpuScene.shader(GLES30.GL_FRAGMENT_SHADER,FRAGMENT);
            id=GLES30.glCreateProgram();GLES30.glAttachShader(id,vs);GLES30.glAttachShader(id,fs);GLES30.glLinkProgram(id);
            int[] ok=new int[1];GLES30.glGetProgramiv(id,GLES30.GL_LINK_STATUS,ok,0);
            if(ok[0]==0)throw new IllegalStateException("Coverage probe link: "+GLES30.glGetProgramInfoLog(id));
            for(String name:new String[]{"uViewProjection","uWorld[0]","uValidity"})
                if(GLES30.glGetUniformLocation(id,name)<0)throw new IllegalStateException("Missing coverage probe uniform "+name);
            return id;
        }catch(RuntimeException|Error failure){if(id!=0)GLES30.glDeleteProgram(id);throw failure;}
        finally{if(vs!=0)GLES30.glDeleteShader(vs);if(fs!=0)GLES30.glDeleteShader(fs);}
    }
    @Override public void close(){if(closed)return;closed=true;GLES30.glDeleteProgram(single);if(multiview!=0)GLES30.glDeleteProgram(multiview);}
    private static final String FRAGMENT="""
        #version 300 es
        precision highp float;
        in vec2 vUV;flat in highp uint vItem;
        uniform highp sampler2D uValidity;
        out vec4 color;
        void main(){
            vec2 dx=dFdx(vUV),dy=dFdy(vUV);
            float estimate=textureGrad(uValidity,vUV,dx,dy).r;
            float footprint=max(length(dx*2048.0),length(dy*2048.0));
            float lod=floor(clamp(log2(max(footprint,0.00000001)),0.0,11.0));
            color=vec4((float(vItem)+1.0)/255.0,(vItem==0u&&estimate>=1.0)?1.0:0.0,lod/255.0,1.0);
        }
        """;
}
