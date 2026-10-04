package com.mirror.bench;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import java.io.IOException;

/** One selected image, owned by the current GL context. Bitmaps are recycled after upload. */
final class SceneBackgroundTexture {
    private static final String[] IMAGES={"cold-mist.png","crimson-mist.png","ancient-dark-pattern.png","violet-stardust.png"};
    private final AssetManager assets;
    private int texture,selected=-1;
    private volatile long bytes;
    private volatile int uploads;
    SceneBackgroundTexture(AssetManager assets){this.assets=assets;}
    long bytes(){return bytes;}
    int uploads(){return uploads;}
    /** Call only on the creating GL thread/context. Never carry this object across context loss. */
    void bind(int background){
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        try {
            if(background!=selected){
                if(background<8){
                    if(texture!=0){GLES30.glDeleteTextures(1,new int[]{texture},0);texture=0;}
                    bytes=0;
                } else upload(background);
                selected=background;
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture);
        } finally {GLES30.glActiveTexture(GLES30.GL_TEXTURE0);}
    }
    private void upload(int background){
        int image=background-8;if(image<0||image>=IMAGES.length)throw new IllegalArgumentException("未知背景图片");
        Bitmap bitmap=null;
        try(var input=assets.open("scene-backgrounds/"+IMAGES[image])){
            BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            bitmap=BitmapFactory.decodeStream(input,null,options);
            if(bitmap==null||bitmap.getWidth()>2048||bitmap.getHeight()>2048)throw new IllegalArgumentException("背景图片无法解码或尺寸过大");
            if(texture==0){int[] name=new int[1];GLES30.glGenTextures(1,name,0);texture=name[0];}
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,texture);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE);
            // Replaces the previous image storage; no growing image cache or mipmap chain.
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D,0,bitmap,0);
            int error=GLES30.glGetError();if(error!=GLES30.GL_NO_ERROR)throw new IllegalStateException("Background upload GL error "+error);
            bytes=(long)bitmap.getWidth()*bitmap.getHeight()*4;uploads++;
        }catch(IOException failure){throw new IllegalStateException("背景图片读取失败",failure);}
        finally {if(bitmap!=null)bitmap.recycle();}
    }
}
