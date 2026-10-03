#include <jni.h>
#include <stdlib.h>
#include <stdio.h>
#include <dlfcn.h>
#include "rknn_expression_core.h"

/* Independent JNI library. Java verifies model bytes and the app's existing runtime entry. */
typedef struct {void *library;ExpressionApi api;ExpressionCore core;} ExpressionModel;
static void fail(JNIEnv *e,const char *type,const char *message){
    if((*e)->ExceptionCheck(e))return;
    jclass cls=(*e)->FindClass(e,type);
    if(cls){(*e)->ThrowNew(e,cls,message);(*e)->DeleteLocalRef(e,cls);}
}
static void fail_core(JNIEnv *e,const ExpressionCore *m){
    char message[256];
    snprintf(message,sizeof(message),"RKNN expression failed at %s (rc=%d, release=%d, destroy=%d)",m->stage?m->stage:"unknown",m->rc,m->release_rc,m->destroy_rc);
    fail(e,"java/lang/IllegalStateException",message);
}
static int dispose(ExpressionModel *m){
    int ok=expression_close(&m->core);
    if(m->library&&dlclose(m->library)!=0)ok=0;
    m->library=NULL;return ok;
}
JNIEXPORT jlong JNICALL Java_com_mirror_bench_RknnExpression_openNative(JNIEnv *e,jclass cls,jbyteArray bytes,jstring runtime){
    (void)cls;
    if(!bytes||!runtime||(*e)->GetArrayLength(e,bytes)!=(jsize)EXPRESSION_MODEL_BYTES){fail(e,"java/lang/IllegalArgumentException","Invalid expression model arguments");return 0;}
    const char *path=(*e)->GetStringUTFChars(e,runtime,NULL);if(!path)return 0;
    if(path[0]!='/'){(*e)->ReleaseStringUTFChars(e,runtime,path);fail(e,"java/lang/IllegalArgumentException","Expression runtime path must be absolute");return 0;}
    ExpressionModel *m=calloc(1,sizeof(*m));
    if(!m){(*e)->ReleaseStringUTFChars(e,runtime,path);fail(e,"java/lang/OutOfMemoryError","Expression context allocation failed");return 0;}
    m->library=dlopen(path,RTLD_NOW|RTLD_LOCAL);(*e)->ReleaseStringUTFChars(e,runtime,path);
    if(!m->library){free(m);fail(e,"java/lang/IllegalStateException","Cannot load the verified app RKNN runtime");return 0;}
    m->api.init=dlsym(m->library,"rknn_init");m->api.query=dlsym(m->library,"rknn_query");
    m->api.set=dlsym(m->library,"rknn_inputs_set");m->api.run=dlsym(m->library,"rknn_run");
    m->api.get=dlsym(m->library,"rknn_outputs_get");m->api.release=dlsym(m->library,"rknn_outputs_release");m->api.destroy=dlsym(m->library,"rknn_destroy");
    jbyte *model=(*e)->GetByteArrayElements(e,bytes,NULL);
    if(!model){(void)dispose(m);free(m);return 0;}
    int opened=expression_open(&m->core,&m->api,model,EXPRESSION_MODEL_BYTES);
    (*e)->ReleaseByteArrayElements(e,bytes,model,JNI_ABORT);
    if(!opened){(void)dispose(m);fail_core(e,&m->core);free(m);return 0;}
    return (jlong)(intptr_t)m;
}
JNIEXPORT jstring JNICALL Java_com_mirror_bench_RknnExpression_infoNative(JNIEnv *e,jclass cls,jlong handle){
    (void)cls;ExpressionModel *m=(ExpressionModel*)(intptr_t)handle;
    if(!m||!m->core.active){fail(e,"java/lang/IllegalStateException","Closed expression model");return NULL;}
    const rknn_tensor_attr *a=&m->core.input,*b=&m->core.output;
    /* Names and SDK strings are exact-contract ASCII, already validated before this formatting. */
    char json[4096];int n=snprintf(json,sizeof(json),
        "{\"kind\":\"normalized-expression-suffix-v1\",\"runtime\":\"%s\",\"driver\":\"%s\",\"inputs\":1,\"outputs\":1,"
        "\"input\":{\"index\":%u,\"name\":\"%s\",\"n_dims\":3,\"dims\":[1,146,2],\"n_elems\":%u,\"size\":%u,\"type\":%d,\"fmt\":%d,\"qnt_type\":%d,\"zp\":%d,\"scale\":%.9g,\"w_stride\":%u,\"size_with_stride\":%u,\"fl\":%d,\"pass_through\":%u,\"h_stride\":%u},"
        "\"output\":{\"index\":%u,\"name\":\"%s\",\"n_dims\":1,\"dims\":[52],\"n_elems\":%u,\"size\":%u,\"type\":%d,\"fmt\":%d,\"qnt_type\":%d,\"zp\":%d,\"scale\":%.9g,\"w_stride\":%u,\"size_with_stride\":%u,\"fl\":%d,\"pass_through\":%u,\"h_stride\":%u},"
        "\"feed\":{\"type\":0,\"bytes\":1168,\"fmt\":3,\"pass_through\":0,\"want_float\":1},\"model_bytes\":1209569,"
        "\"model_sha256\":\"17b0773a404521a8cf9624fa8147467d9fb3fdb309a641f244244b2910d4831c\","
        "\"runtime_sha256\":\"01fd2e532f4f071e22281a36d83849bf467faf564cf447affb46fe2466d295de\","
        "\"identity_verification\":\"Java SHA256 before native open\",\"poisoned\":%s}",
        m->core.sdk.api_version,m->core.sdk.drv_version,
        a->index,a->name,a->n_elems,a->size,a->type,a->fmt,a->qnt_type,a->zp,a->scale,a->w_stride,a->size_with_stride,a->fl,a->pass_through,a->h_stride,
        b->index,b->name,b->n_elems,b->size,b->type,b->fmt,b->qnt_type,b->zp,b->scale,b->w_stride,b->size_with_stride,b->fl,b->pass_through,b->h_stride,
        m->core.poisoned?"true":"false");
    if(n<0||(size_t)n>=sizeof(json)){fail(e,"java/lang/IllegalStateException","Expression metadata overflow");return NULL;}
    return (*e)->NewStringUTF(e,json);
}
JNIEXPORT jfloatArray JNICALL Java_com_mirror_bench_RknnExpression_runNative(JNIEnv *e,jclass cls,jlong handle,jobject input){
    (void)cls;ExpressionModel *m=(ExpressionModel*)(intptr_t)handle;
    if(!m||!m->core.active||m->core.poisoned){fail(e,"java/lang/IllegalStateException","Closed or failed expression model");return NULL;}
    if(!input){fail(e,"java/lang/IllegalArgumentException","Missing normalized expression buffer");return NULL;}
    void *address=(*e)->GetDirectBufferAddress(e,input);jlong capacity=(*e)->GetDirectBufferCapacity(e,input);
    if((*e)->ExceptionCheck(e))return NULL;
    if(!address||capacity!=1168){fail(e,"java/lang/IllegalArgumentException","Expression input must be 292 direct float32 values");return NULL;}
    float snapshot[292],values[52];memcpy(snapshot,address,sizeof(snapshot));
    if(!expression_run(&m->core,snapshot,values)){fail_core(e,&m->core);return NULL;}
    /* All RKNN output buffers have already been released before any Java allocation. */
    jfloatArray output=(*e)->NewFloatArray(e,52);if(!output)return NULL;
    (*e)->SetFloatArrayRegion(e,output,0,52,values);
    if((*e)->ExceptionCheck(e)){(*e)->DeleteLocalRef(e,output);return NULL;}
    return output;
}
JNIEXPORT void JNICALL Java_com_mirror_bench_RknnExpression_closeNative(JNIEnv *e,jclass cls,jlong handle){
    (void)cls;ExpressionModel *m=(ExpressionModel*)(intptr_t)handle;if(!m)return;
    int okay=dispose(m);
    if(!okay){if(m->core.destroy_rc!=0&&m->core.destroy_rc!=EXPRESSION_NOT_CALLED){m->core.stage="destroy";fail_core(e,&m->core);}else fail(e,"java/lang/IllegalStateException","Expression runtime unload failed");}
    free(m);
}
