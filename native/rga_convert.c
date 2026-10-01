#include <jni.h>
#include <android/bitmap.h>
#include <stdio.h>
#include <string.h>
#include "im2d.h"

static void fail(JNIEnv *env,const char *message) {
    (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/IllegalStateException"),message);
}
JNIEXPORT void JNICALL Java_com_mirror_bench_RgaConvert_convert(JNIEnv *env,jclass clazz,
        jbyteArray input,jobject bitmap,jint width,jint height) {
    (void)clazz;
    AndroidBitmapInfo info;
    if(width<=0||height<=0||(width%2)||(height%2)||
            (*env)->GetArrayLength(env,input)!=(jlong)width*height*3/2) {
        fail(env,"Invalid NV21 dimensions/size"); return;
    }
    if(AndroidBitmap_getInfo(env,bitmap,&info)!=ANDROID_BITMAP_RESULT_SUCCESS||
            info.width!=(uint32_t)width||info.height!=(uint32_t)height||info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888) {
        fail(env,"RGA destination must be a matching RGBA8888 bitmap"); return;
    }
    void *pixels=NULL;
    if(AndroidBitmap_lockPixels(env,bitmap,&pixels)!=ANDROID_BITMAP_RESULT_SUCCESS) {
        fail(env,"Cannot lock RGA bitmap"); return;
    }
    // GetByteArrayElements may copy. IM_SYNC guarantees that both buffers remain
    // valid until the driver has finished, before Java or the bitmap can reuse them.
    jbyte *bytes=(*env)->GetByteArrayElements(env,input,NULL);
    if(!bytes) { AndroidBitmap_unlockPixels(env,bitmap); return; }
    rga_buffer_t src=wrapbuffer_virtualaddr_t(bytes,width,height,width,height,RK_FORMAT_YCrCb_420_SP);
    rga_buffer_t dst=wrapbuffer_virtualaddr_t(pixels,width,height,info.stride/4,height,RK_FORMAT_RGBA_8888);
    IM_STATUS status=imcvtcolor_t(src,dst,RK_FORMAT_YCrCb_420_SP,RK_FORMAT_RGBA_8888,IM_YUV_TO_RGB_BT601_LIMIT,1);
    (*env)->ReleaseByteArrayElements(env,input,bytes,JNI_ABORT);
    AndroidBitmap_unlockPixels(env,bitmap);
    if(status!=IM_STATUS_SUCCESS) {
        char error[384]; snprintf(error,sizeof(error),"RGA conversion failed (%d): %s",status,imStrError_t(status));
        fail(env,error);
    }
}
JNIEXPORT jstring JNICALL Java_com_mirror_bench_RgaConvert_version(JNIEnv *env,jclass clazz) {
    (void)clazz;
    const char *description=querystring(RGA_VERSION);
    return (*env)->NewStringUTF(env,description?description:"unknown");
}
