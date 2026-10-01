#include <jni.h>
#include <android/bitmap.h>
#include <stdio.h>
#include <string.h>
#include <stdint.h>
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

typedef struct { uint8_t *data; jlong available; int row,pixel; } plane_t;
static int read_plane(JNIEnv *env,jobject buffer,int row,int pixel,jmethodID position,jmethodID remaining,plane_t *plane) {
    uint8_t *address=(*env)->GetDirectBufferAddress(env,buffer);
    if(!address) return 0;
    jint offset=(*env)->CallIntMethod(env,buffer,position);
    jint length=(*env)->CallIntMethod(env,buffer,remaining);
    if((*env)->ExceptionCheck(env)) return 0;
    plane->data=address+offset; plane->available=length; plane->row=row; plane->pixel=pixel;
    return 1;
}
static int valid_plane(plane_t plane,int width,int height) {
    jlong line=(jlong)(width-1)*plane.pixel+1;
    return plane.pixel>0&&plane.row>=line&&plane.available>=(jlong)(height-1)*plane.row+line;
}
JNIEXPORT jboolean JNICALL Java_com_mirror_bench_RgaConvert_pack(JNIEnv *env,jclass clazz,
        jint width,jint height,jobject y,jint yr,jint yp,jobject u,jint ur,jint up,jobject v,jint vr,jint vp,jbyteArray output) {
    (void)clazz;
    if(width<=0||height<=0||(width%2)||(height%2)||
            (jlong)width*height*3/2!=(*env)->GetArrayLength(env,output)) {
        fail(env,"Invalid native NV21 dimensions/size"); return JNI_FALSE;
    }
    jclass buffer=(*env)->FindClass(env,"java/nio/Buffer");
    jmethodID position=(*env)->GetMethodID(env,buffer,"position","()I");
    jmethodID remaining=(*env)->GetMethodID(env,buffer,"remaining","()I");
    plane_t planes[3];
    if(!read_plane(env,y,yr,yp,position,remaining,&planes[0])||
            !read_plane(env,u,ur,up,position,remaining,&planes[1])||
            !read_plane(env,v,vr,vp,position,remaining,&planes[2])) return JNI_FALSE;
    if(!valid_plane(planes[0],width,height)||!valid_plane(planes[1],width/2,height/2)||!valid_plane(planes[2],width/2,height/2)) {
        fail(env,"Native YUV planes are truncated or have invalid strides"); return JNI_FALSE;
    }
    jbyte *outputBytes=(*env)->GetByteArrayElements(env,output,NULL);
    if(!outputBytes) return JNI_FALSE;
    uint8_t *destination=(uint8_t *)outputBytes;
    for(int row=0;row<height;row++) {
        const uint8_t *source=planes[0].data+(jlong)row*yr;
        if(yp==1) memcpy(destination,source,width);
        else for(int x=0;x<width;x++) destination[x]=source[x*yp];
        destination+=width;
    }
    // Pointer identity proves that V/U share contiguous NV21 storage. The U
    // bounds check above also covers the final byte missing from V's limit.
    int sharedNv21=up==2&&vp==2&&ur==vr&&planes[1].data==planes[2].data+1;
    for(int row=0;row<height/2;row++) {
        const uint8_t *sourceU=planes[1].data+(jlong)row*ur,*sourceV=planes[2].data+(jlong)row*vr;
        if(sharedNv21) memcpy(destination,sourceV,width);
        else for(int x=0;x<width/2;x++) { destination[x*2]=sourceV[x*vp]; destination[x*2+1]=sourceU[x*up]; }
        destination+=width;
    }
    (*env)->ReleaseByteArrayElements(env,output,outputBytes,0);
    return JNI_TRUE;
}
