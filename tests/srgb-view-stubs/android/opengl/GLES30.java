package android.opengl;
/** State boundary only: no GLSL, driver, or GPU performance simulation. */
public final class GLES30 {
    public static final int GL_TEXTURE_2D_ARRAY=0x8C1A,GL_TEXTURE_BINDING_2D_ARRAY=0x8C1D,GL_FRAMEBUFFER=0x8D40,
        GL_COLOR_ATTACHMENT0=0x8CE0,GL_FRAMEBUFFER_COMPLETE=0x8CD5,GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING=0x8210,
        GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME=0x8CD1,GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LAYER=0x8CD4,GL_SRGB=0x8C40,GL_LINEAR=0x2601,
        GL_SAMPLER_BINDING=0x8919,GL_NO_ERROR=0;
    public static int texture,decode,object,encoding,layer,base,views,sampler,error;
    public static boolean write,throwEnable;
    public static void glGetIntegerv(int name,int[] out,int at){out[at]=name==GL_TEXTURE_BINDING_2D_ARRAY?texture:sampler;}
    public static void glTexParameteri(int target,int key,int value){decode=value;}
    public static void glGetTexParameteriv(int target,int key,int[] out,int at){out[at]=decode;}
    public static int glCheckFramebufferStatus(int target){return GL_FRAMEBUFFER_COMPLETE;}
    public static void glGetFramebufferAttachmentParameteriv(int target,int attachment,int name,int[] out,int at){out[at]=switch(name){case GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME->object;case GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING->encoding;case GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LAYER->layer;case 0x9632->base;default->views;};}
    public static boolean glIsEnabled(int cap){return write;}
    public static void glEnable(int cap){write=true;if(throwEnable)throw new IllegalStateException("injected enable failure");}
    public static void glDisable(int cap){write=false;}
    public static int glGetError(){int e=error;error=0;return e;}
}
