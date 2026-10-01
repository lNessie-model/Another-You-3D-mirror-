package com.mirror.bench;

import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Generic RGB subpixel interlacing. Diagnostic constants are NOT panel calibration. */
final class InterlaceRenderer implements GLSurfaceView.Renderer {
    private final int views;
    private final float scale;
    private final boolean scene;
    private int width,height,viewWidth,viewHeight,texture,fbo,depth,sceneProgram,interlaceProgram;
    private int vertexBuffer,indexBuffer,indexCount;
    private String gpu="";
    private volatile String error="";
    private volatile float[] expressions={0,0,0,0};
    private boolean measuring;
    private long firstFrame,lastFrame;
    private final ArrayList<Double> workMs=new ArrayList<>(),intervalMs=new ArrayList<>();
    private final ArrayList<Double> sceneStageMs=new ArrayList<>(),interlaceStageMs=new ArrayList<>();
    private volatile boolean profileStages;
    private boolean pipeline;
    private final long[] fences=new long[2];
    private int fenceSlot;
    private boolean lookup,verifyLookup;
    private int lookupTexture,lookupProgram,lookupGenerator;
    private int lookupMismatch=-1;
    private float pitch=9.69f,tilt=.28f;
    private String calibration="diagnostic constants; not a verified optical panel calibration";
    private final float[] projection=new float[16],view=new float[16],model=new float[16],mv=new float[16],mvp=new float[16];

    InterlaceRenderer(int views,float scale,boolean scene) {
        if(views<1||views>16||scale<=0||scale>1) throw new IllegalArgumentException("Invalid view configuration");
        this.views=views; this.scale=scale; this.scene=scene;
    }
    void setExpressions(float[] values) { expressions=values; }
    void setProfileStages(boolean enabled) { profileStages=enabled; }
    void setPipeline(boolean enabled) { pipeline=enabled; }
    void setLookup(boolean enabled,boolean verify) { lookup=enabled; verifyLookup=verify; }
    void setPanelParameters(float suppliedPitch,float suppliedTan,String pitchUnits) {
        if(!Float.isFinite(suppliedPitch)||!Float.isFinite(suppliedTan)||suppliedPitch<=0||
                !("pixels".equals(pitchUnits)||"subpixels".equals(pitchUnits)))
            throw new IllegalArgumentException("Invalid panel pitch/tan/units");
        // Shader x is measured in RGB subpixels, while gl_FragCoord.y is pixels.
        pitch=suppliedPitch*("pixels".equals(pitchUnits)?3:1);
        tilt=suppliedTan*3;
        calibration="user pitch="+suppliedPitch+" "+pitchUnits+", tan="+suppliedTan+
                " horizontal pixels/vertical pixel; optical alignment unverified";
    }
    synchronized void beginMeasurement() {
        measuring=true; firstFrame=0; lastFrame=0; workMs.clear(); intervalMs.clear();
        sceneStageMs.clear(); interlaceStageMs.clear();
    }

    @Override public void onSurfaceCreated(GL10 ignored,EGLConfig config) {
        try {
            for(int i=0;i<fences.length;i++) fences[i]=0;
            fenceSlot=0;
            texture=0; fbo=0; depth=0; lookupTexture=0; lookupMismatch=-1;
            gpu=GLES30.glGetString(GLES30.GL_RENDERER)+" / "+GLES30.glGetString(GLES30.GL_VERSION);
            sceneProgram=program(SCENE_VERTEX,SCENE_FRAGMENT);
            interlaceProgram=program(SCREEN_VERTEX,INTERLACE_FRAGMENT);
            if(lookup||verifyLookup) {
                lookupProgram=program(SCREEN_VERTEX,LOOKUP_FRAGMENT);
                lookupGenerator=program(SCREEN_VERTEX,LOOKUP_GENERATOR);
            }
            makeMesh();
        } catch(Throwable problem) { fail(problem); }
    }
    @Override public void onSurfaceChanged(GL10 ignored,int width,int height) {
        this.width=width; this.height=height;
        viewWidth=Math.max(1,Math.round(width*scale)); viewHeight=Math.max(1,Math.round(height*scale));
        try {
            int[] ids=new int[1];
            if(texture!=0) GLES30.glDeleteTextures(1,new int[]{texture},0);
            GLES30.glGenTextures(1,ids,0); texture=ids[0];
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,texture);
            // https://developer.android.com/reference/android/opengl/GLES30#glTexStorage3D(int,int,int,int,int,int)
            GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY,1,GLES30.GL_RGBA8,viewWidth,viewHeight,views);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
            if(fbo==0) { GLES30.glGenFramebuffers(1,ids,0); fbo=ids[0]; }
            if(depth!=0) GLES30.glDeleteRenderbuffers(1,new int[]{depth},0);
            GLES30.glGenRenderbuffers(1,ids,0); depth=ids[0];
            GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER,depth);
            GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER,GLES30.GL_DEPTH_COMPONENT16,viewWidth,viewHeight);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo);
            GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER,GLES30.GL_DEPTH_ATTACHMENT,GLES30.GL_RENDERBUFFER,depth);
            for(int v=0;v<views;v++) {
                GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,texture,0,v);
                if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)
                    throw new IllegalStateException("Framebuffer incomplete");
                GLES30.glViewport(0,0,viewWidth,viewHeight);
                GLES30.glClearColor((v%3+1)/4f,((v+1)%3+1)/4f,((v+2)%3+1)/4f,1);
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);
            if(lookup||verifyLookup) {
                makeLookup();
                if(verifyLookup) verifyLookupPixels();
            }
            GLES30.glFinish(); checkGl();
        } catch(Throwable problem) { fail(problem); }
    }
    @Override public void onDrawFrame(GL10 ignored) {
        if(!error.isEmpty()||texture==0) return;
        try {
            long before=SystemClock.elapsedRealtimeNanos();
            if(pipeline&&fences[fenceSlot]!=0) {
                int wait=GLES30.glClientWaitSync(fences[fenceSlot],GLES30.GL_SYNC_FLUSH_COMMANDS_BIT,1_000_000_000L);
                if(wait==GLES30.GL_WAIT_FAILED||wait==GLES30.GL_TIMEOUT_EXPIRED)
                    throw new IllegalStateException("GPU fence did not complete: "+wait);
                GLES30.glDeleteSync(fences[fenceSlot]); fences[fenceSlot]=0;
            }
            boolean split=profileStages&&!pipeline&&measuring&&workMs.size()%30==0;
            if(scene) drawViews();
            long sceneEnd=before;
            if(split) { GLES30.glFinish(); sceneEnd=SystemClock.elapsedRealtimeNanos(); }
            drawInterlace(lookup);
            // Completion wall time includes CPU submission + GPU execution, not a GPU-only timer.
            if(pipeline) {
                fences[fenceSlot]=GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
                if(fences[fenceSlot]==0) throw new IllegalStateException("Cannot create GPU fence");
                GLES30.glFlush(); fenceSlot=(fenceSlot+1)%fences.length;
            } else GLES30.glFinish();
            checkGl();
            long after=SystemClock.elapsedRealtimeNanos();
            synchronized(this) {
                if(measuring) {
                    if(firstFrame==0) firstFrame=before;
                    if(lastFrame!=0) intervalMs.add((before-lastFrame)/1e6);
                    lastFrame=before; workMs.add((after-before)/1e6);
                    if(split) { sceneStageMs.add((sceneEnd-before)/1e6); interlaceStageMs.add((after-sceneEnd)/1e6); }
                }
            }
        } catch(Throwable problem) { fail(problem); }
    }
    private void phaseUniforms(int program) {
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"uCount"),views);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program,"uPitch"),pitch);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program,"uTilt"),tilt);
    }
    private void drawInterlace(boolean cached) {
        int program=cached?lookupProgram:interlaceProgram;
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0);
        GLES30.glViewport(0,0,width,height); GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glUseProgram(program);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY,texture);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"uViews"),0);
        if(cached) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,lookupTexture);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program,"uLookup"),1);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        } else phaseUniforms(program);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
    }
    private void makeLookup() {
        if(lookupTexture!=0) GLES30.glDeleteTextures(1,new int[]{lookupTexture},0);
        int[] ids=new int[1]; GLES30.glGenTextures(1,ids,0); lookupTexture=ids[0];
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,lookupTexture);
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D,1,GLES30.GL_RGBA8UI,width,height);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_NEAREST);
        int[] frame=new int[1]; GLES30.glGenFramebuffers(1,frame,0); GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,frame[0]);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,GLES30.GL_TEXTURE_2D,lookupTexture,0);
        if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Lookup framebuffer incomplete");
        GLES30.glViewport(0,0,width,height); GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glUseProgram(lookupGenerator); phaseUniforms(lookupGenerator);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES,0,3);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,0); GLES30.glDeleteFramebuffers(1,frame,0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        checkGl();
    }
    private void verifyLookupPixels() {
        // Every channel encodes a distinct value for every view. A repeated three-
        // color fixture would hide index mistakes in a four/eight-view lookup table.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo);
        for(int v=0;v<views;v++) {
            GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,texture,0,v);
            GLES30.glClearColor((v+1f)/(views+1),(views-v)/(views+1f),(v+1f)/(views+1)*.7f,1);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        }
        ByteBuffer reference=ByteBuffer.allocateDirect(width*height*4),candidate=ByteBuffer.allocateDirect(width*height*4);
        drawInterlace(false); GLES30.glReadPixels(0,0,width,height,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,reference);
        drawInterlace(true); GLES30.glReadPixels(0,0,width,height,GLES30.GL_RGBA,GLES30.GL_UNSIGNED_BYTE,candidate);
        lookupMismatch=0;
        for(int i=0;i<reference.capacity();i++) if(reference.get(i)!=candidate.get(i)) lookupMismatch++;
        if(lookupMismatch!=0) throw new IllegalStateException("Lookup pixel mismatch: "+lookupMismatch);
        checkGl();
    }
    private void drawViews() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,fbo);
        GLES30.glViewport(0,0,viewWidth,viewHeight);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glUseProgram(sceneProgram);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,vertexBuffer);
        int[] sizes={3,3,2,3,3,3,3}; int offset=0;
        for(int attribute=0;attribute<sizes.length;attribute++) {
            GLES30.glEnableVertexAttribArray(attribute);
            GLES30.glVertexAttribPointer(attribute,sizes[attribute],GLES30.GL_FLOAT,false,80,offset);
            offset+=sizes[attribute]*4;
        }
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER,indexBuffer);
        float[] weights=expressions;
        GLES30.glUniform4fv(GLES30.glGetUniformLocation(sceneProgram,"uWeights"),1,weights,0);
        float angle=(SystemClock.uptimeMillis()%12000)/12000f*360;
        Matrix.setIdentityM(model,0); Matrix.rotateM(model,0,angle,0,1,0);
        float aspect=(float)viewWidth/viewHeight;
        for(int v=0;v<views;v++) {
            GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,texture,0,v);
            GLES30.glClearColor(.035f,.045f,.06f,1);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);
            float eye=views==1?0:(v/(float)(views-1)-.5f)*.4f;
            Matrix.setLookAtM(view,0,eye,0,3,eye,0,0,0,1,0);
            float near=.1f,half=.052f,shift=-eye*near/3;
            Matrix.frustumM(projection,0,-half*aspect+shift,half*aspect+shift,-half,half,near,10);
            Matrix.multiplyMM(mv,0,view,0,model,0); Matrix.multiplyMM(mvp,0,projection,0,mv,0);
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(sceneProgram,"uMvp"),1,false,mvp,0);
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(sceneProgram,"uModel"),1,false,model,0);
            int perBatch=indexCount/8;
            for(int batch=0;batch<8;batch++) {
                GLES30.glUniform3f(GLES30.glGetUniformLocation(sceneProgram,"uColor"),.65f+batch*.02f,.42f,.30f);
                GLES30.glDrawElements(GLES30.GL_TRIANGLES,perBatch,GLES30.GL_UNSIGNED_SHORT,batch*perBatch*2);
            }
        }
        for(int attribute=0;attribute<7;attribute++) GLES30.glDisableVertexAttribArray(attribute);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER,0);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0);
    }
    private void makeMesh() {
        int segments=128,rings=80;
        ByteBuffer vertices=ByteBuffer.allocateDirect((segments+1)*(rings+1)*80).order(ByteOrder.nativeOrder());
        for(int r=0;r<=rings;r++) for(int s=0;s<=segments;s++) {
            double a=Math.PI*r/rings,b=2*Math.PI*s/segments;
            float x=(float)(Math.sin(a)*Math.cos(b)),y=(float)Math.cos(a),z=(float)(Math.sin(a)*Math.sin(b));
            vertices.putFloat(x*.72f).putFloat(y).putFloat(z*.65f);
            vertices.putFloat(x).putFloat(y).putFloat(z).putFloat(s/(float)segments).putFloat(r/(float)rings);
            float front=Math.max(0,z),mouth=(float)Math.exp(-6*x*x-12*(y+.35f)*(y+.35f))*front;
            float eye=(float)Math.exp(-35*(Math.abs(x)-.32f)*(Math.abs(x)-.32f)-30*(y-.2f)*(y-.2f))*front;
            float brow=(float)Math.exp(-12*x*x-35*(y-.45f)*(y-.45f))*front;
            vertices.putFloat(0).putFloat(-.18f*mouth).putFloat(.03f*mouth);
            vertices.putFloat(0).putFloat(-.1f*eye).putFloat(0);
            vertices.putFloat(.025f*x*mouth).putFloat(.07f*Math.abs(x)*mouth).putFloat(0);
            vertices.putFloat(0).putFloat(.07f*brow).putFloat(0);
        }
        vertices.position(0);
        indexCount=segments*rings*6;
        ByteBuffer indices=ByteBuffer.allocateDirect(indexCount*2).order(ByteOrder.nativeOrder());
        for(int r=0;r<rings;r++) for(int s=0;s<segments;s++) {
            int a=r*(segments+1)+s,b=a+segments+1;
            for(int index:new int[]{a,b,a+1,a+1,b,b+1}) indices.putShort((short)index);
        }
        indices.position(0);
        int[] ids=new int[2]; GLES30.glGenBuffers(2,ids,0); vertexBuffer=ids[0]; indexBuffer=ids[1];
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,vertexBuffer);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,vertices.capacity(),vertices,GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER,indexBuffer);
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER,indices.capacity(),indices,GLES30.GL_STATIC_DRAW);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0); GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER,0);
    }
    synchronized JSONObject summary() throws Exception {
        measuring=false;
        double seconds=lastFrame>firstFrame?(lastFrame-firstFrame)/1e9:0;
        return new JSONObject().put("mode",scene?"scene+interlace":"interlace_only")
                .put("gpu",gpu).put("error",error).put("views",views)
                .put("output_width",width).put("output_height",height)
                .put("view_width",viewWidth).put("view_height",viewHeight)
                .put("view_color_buffer_mib",viewWidth*(double)viewHeight*views*4/1048576)
                .put("triangles_per_view",scene?indexCount/3:0).put("draw_calls_per_view",scene?8:0)
                .put("morph_targets",scene?4:0).put("frames",workMs.size())
                .put("sync_mode",pipeline?"bounded two-frame GPU fence pipeline":"per-frame glFinish")
                .put("max_frames_in_flight",pipeline?2:1)
                .put("interlace_index_mode",lookup?"GPU-generated integer lookup":"per-pixel arithmetic")
                .put("lookup_color_buffer_mib",lookup||verifyLookup?width*(double)height*4/1048576:0)
                .put("lookup_verification_byte_mismatches",lookupMismatch)
                .put("fps",seconds>0?(workMs.size()-1)/seconds:0)
                .put("frame_work_p50_ms",BenchActivity.percentile(workMs,.5))
                .put("frame_work_p95_ms",BenchActivity.percentile(workMs,.95))
                .put("frame_interval_p95_ms",BenchActivity.percentile(intervalMs,.95))
                .put("frame_work_samples_ms",new JSONArray(workMs)).put("frame_interval_samples_ms",new JSONArray(intervalMs))
                .put("stage_profile_samples",sceneStageMs.size())
                .put("scene_completion_mean_ms",sceneStageMs.stream().mapToDouble(x->x).average().orElse(0))
                .put("scene_completion_p95_ms",BenchActivity.percentile(sceneStageMs,.95))
                .put("interlace_completion_mean_ms",interlaceStageMs.stream().mapToDouble(x->x).average().orElse(0))
                .put("interlace_completion_p95_ms",BenchActivity.percentile(interlaceStageMs,.95))
                .put("stage_timing",pipeline?"Completion stage profiling disabled for asynchronous pipeline"
                        :"One extra glFinish every 30 frames; completion wall time including submission, GPU work and shared-GPU waiting; not GPU-exclusive")
                .put("phase_pitch_rgb_subpixels",pitch).put("phase_tilt_rgb_subpixels_per_y_pixel",tilt)
                .put("calibration",calibration)
                .put("timing",pipeline?"CPU submission and bounded fence wait; not GPU completion; callback FPS includes EGL swap backpressure"
                        :"glFinish completion wall time; includes CPU submission and GPU execution; excludes EGL swap");
    }
    private void fail(Throwable problem) { error=problem.toString(); Log.e("MirrorBench","GL failure",problem); }
    private static void checkGl() { int e=GLES30.glGetError(); if(e!=0) throw new IllegalStateException("GL error "+e); }
    private static int program(String vertex,String fragment) {
        int p=GLES30.glCreateProgram();
        for(int type:new int[]{GLES30.GL_VERTEX_SHADER,GLES30.GL_FRAGMENT_SHADER}) {
            int shader=GLES30.glCreateShader(type);
            GLES30.glShaderSource(shader,type==GLES30.GL_VERTEX_SHADER?vertex:fragment); GLES30.glCompileShader(shader);
            int[] ok=new int[1]; GLES30.glGetShaderiv(shader,GLES30.GL_COMPILE_STATUS,ok,0);
            if(ok[0]==0) throw new IllegalStateException(GLES30.glGetShaderInfoLog(shader));
            GLES30.glAttachShader(p,shader); GLES30.glDeleteShader(shader);
        }
        GLES30.glLinkProgram(p); int[] ok=new int[1]; GLES30.glGetProgramiv(p,GLES30.GL_LINK_STATUS,ok,0);
        if(ok[0]==0) throw new IllegalStateException(GLES30.glGetProgramInfoLog(p));
        return p;
    }
    private static final String SCREEN_VERTEX="""
        #version 300 es
        out vec2 vUv;
        void main(){vec2 p=gl_VertexID==0?vec2(-1,-1):(gl_VertexID==1?vec2(3,-1):vec2(-1,3));vUv=p*.5+.5;gl_Position=vec4(p,0,1);}
        """;
    private static final String INTERLACE_FRAGMENT="""
        #version 300 es
        precision highp float;
        precision highp sampler2DArray;
        in vec2 vUv;
        uniform sampler2DArray uViews; uniform int uCount; uniform float uPitch,uTilt;
        out vec4 color;
        float viewAt(float c){return floor(fract((gl_FragCoord.x*3.0+gl_FragCoord.y*uTilt+c)/uPitch)*float(uCount));}
        void main(){color=vec4(texture(uViews,vec3(vUv,viewAt(0.0))).r,texture(uViews,vec3(vUv,viewAt(1.0))).g,texture(uViews,vec3(vUv,viewAt(2.0))).b,1);}
        """;
    private static final String LOOKUP_GENERATOR="""
        #version 300 es
        precision highp float; precision highp int;
        uniform int uCount; uniform float uPitch,uTilt; out uvec4 indices;
        float viewAt(float c){return floor(fract((gl_FragCoord.x*3.0+gl_FragCoord.y*uTilt+c)/uPitch)*float(uCount));}
        void main(){indices=uvec4(uint(viewAt(0.0)),uint(viewAt(1.0)),uint(viewAt(2.0)),0u);}
        """;
    private static final String LOOKUP_FRAGMENT="""
        #version 300 es
        precision highp float; precision highp int;
        precision highp sampler2DArray; precision highp usampler2D;
        in vec2 vUv; uniform sampler2DArray uViews; uniform usampler2D uLookup; out vec4 color;
        void main(){uvec3 v=texelFetch(uLookup,ivec2(gl_FragCoord.xy),0).rgb;
        color=vec4(texture(uViews,vec3(vUv,float(v.r))).r,texture(uViews,vec3(vUv,float(v.g))).g,texture(uViews,vec3(vUv,float(v.b))).b,1);}
        """;
    private static final String SCENE_VERTEX="""
        #version 300 es
        layout(location=0) in vec3 aPosition; layout(location=1) in vec3 aNormal; layout(location=2) in vec2 aUv;
        layout(location=3) in vec3 aJaw; layout(location=4) in vec3 aBlink; layout(location=5) in vec3 aSmile; layout(location=6) in vec3 aBrow;
        uniform mat4 uMvp,uModel; uniform vec4 uWeights; out vec3 vNormal; out vec2 vUv;
        void main(){vec3 p=aPosition+aJaw*uWeights.x+aBlink*uWeights.y+aSmile*uWeights.z+aBrow*uWeights.w;gl_Position=uMvp*vec4(p,1);vNormal=mat3(uModel)*aNormal;vUv=aUv;}
        """;
    private static final String SCENE_FRAGMENT="""
        #version 300 es
        precision mediump float;
        in vec3 vNormal; in vec2 vUv; uniform vec3 uColor; out vec4 color;
        void main(){vec3 n=normalize(vNormal);float light=.2+.5*max(dot(n,normalize(vec3(1,1,2))),0.0)+.25*max(dot(n,normalize(vec3(-1,.3,1))),0.0);float pattern=.92+.08*sin(vUv.x*60.0)*sin(vUv.y*60.0);color=vec4(uColor*light*pattern,1);}
        """;
}
