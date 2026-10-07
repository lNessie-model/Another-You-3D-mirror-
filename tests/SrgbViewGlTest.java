package com.mirror.bench;
import android.opengl.GLES30;
public final class SrgbViewGlTest {
    static int checks;
    static void ok(boolean x){checks++;if(!x)throw new AssertionError("check "+checks);}
    static void bad(Runnable r){checks++;try{r.run();throw new AssertionError("accepted");}catch(IllegalStateException|IllegalArgumentException expected){}}
    public static void main(String[] args)throws Exception{
        String ext="GL_EXT_texture_sRGB_decode GL_EXT_sRGB_write_control";
        SrgbViewGl g=new SrgbViewGl(2,16,ext);GLES30.texture=12;g.configureBoundArray(12,2);
        ok(GLES30.decode==0x8A4A);ok(!g.ready());
        GLES30.object=12;GLES30.encoding=GLES30.GL_SRGB;
        for(int i=0;i<16;i++){GLES30.layer=i;g.verifyBoundAttachment(12,i,1,false,2);}
        bad(()->g.complete(true,2));
        for(int i=0;i<16;i+=4){GLES30.base=i;GLES30.views=4;g.verifyBoundAttachment(12,i,4,true,2);}
        g.complete(true,2);ok(g.ready());
        for(boolean before:new boolean[]{false,true}){GLES30.write=before;g.beginWrite(true,2);ok(GLES30.write);g.endWrite();ok(GLES30.write==before);g.beginWrite(false,2);ok(!GLES30.write);g.endWrite();ok(GLES30.write==before);}
        GLES30.write=false;GLES30.throwEnable=true;bad(()->g.beginWrite(true,2));ok(!GLES30.write);GLES30.throwEnable=false;
        g.beginWrite(true,2);bad(()->g.beginWrite(true,2));g.endWrite();ok(!GLES30.write);
        bad(()->g.beginWrite(true,3));
        boolean[] wrong={false};Thread t=new Thread(()->{try{g.beginWrite(true,2);}catch(IllegalStateException e){wrong[0]=true;}});t.start();t.join();ok(wrong[0]);
        g.invalidate(2);ok(!g.ready());GLES30.texture=12;g.configureBoundArray(12,2);GLES30.encoding=GLES30.GL_LINEAR;bad(()->g.verifyBoundAttachment(12,0,1,false,2));
        GLES30.encoding=GLES30.GL_SRGB;GLES30.object=99;bad(()->g.verifyBoundAttachment(12,0,1,false,2));GLES30.object=12;
        GLES30.texture=99;bad(()->g.configureBoundArray(12,2));GLES30.texture=12;
        GLES30.sampler=42;bad(()->g.requireSampling(2));GLES30.sampler=0;g.requireSampling(2);
        g.fail();ok(!g.ready());ok(g.state().equals("failed"));
        SrgbViewGl newer=new SrgbViewGl(3,16,ext);ok(!newer.ready());bad(()->newer.beginWrite(true,2));
        System.out.println("SrgbViewGlTest "+checks+" checks GREEN");
    }
}
