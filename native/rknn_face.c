#include <jni.h>
#include <android/bitmap.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <math.h>
#include <dlfcn.h>
#include <stdatomic.h>
#include "rknn_api.h"
#include "face_crop.h"

/* App-local runtime, persistent contexts. No driver or clock changes. */
typedef struct {
    void *library;
    rknn_context context;
    rknn_input_output_num counts;
    rknn_tensor_attr input, outputs[8];
    int (*query)(rknn_context,rknn_query_cmd,void*,uint32_t);
    int (*set)(rknn_context,uint32_t,rknn_input*);
    int (*run)(rknn_context,rknn_run_extend*);
    int (*get)(rknn_context,uint32_t,rknn_output*,rknn_output_extend*);
    int (*release)(rknn_context,uint32_t,rknn_output*);
    int (*destroy)(rknn_context);
    int cleanup_attempted,cleanup_status;
} Model;
static atomic_int native_cleanup_fault=0;
static void fail(JNIEnv *e,const char *message) {
    jclass type=(*e)->FindClass(e,"java/lang/IllegalStateException");
    if(type) (*e)->ThrowNew(e,type,message);
}
static void fail_cleanup(JNIEnv *e,int status) {
    if((*e)->ExceptionCheck(e))return;
    char message[100];snprintf(message,sizeof(message),"RKNN cleanup unconfirmed (%d); restart app",status);
    jclass type=(*e)->FindClass(e,"com/mirror/bench/NativeCleanupUnconfirmed");
    if(type)(*e)->ThrowNew(e,type,message);
}
static int cleanup(Model *m) {
    if(!m)return 0;
    if(m->cleanup_attempted)return m->cleanup_status;
    m->cleanup_attempted=1;
    int status=0;
    if(m->context){status=m->destroy?m->destroy(m->context):-1001;if(!status)m->context=0;}
    if(!status&&m->library){status=dlclose(m->library)?-1002:0;if(!status)m->library=NULL;}
    if(status){
        // Keep uncertain native ownership/library resident until process death. Never retry it.
        m->cleanup_status=status;atomic_store(&native_cleanup_fault,1);return status;
    }
    free(m);return 0;
}
static void initialization_failed(JNIEnv *e,Model *m,const char *message){
    int status=cleanup(m);if(status)fail_cleanup(e,status);else fail(e,message);
}
JNIEXPORT jlong JNICALL Java_com_mirror_bench_RknnModel_openNative(JNIEnv *e,jclass c,jstring path) {
    (void)c;
    if(atomic_load(&native_cleanup_fault)){fail_cleanup(e,-1003);return 0;}
    const char *p=(*e)->GetStringUTFChars(e,path,NULL); if(!p) return 0;
    FILE *f=fopen(p,"rb"); (*e)->ReleaseStringUTFChars(e,path,p);
    if(!f) { fail(e,"Cannot open RKNN model"); return 0; }
    fseek(f,0,SEEK_END); long n=ftell(f); rewind(f);
    if(n<=0||n>64*1024*1024) { fclose(f); fail(e,"Invalid RKNN model size"); return 0; }
    void *data=malloc(n); if(!data) { fclose(f); fail(e,"Model allocation failed"); return 0; }
    if(fread(data,1,n,f)!=(size_t)n) { free(data); fclose(f); fail(e,"Incomplete RKNN model"); return 0; }
    fclose(f);
    Model *m=calloc(1,sizeof(*m)); if(!m) { free(data); fail(e,"Context allocation failed"); return 0; }
    m->library=dlopen("librknnrt.so",RTLD_NOW|RTLD_LOCAL);
    int (*init)(rknn_context*,void*,uint32_t,uint32_t,rknn_init_extend*)=NULL;
    if(m->library) {
        init=dlsym(m->library,"rknn_init"); m->query=dlsym(m->library,"rknn_query");
        m->set=dlsym(m->library,"rknn_inputs_set"); m->run=dlsym(m->library,"rknn_run");
        m->get=dlsym(m->library,"rknn_outputs_get"); m->release=dlsym(m->library,"rknn_outputs_release");
        m->destroy=dlsym(m->library,"rknn_destroy");
    }
    int status=-1;
    if(init&&m->query&&m->set&&m->run&&m->get&&m->release&&m->destroy)
        status=init(&m->context,data,(uint32_t)n,0,NULL);
    free(data);
    if(!status) status=m->query(m->context,RKNN_QUERY_IN_OUT_NUM,&m->counts,sizeof(m->counts));
    if(status||m->counts.n_input!=1||m->counts.n_output<1||m->counts.n_output>8) {
        initialization_failed(e,m,"RKNN initialization or tensor count failed"); return 0;
    }
    status=m->query(m->context,RKNN_QUERY_INPUT_ATTR,&m->input,sizeof(m->input));
    for(uint32_t i=0;i<m->counts.n_output&&!status;i++) {
        m->outputs[i].index=i;
        status=m->query(m->context,RKNN_QUERY_OUTPUT_ATTR,&m->outputs[i],sizeof(m->outputs[i]));
        if(!m->outputs[i].n_elems||m->outputs[i].n_elems>2000000) status=-1;
    }
    if(status||!m->input.n_elems||m->input.n_elems>2000000) {
        initialization_failed(e,m,"Invalid RKNN tensor attributes"); return 0;
    }
    // A different context may have failed cleanup while this initialization was in flight.
    if(atomic_load(&native_cleanup_fault)){
        int closed=cleanup(m);fail_cleanup(e,closed?closed:-1003);return 0;
    }
    return (jlong)(intptr_t)m;
}
JNIEXPORT jstring JNICALL Java_com_mirror_bench_RknnModel_infoNative(JNIEnv *e,jclass c,jlong h) {
    (void)c; Model *m=(Model*)(intptr_t)h;
    if(atomic_load(&native_cleanup_fault)){fail_cleanup(e,-1003);return NULL;}
    if(!m||m->cleanup_attempted) { fail(e,"Closed or failed model"); return NULL; }
    rknn_sdk_version v={0}; int status=m->query(m->context,RKNN_QUERY_SDK_VERSION,&v,sizeof(v));
    if(status) { fail(e,"RKNN version query failed"); return NULL; }
    char json[4096]; int pos=snprintf(json,sizeof(json),"{\"runtime\":\"%s\",\"driver\":\"%s\",\"input_elements\":%u,\"outputs\":[",v.api_version,v.drv_version,m->input.n_elems);
    for(uint32_t i=0;i<m->counts.n_output;i++) {
        pos+=snprintf(json+pos,sizeof(json)-pos,"%s{\"index\":%u,\"name\":\"%s\",\"elements\":%u}",i?",":"",i,m->outputs[i].name,m->outputs[i].n_elems);
    }
    snprintf(json+pos,sizeof(json)-pos,"]}");
    return (*e)->NewStringUTF(e,json);
}
JNIEXPORT jobjectArray JNICALL Java_com_mirror_bench_RknnModel_runNative(JNIEnv *e,jclass c,jlong h,jobject pixels) {
    (void)c; Model *m=(Model*)(intptr_t)h;
    if(atomic_load(&native_cleanup_fault)){fail_cleanup(e,-1003);return NULL;}
    if(!m||m->cleanup_attempted) { fail(e,"Closed or failed model"); return NULL; }
    void *data=(*e)->GetDirectBufferAddress(e,pixels); jlong capacity=(*e)->GetDirectBufferCapacity(e,pixels);
    if(!data||capacity!=(jlong)m->input.n_elems*4) { fail(e,"Float32 input buffer shape mismatch"); return NULL; }
    rknn_input input={0}; input.buf=data; input.size=(uint32_t)capacity; input.type=RKNN_TENSOR_FLOAT32; input.fmt=RKNN_TENSOR_NHWC;
    rknn_output outputs[8]={0};
    for(uint32_t i=0;i<m->counts.n_output;i++) { outputs[i].index=i; outputs[i].want_float=1; }
    int status=m->set(m->context,1,&input); if(!status) status=m->run(m->context,NULL);
    if(!status) status=m->get(m->context,m->counts.n_output,outputs,NULL);
    if(status) { char error[100]; snprintf(error,sizeof(error),"RKNN inference returned %d",status); fail(e,error); return NULL; }
    jclass arrayType=(*e)->FindClass(e,"[F");
    jobjectArray result=arrayType?(*e)->NewObjectArray(e,m->counts.n_output,arrayType,NULL):NULL;
    for(uint32_t i=0;result&&i<m->counts.n_output&&!(*e)->ExceptionCheck(e);i++) {
        if(!outputs[i].buf||outputs[i].size<m->outputs[i].n_elems*4) { fail(e,"Incomplete RKNN output"); break; }
        jfloatArray row=(*e)->NewFloatArray(e,m->outputs[i].n_elems);
        if(!row) break;
        (*e)->SetFloatArrayRegion(e,row,0,m->outputs[i].n_elems,(float*)outputs[i].buf);
        (*e)->SetObjectArrayElement(e,result,i,row); (*e)->DeleteLocalRef(e,row);
    }
    status=m->release(m->context,m->counts.n_output,outputs);
    if(status&&!(*e)->ExceptionCheck(e)) fail(e,"RKNN output release failed");
    return (*e)->ExceptionCheck(e)?NULL:result;
}
JNIEXPORT void JNICALL Java_com_mirror_bench_RknnModel_closeNative(JNIEnv *e,jclass c,jlong h) {
    (void)c;int status=cleanup((Model*)(intptr_t)h);if(status)fail_cleanup(e,status);
}

/* Shared validation/locking for both implementations; no model context is needed. */
static void crop_bitmap(JNIEnv *e,jobject bitmap,jobject target,int reference,
        jint size,jfloat cx,jfloat cy,jfloat side,jfloat rotation,jfloat mean,jfloat std) {
    AndroidBitmapInfo info; void *pixels=NULL;
    float *out=(*e)->GetDirectBufferAddress(e,target);
    if(!out||size<1||size>1024||(*e)->GetDirectBufferCapacity(e,target)!=(jlong)size*size*3*4
            ||!isfinite(cx)||!isfinite(cy)||!isfinite(side)||side<=0||!isfinite(rotation)||!isfinite(mean)||!isfinite(std)||std<=0
            ||AndroidBitmap_getInfo(e,bitmap,&info)||info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888) {
        fail(e,"Invalid crop input"); return;
    }
    if(AndroidBitmap_lockPixels(e,bitmap,&pixels)) { fail(e,"Cannot lock crop bitmap"); return; }
    if(reference) mirror_crop_reference(pixels,(int)info.width,(int)info.height,(int)info.stride,out,size,cx,cy,side,rotation,mean,std);
    else mirror_crop_optimized(pixels,(int)info.width,(int)info.height,(int)info.stride,out,size,cx,cy,side,rotation,mean,std);
    AndroidBitmap_unlockPixels(e,bitmap);
}
/* MediaPipe OpenCV CPU crop convention: ROI corners map to (0,0),(size,size). */
JNIEXPORT void JNICALL Java_com_mirror_bench_RknnModel_cropNative(JNIEnv *e,jclass c,jobject bitmap,jobject target,
        jint size,jfloat cx,jfloat cy,jfloat side,jfloat rotation,jfloat mean,jfloat std) {
    (void)c; crop_bitmap(e,bitmap,target,0,size,cx,cy,side,rotation,mean,std);
}
JNIEXPORT void JNICALL Java_com_mirror_bench_RknnModel_cropReferenceNative(JNIEnv *e,jclass c,jobject bitmap,jobject target,
        jint size,jfloat cx,jfloat cy,jfloat side,jfloat rotation,jfloat mean,jfloat std) {
    (void)c; crop_bitmap(e,bitmap,target,1,size,cx,cy,side,rotation,mean,std);
}
