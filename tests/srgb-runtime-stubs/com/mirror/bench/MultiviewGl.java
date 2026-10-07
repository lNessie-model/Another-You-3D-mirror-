package com.mirror.bench;
/** Sole native OVR attach boundary substituted; production FBO ownership/validation still executes. */
final class MultiviewGl {static void attach(int attachment,int texture,int base,int count){android.opengl.GLES30.attachMultiview(attachment,texture,base,count);}}
