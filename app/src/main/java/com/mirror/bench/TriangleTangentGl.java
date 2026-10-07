package com.mirror.bench;

import android.opengl.GLES30;
import java.nio.FloatBuffer;

/** GL-owner diagnostic table. Unit3/unpack state is restored, including partial initialization failure. */
final class TriangleTangentGl implements AutoCloseable {
    final TriangleTangentTable table;
    private final Thread owner=Thread.currentThread();
    private int texture,oldActive,oldTexture,oldSampler;
    private boolean bound,closed;
    private long uploads,uploadedBuild;
    TriangleTangentGl(TriangleTangentTable table){
        this.table=table;
        if(integer(GLES30.GL_MAJOR_VERSION)<3||(integer(GLES30.GL_MAJOR_VERSION)==3&&integer(GLES30.GL_MINOR_VERSION)<2))
            throw new IllegalStateException("Tangent diagnostic requires actual GLES3.2");
        int[] name=new int[1];
        try{
            GLES30.glGenTextures(1,name,0);texture=name[0];if(texture==0)throw new IllegalStateException("No tangent texture");
            begin();
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D,1,GLES30.GL_RGBA32F,table.width(),table.height());
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_NEAREST);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_NEAREST);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
            check("tangent allocation");end();
        }catch(RuntimeException|Error e){try{close();}catch(RuntimeException|Error c){e.addSuppressed(c);}throw e;}
    }
    void prepare(FloatBuffer pn,float[] world,long revision){
        requireOwner();if(bound)throw new IllegalStateException("Cannot replace bound tangent table");
        table.update(pn,world,revision);if(uploadedBuild==table.rebuilds())return;
        int[] keys={GLES30.GL_UNPACK_ALIGNMENT,GLES30.GL_UNPACK_ROW_LENGTH,GLES30.GL_UNPACK_SKIP_ROWS,GLES30.GL_UNPACK_SKIP_PIXELS};
        int[] values=new int[keys.length];for(int i=0;i<keys.length;i++)values[i]=integer(keys[i]);
        int pbo=integer(GLES30.GL_PIXEL_UNPACK_BUFFER_BINDING);Throwable failure=null;
        try{
            begin();GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER,0);
            for(int key:keys)GLES30.glPixelStorei(key,key==GLES30.GL_UNPACK_ALIGNMENT?4:0);
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D,0,0,0,table.width(),table.height(),GLES30.GL_RGBA,GLES30.GL_FLOAT,table.packed());
            check("tangent upload");uploads++;uploadedBuild=table.rebuilds();
        }catch(RuntimeException|Error e){failure=e;throw e;}
        finally{
            ResourceCleanup cleanup=new ResourceCleanup(failure);
            cleanup.close("tangent unpack buffer",()->GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER,pbo));
            for(int i=0;i<keys.length;i++){final int j=i;cleanup.close("tangent pixel store",()->GLES30.glPixelStorei(keys[j],values[j]));}
            cleanup.close("tangent binding",this::end);
            if(failure==null&&cleanup.failure()!=null)throw new IllegalStateException("Tangent upload restore",cleanup.failure());
        }
    }
    void begin(){
        requireOwner();if(bound)throw new IllegalStateException("Nested tangent binding");
        oldActive=integer(GLES30.GL_ACTIVE_TEXTURE);GLES30.glActiveTexture(GLES30.GL_TEXTURE3);
        oldTexture=integer(GLES30.GL_TEXTURE_BINDING_2D);oldSampler=integer(GLES30.GL_SAMPLER_BINDING);bound=true;
        // Keep unit3 active until end: storage/subimage calls operate on the active unit.
        GLES30.glBindSampler(3,0);GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture);
    }
    void verify(){requireOwner();if(!bound)throw new IllegalStateException("Tangent texture not bound");int active=integer(GLES30.GL_ACTIVE_TEXTURE);try{GLES30.glActiveTexture(GLES30.GL_TEXTURE3);if(integer(GLES30.GL_TEXTURE_BINDING_2D)!=texture||integer(GLES30.GL_SAMPLER_BINDING)!=0)throw new IllegalStateException("Tangent texture/sampler binding mismatch");}finally{GLES30.glActiveTexture(active);}}
    void end(){requireOwner();if(!bound)return;bound=false;ResourceCleanup c=new ResourceCleanup(null);c.close("active3",()->GLES30.glActiveTexture(GLES30.GL_TEXTURE3));c.close("old table binding",()->GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,oldTexture));c.close("old sampler",()->GLES30.glBindSampler(3,oldSampler));c.close("old active",()->GLES30.glActiveTexture(oldActive));if(c.failure()!=null)throw new IllegalStateException("Tangent binding restore",c.failure());}
    long uploads(){return uploads;}int texture(){return texture;}
    private void requireOwner(){if(closed||Thread.currentThread()!=owner)throw new IllegalStateException("Tangent GL owner closed/differs");}
    @Override public void close(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Tangent close owner differs");if(closed)return;ResourceCleanup c=new ResourceCleanup(null);c.close("tangent end",this::end);closed=true;int old=texture;texture=0;if(old!=0)c.close("tangent delete",()->GLES30.glDeleteTextures(1,new int[]{old},0));c.close("tangent cleanup GL",()->check("tangent cleanup"));if(c.failure()!=null)throw new IllegalStateException("Tangent cleanup",c.failure());}
    private static void check(String label){int code=GLES30.glGetError();if(code!=0)throw new IllegalStateException(label+" GL error "+code);}
    private static int integer(int key){int[] result=new int[1];GLES30.glGetIntegerv(key,result,0);return result[0];}
}
