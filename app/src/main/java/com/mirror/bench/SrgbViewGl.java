package com.mirror.bench;

import android.opengl.GLES30;

/** One EGL-generation owner; owns no GL names. Discard on context loss, never close old names. */
final class SrgbViewGl {
    static final int WRITE=0x8DB9,DECODE=0x8A48,SKIP=0x8A4A;
    private final Thread owner=Thread.currentThread();
    private final long generation,all;
    private final int views;
    private final int[] scratch=new int[1];
    private long singles,groups;
    private boolean writing,savedWrite;
    private int texture;
    private volatile String state="uninitialized";
    SrgbViewGl(long generation,int views,String extensions){
        SrgbViewPolicy.requireExtensions(extensions);
        if(generation<=0||views!=16)throw new IllegalArgumentException("Sixteen-view EGL generation required");
        this.generation=generation;this.views=views;all=(1L<<views)-1;
    }
    private void owner(long current){if(Thread.currentThread()!=owner||current!=generation)throw new IllegalStateException("sRGB view context/owner differs");}
    void invalidate(long current){owner(current);if(writing)throw new IllegalStateException("Active sRGB write scope");state="uninitialized";singles=groups=0;texture=0;}
    void configureBoundArray(int name,long current){
        owner(current);if(name<=0)throw new IllegalArgumentException("View texture required");
        GLES30.glGetIntegerv(GLES30.GL_TEXTURE_BINDING_2D_ARRAY,scratch,0);
        if(scratch[0]!=name)throw new IllegalStateException("Wrong sRGB view texture binding");
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY,DECODE,SKIP);
        GLES30.glGetTexParameteriv(GLES30.GL_TEXTURE_2D_ARRAY,DECODE,scratch,0);
        if(scratch[0]!=SKIP)throw new IllegalStateException("View sampler decode not disabled");
        texture=name;check("sRGB view texture");
    }
    void verifyBoundAttachment(int name,int start,int count,boolean multiview,long current){
        owner(current);
        if(name!=texture||start<0||count!=(multiview?4:1)||start+count>views||multiview&&start%4!=0)
            throw new IllegalArgumentException("sRGB attachment range/pairing differs");
        if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("sRGB framebuffer incomplete");
        attachment(GLES30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME,name);
        attachment(GLES30.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING,GLES30.GL_SRGB);
        if(multiview){attachment(0x9632,start);attachment(0x9630,count);groups|=((1L<<count)-1)<<start;}
        else{attachment(GLES30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LAYER,start);singles|=1L<<start;}
        check("sRGB view attachment");
    }
    private void attachment(int key,int expected){
        GLES30.glGetFramebufferAttachmentParameteriv(GLES30.GL_FRAMEBUFFER,GLES30.GL_COLOR_ATTACHMENT0,key,scratch,0);
        if(scratch[0]!=expected)throw new IllegalStateException("sRGB attachment metadata differs: "+key);
    }
    void complete(boolean multiview,long current){owner(current);if(singles!=all||multiview&&groups!=all)throw new IllegalStateException("Unverified sRGB view layers");state="srgb8_alpha8_skip_decode";}
    /** Called once during setup with texture unit zero current; do not silently replace a sampler. */
    void requireSampling(long current){owner(current);GLES30.glGetIntegerv(GLES30.GL_SAMPLER_BINDING,scratch,0);if(scratch[0]!=0)throw new IllegalStateException("Sampler object would override sRGB decode policy");check("sRGB view sampling");}
    /** No object allocation. Must close before binding/drawing the window, also on exceptions. */
    void beginWrite(boolean linear,long current){
        owner(current);if(writing)throw new IllegalStateException("Nested sRGB write scope");
        savedWrite=GLES30.glIsEnabled(WRITE);writing=true;
        try{if(linear)GLES30.glEnable(WRITE);else GLES30.glDisable(WRITE);}
        catch(RuntimeException|Error original){try{endWrite();}catch(RuntimeException|Error cleanup){original.addSuppressed(cleanup);}throw original;}
    }
    void endWrite(){
        owner(generation);if(!writing)return;writing=false;
        if(savedWrite)GLES30.glEnable(WRITE);else GLES30.glDisable(WRITE);
    }
    boolean ready(){return state.equals("srgb8_alpha8_skip_decode");}
    String state(){return state;}
    void fail(){state="failed";}
    private static void check(String label){int error=GLES30.glGetError();if(error!=GLES30.GL_NO_ERROR)throw new IllegalStateException(label+" GL error "+error);}
}
