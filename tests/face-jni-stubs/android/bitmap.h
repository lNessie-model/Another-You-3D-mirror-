#ifndef MIRROR_BITMAP_HOST_STUB_H
#define MIRROR_BITMAP_HOST_STUB_H
#include <jni.h>
#include <stdint.h>
typedef struct {uint32_t width,height,stride;int32_t format;uint32_t flags;} AndroidBitmapInfo;
#define ANDROID_BITMAP_FORMAT_RGBA_8888 1
int AndroidBitmap_getInfo(JNIEnv *,jobject,AndroidBitmapInfo *);
int AndroidBitmap_lockPixels(JNIEnv *,jobject,void **);
int AndroidBitmap_unlockPixels(JNIEnv *,jobject);
#endif
