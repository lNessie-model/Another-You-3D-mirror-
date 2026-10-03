package com.mirror.bench;

import android.opengl.GLES30;

/** GLES adapter; no texture allocations, clears, invalidations or synchronization. */
final class PersistentMultiviewGl implements PersistentMultiviewFbos.Driver {
    static final PersistentMultiviewGl INSTANCE=new PersistentMultiviewGl();
    private PersistentMultiviewGl(){}
    public int create(){
        int[] name={0};GLES30.glGenFramebuffers(1,name,0);
        try{check("allocate framebuffer");if(name[0]==0)throw new IllegalStateException("Zero framebuffer");return name[0];}
        catch(RuntimeException failure){if(name[0]!=0)GLES30.glDeleteFramebuffers(1,name,0);throw failure;}
    }
    public void bind(int name){GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER,name);}
    public void attach(int color,int depth,int base){
        MultiviewGl.attach(GLES30.GL_COLOR_ATTACHMENT0,color,base,4);
        MultiviewGl.attach(GLES30.GL_DEPTH_ATTACHMENT,depth,base,4);
        check("attach group "+base);
        if(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)!=GLES30.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Persistent multiview framebuffer incomplete at layer "+base);
        verifyAttachment(GLES30.GL_COLOR_ATTACHMENT0,color,base);
        verifyAttachment(GLES30.GL_DEPTH_ATTACHMENT,depth,base);
        check("validate group "+base);
    }
    private static void verifyAttachment(int attachment,int texture,int base){
        int[] value={0};
        int[] parameters={GLES30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE,GLES30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME,
                GLES30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL,0x9632,0x9630};
        int[] expected={GLES30.GL_TEXTURE,texture,0,base,4};
        for(int i=0;i<parameters.length;i++){
            GLES30.glGetFramebufferAttachmentParameteriv(GLES30.GL_FRAMEBUFFER,attachment,parameters[i],value,0);
            if(value[0]!=expected[i])throw new IllegalStateException("Persistent attachment state mismatch at base "+base+", parameter "+parameters[i]);
        }
    }
    public void delete(int name){GLES30.glDeleteFramebuffers(1,new int[]{name},0);}
    private static void check(String where){int code=GLES30.glGetError();if(code!=GLES30.GL_NO_ERROR)throw new IllegalStateException(where+" GL error "+code);}
}
