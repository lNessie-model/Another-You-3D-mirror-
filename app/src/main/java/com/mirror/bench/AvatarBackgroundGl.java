package com.mirror.bench;

import android.opengl.GLES30;

/** Experimental GLES driver; owns separate immutable color/depth arrays for every view.
 * Caller owns the framebuffer/viewport and ordinary scene state before restore. No GL calls off the owner thread.
 */
final class AvatarBackgroundGl implements AvatarBackgroundCache.Driver {
    private final AvatarGpuScene scene;
    private final float[] matrices=new float[64];
    private int color,depth,vao,program,baseLocation,colorLocation,depthLocation,width,height;
    private int[] framebuffers=new int[0];
    AvatarBackgroundGl(AvatarGpuScene scene){
        if(scene==null||!scene.hasCacheableStaticBackground())throw new IllegalArgumentException("Eligible static background required");
        this.scene=scene;
    }
    public void allocate(int width,int height,int views,int group){
        check("background allocation entry");this.width=width;this.height=height;
        int[] names=new int[2];GLES30.glGenTextures(2,names,0);color=names[0];depth=names[1];check("background texture names");
        if(color==0||depth==0)throw new IllegalStateException("Zero background texture name");
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        texture(color,GLES30.GL_RGBA8,width,height,views);
        texture(depth,GLES30.GL_DEPTH_COMPONENT16,width,height,views);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY,GLES30.GL_TEXTURE_COMPARE_MODE,GLES30.GL_NONE);
        check("background depth sampling mode");GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,0);
        framebuffers=new int[views/group];GLES30.glGenFramebuffers(framebuffers.length,framebuffers,0);check("background framebuffer names");
        for(int i=0;i<framebuffers.length;i++){
            if(framebuffers[i]==0)throw new IllegalStateException("Zero background framebuffer name");
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,framebuffers[i]);
            if(group==4){MultiviewGl.attach(GLES30.GL_COLOR_ATTACHMENT0,color,i*4,4);MultiviewGl.attach(GLES30.GL_DEPTH_ATTACHMENT,depth,i*4,4);}
            else {GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,color,0,i);GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER,GLES30.GL_DEPTH_ATTACHMENT,depth,0,i);}
            check("background attachment "+i);
            if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Incomplete background framebuffer "+i);
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);
        int[] name={0};GLES30.glGenVertexArrays(1,name,0);vao=name[0];check("background VAO name");
        if(vao==0)throw new IllegalStateException("Zero background VAO name");
        program=compileProgram(vertexSource(group==4),FRAGMENT);
        baseLocation=GLES30.glGetUniformLocation(program,"uBase");colorLocation=GLES30.glGetUniformLocation(program,"uColor");depthLocation=GLES30.glGetUniformLocation(program,"uDepth");
        if(baseLocation<0||colorLocation<0||depthLocation<0)throw new IllegalStateException("Missing background restore uniforms");
        check("background restore uniforms");
    }
    private static void texture(int name,int format,int width,int height,int views){
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,name);
        GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY,1,format,width,height,views);check("background texture storage");
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);check("background texture parameters");
    }
    public void capture(int base,int group,float[] vp,int offset,float aspect){
        System.arraycopy(vp,offset,matrices,0,group*16);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,framebuffers[base/group]);GLES30.glBindVertexArray(vao);
        GLES30.glViewport(0,0,width,height);GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
        GLES30.glColorMask(true,true,true,true);GLES30.glDepthMask(true);GLES30.glDepthFunc(GLES30.GL_LESS);
        GLES30.glDepthRangef(0,1);GLES30.glClearDepthf(1);
        GLES30.glClearColor(.035f,.045f,.06f,1);GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);
        try{scene.drawStaticBackground(matrices,group,aspect);check("background capture "+base);}
        finally{GLES30.glBindVertexArray(0);}
    }
    public void finishCapture(){GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);check("complete background capture");}
    public void restore(int base,int group){
        // gl_FragCoord indexes the unchanged 0,0 per-view viewport. Never sample the active destination.
        boolean dither=GLES30.glIsEnabled(GLES30.GL_DITHER);
        GLES30.glDisable(GLES30.GL_DITHER);GLES30.glDisable(GLES30.GL_BLEND);GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);GLES30.glDepthFunc(GLES30.GL_LESS);GLES30.glDepthMask(true);
        GLES30.glBindVertexArray(vao);GLES30.glUseProgram(program);
        GLES30.glUniform1i(baseLocation,base);GLES30.glUniform1i(colorLocation,0);GLES30.glUniform1i(depthLocation,1);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,color);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,depth);
        try{GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);}
        finally{
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,0);GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,0);GLES30.glBindVertexArray(0);
            if(dither)GLES30.glEnable(GLES30.GL_DITHER);
        }
    }
    public void release(){
        if(program!=0){GLES30.glDeleteProgram(program);program=0;}
        if(vao!=0){GLES30.glDeleteVertexArrays(1,new int[]{vao},0);vao=0;}
        if(framebuffers.length>0){GLES30.glDeleteFramebuffers(framebuffers.length,framebuffers,0);framebuffers=new int[0];}
        if(color!=0){GLES30.glDeleteTextures(1,new int[]{color},0);color=0;}
        if(depth!=0){GLES30.glDeleteTextures(1,new int[]{depth},0);depth=0;}
        check("background resources released");
    }
    private static int compileProgram(String vertex,String fragment){
        int program=GLES30.glCreateProgram();int[] shaders=new int[2];boolean linked=false;
        try{
            if(program==0)throw new IllegalStateException("Zero background shader program");
            for(int i=0;i<2;i++){
                shaders[i]=GLES30.glCreateShader(i==0?GLES30.GL_VERTEX_SHADER:GLES30.GL_FRAGMENT_SHADER);
                if(shaders[i]==0)throw new IllegalStateException("Zero background shader name");
                GLES30.glShaderSource(shaders[i],i==0?vertex:fragment);GLES30.glCompileShader(shaders[i]);int[] ok={0};
                GLES30.glGetShaderiv(shaders[i],GLES30.GL_COMPILE_STATUS,ok,0);
                if(ok[0]==0)throw new IllegalStateException("Background shader: "+GLES30.glGetShaderInfoLog(shaders[i]));
                GLES30.glAttachShader(program,shaders[i]);
            }
            GLES30.glLinkProgram(program);int[] ok={0};GLES30.glGetProgramiv(program,GLES30.GL_LINK_STATUS,ok,0);
            if(ok[0]==0)throw new IllegalStateException("Background link: "+GLES30.glGetProgramInfoLog(program));
            check("background program link");linked=true;return program;
        }finally{
            for(int shader:shaders)if(shader!=0)GLES30.glDeleteShader(shader);
            if(!linked&&program!=0)GLES30.glDeleteProgram(program);
        }
    }
    private static void check(String where){int error=GLES30.glGetError();if(error!=GLES30.GL_NO_ERROR)throw new IllegalStateException(where+": GL "+error);}
    static String vertexSource(boolean multiview){
        String source=VERTEX;
        if(multiview)source=source.replace("#version 300 es","#version 300 es\n#extension GL_OVR_multiview2 : require\nlayout(num_views=4) in;")
                .replace("vLayer=uBase;","vLayer=uBase+int(gl_ViewID_OVR);");
        return source;
    }
    private static final String VERTEX="""
        #version 300 es
        precision highp float;
        precision highp int;
        uniform int uBase;
        flat out highp int vLayer;
        void main(){
            vec2 p=gl_VertexID==0?vec2(-1,-1):(gl_VertexID==1?vec2(3,-1):vec2(-1,3));
            vLayer=uBase;gl_Position=vec4(p,0,1);
        }
        """;
    static final String FRAGMENT="""
        #version 300 es
        precision highp float;
        precision highp int;
        precision highp sampler2DArray;
        uniform sampler2DArray uColor,uDepth;
        flat in highp int vLayer;
        out highp vec4 color;
        void main(){
            ivec3 sampleAt=ivec3(ivec2(gl_FragCoord.xy),vLayer);
            float depth=texelFetch(uDepth,sampleAt,0).r;
            if(depth>=1.0)discard;
            gl_FragDepth=depth;
            color=texelFetch(uColor,sampleAt,0);
        }
        """;
}
