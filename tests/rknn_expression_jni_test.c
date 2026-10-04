#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>
#include "../native/rknn_expression_core.h"
static int pending,mode,allocated,freed,loads,unloads,destroys,releases,strings_released,elements_released,arrays,deleted,throws;
static unsigned checks;static float input_values[292],native_values[52],java_values[52];static char info_text[4096];
static void check(int value){checks++;assert(value);}
static void *tracked_calloc(size_t n,size_t size){if(mode==2)return NULL;allocated++;return calloc(n,size);}
static void tracked_free(void *p){if(p)freed++;free(p);}
#define calloc tracked_calloc
#define free tracked_free
/* POSIX dlsym returns object pointers convertible to function pointers on Android.
 * MSVC's host-only emulation diagnoses that supported Android idiom as an extension. */
#pragma warning(push)
#pragma warning(disable:4152)
#include "../native/rknn_expression.c"
#pragma warning(pop)
#undef calloc
#undef free
static int stub_init(rknn_context *ctx,void *model,uint32_t size,uint32_t flags,rknn_init_extend *e){check(model!=NULL&&size==1209569&&flags==0&&!e);*ctx=17;return mode==6?-1:0;}
static int stub_query(rknn_context ctx,rknn_query_cmd command,void *buffer,uint32_t size){
    (void)size;check(ctx==17);if(mode==7)return -2;
    if(command==RKNN_QUERY_SDK_VERSION){rknn_sdk_version *v=buffer;strcpy(v->api_version,EXPRESSION_SDK);strcpy(v->drv_version,EXPRESSION_DRIVER);}
    else if(command==RKNN_QUERY_IN_OUT_NUM){rknn_input_output_num *n=buffer;n->n_input=n->n_output=1;}
    else {rknn_tensor_attr *a=buffer;int out=command==RKNN_QUERY_OUTPUT_ATTR;memset(a,0,sizeof(*a));a->n_dims=out?1:3;a->dims[0]=out?52:1;if(!out){a->dims[1]=146;a->dims[2]=2;}strcpy(a->name,out?EXPRESSION_OUTPUT_NAME:EXPRESSION_INPUT_NAME);a->n_elems=out?52:292;a->size=out?104:584;a->size_with_stride=a->size;a->fmt=RKNN_TENSOR_UNDEFINED;a->type=RKNN_TENSOR_FLOAT16;a->qnt_type=RKNN_TENSOR_QNT_AFFINE_ASYMMETRIC;a->scale=1;}
    return 0;
}
static int stub_set(rknn_context ctx,uint32_t n,rknn_input *in){check(ctx==17&&n==1&&in->buf!=input_values&&in->size==1168);check(((float*)in->buf)[291]==291);return 0;}
static int stub_run(rknn_context ctx,rknn_run_extend *e){check(ctx==17&&!e);return 0;}
static int stub_get(rknn_context ctx,uint32_t n,rknn_output *out,rknn_output_extend *e){check(ctx==17&&n==1&&!e&&out->want_float==1);out->buf=native_values;out->size=208;return mode==8?-3:0;}
static int stub_release(rknn_context ctx,uint32_t n,rknn_output *out){check(ctx==17&&n==1&&out->buf==native_values);releases++;for(int i=0;i<52;i++)native_values[i]=-999;return 0;}
static int stub_destroy(rknn_context ctx){check(ctx==17);destroys++;return mode==12?-4:0;}
static ExpressionApi stub_api={stub_init,stub_query,stub_set,stub_run,stub_get,stub_release,stub_destroy};
void *dlopen(const char *path,int flags){check(!strcmp(path,"/data/app/base.apk!/lib/arm64-v8a/librknnrt.so")&&flags==RTLD_NOW);loads++;return mode==3?NULL:(void*)1;}
void *dlsym(void *library,const char *symbol){
    check(library==(void*)1);if(mode==4)return NULL;void *result=NULL;
#define LOAD(name,field) if(!strcmp(symbol,name)){check(sizeof(result)==sizeof(stub_api.field));memcpy(&result,&stub_api.field,sizeof(result));return result;}
    LOAD("rknn_init",init) LOAD("rknn_query",query) LOAD("rknn_inputs_set",set) LOAD("rknn_run",run) LOAD("rknn_outputs_get",get) LOAD("rknn_outputs_release",release) LOAD("rknn_destroy",destroy)
#undef LOAD
    return NULL;
}
int dlclose(void *library){check(library==(void*)1);unloads++;return 0;}
static jboolean JNICALL exception_check(JNIEnv *e){(void)e;return (jboolean)pending;}
static jclass JNICALL find_class(JNIEnv *e,const char *name){(void)e;return (jclass)name;}
static jint JNICALL throw_new(JNIEnv *e,jclass type,const char *message){(void)e;check(type&&message);pending=1;throws++;return 0;}
static void JNICALL delete_ref(JNIEnv *e,jobject ref){(void)e;check(ref!=NULL);deleted++;}
static jsize JNICALL array_length(JNIEnv *e,jarray a){(void)e;check(a!=NULL);return 1209569;}
static const char *JNICALL string_chars(JNIEnv *e,jstring text,jboolean *copied){(void)e;(void)copied;check(text!=NULL);if(mode==1){pending=1;return NULL;}return "/data/app/base.apk!/lib/arm64-v8a/librknnrt.so";}
static void JNICALL release_string(JNIEnv *e,jstring text,const char *s){(void)e;check(text&&s);strings_released++;}
static jbyte *JNICALL array_elements(JNIEnv *e,jbyteArray a,jboolean *copy){(void)e;(void)copy;check(a!=NULL);if(mode==5){pending=1;return NULL;}return (jbyte*)input_values;}
static void JNICALL release_elements(JNIEnv *e,jbyteArray a,jbyte *v,jint how){(void)e;check(a&&v&&how==JNI_ABORT);elements_released++;}
static jstring JNICALL new_string(JNIEnv *e,const char *text){(void)e;if(mode==13){pending=1;return NULL;}check(strlen(text)<sizeof(info_text));strcpy(info_text,text);return (jstring)info_text;}
static void *JNICALL buffer_address(JNIEnv *e,jobject b){(void)e;check(b!=NULL);return input_values;}
static jlong JNICALL buffer_capacity(JNIEnv *e,jobject b){(void)e;check(b!=NULL);return mode==9?1164:1168;}
static jfloatArray JNICALL new_array(JNIEnv *e,jsize size){(void)e;check(size==52&&releases==1);arrays++;if(mode==10){pending=1;return NULL;}return (jfloatArray)java_values;}
static void JNICALL set_region(JNIEnv *e,jfloatArray a,jsize start,jsize count,const jfloat *data){(void)e;check(a&&start==0&&count==52&&releases==1);if(mode==11){pending=1;return;}memcpy(java_values,data,208);}
static struct JNINativeInterface_ table={0};
static void reset(int new_mode){mode=new_mode;pending=allocated=freed=loads=unloads=destroys=releases=strings_released=elements_released=arrays=deleted=throws=0;for(int i=0;i<292;i++)input_values[i]=(float)i;for(int i=0;i<52;i++)native_values[i]=(float)i/51;}
int main(void){
    table.ExceptionCheck=exception_check;table.FindClass=find_class;table.ThrowNew=throw_new;table.DeleteLocalRef=delete_ref;table.GetArrayLength=array_length;
    table.GetStringUTFChars=string_chars;table.ReleaseStringUTFChars=release_string;table.GetByteArrayElements=array_elements;table.ReleaseByteArrayElements=release_elements;
    table.NewStringUTF=new_string;table.GetDirectBufferAddress=buffer_address;table.GetDirectBufferCapacity=buffer_capacity;table.NewFloatArray=new_array;table.SetFloatArrayRegion=set_region;
    JNIEnv env=&table;jbyteArray data=(jbyteArray)input_values;jstring path=(jstring)"path";jobject input=(jobject)input_values;
    reset(0);jlong handle=Java_com_mirror_bench_RknnExpression_openNative(&env,NULL,data,path);check(handle&&!pending&&elements_released==1&&strings_released==1);
    check(Java_com_mirror_bench_RknnExpression_infoNative(&env,NULL,handle)!=NULL);check(strstr(info_text,"\"qnt_type\":2")!=NULL);
    check(Java_com_mirror_bench_RknnExpression_runNative(&env,NULL,handle,input)!=NULL);for(int i=0;i<52;i++)check(java_values[i]==(float)i/51);
    Java_com_mirror_bench_RknnExpression_closeNative(&env,NULL,handle);check(!pending&&destroys==1&&unloads==1&&allocated==freed);
    for(int failure=1;failure<=7;failure++){
        reset(failure);check(!Java_com_mirror_bench_RknnExpression_openNative(&env,NULL,data,path));check(pending&&allocated==freed);check(destroys==(failure>=6));check(unloads==(failure>=4));
        if(failure==1||failure==5)check(throws==0); // Preserve existing JNI OOM instead of replacing it.
    }
    for(int failure=8;failure<=13;failure++){
        reset(0);handle=Java_com_mirror_bench_RknnExpression_openNative(&env,NULL,data,path);check(handle!=0);mode=failure;
        if(failure==13)check(Java_com_mirror_bench_RknnExpression_infoNative(&env,NULL,handle)==NULL);
        else if(failure!=12)check(Java_com_mirror_bench_RknnExpression_runNative(&env,NULL,handle,input)==NULL);
        if(failure==8)check(releases==1&&arrays==0);
        if(failure==9)check(releases==0&&arrays==0);
        if(failure==10)check(releases==1&&arrays==1&&deleted==0&&throws==0);
        if(failure==11)check(releases==1&&arrays==1&&deleted==1&&throws==0);
        Java_com_mirror_bench_RknnExpression_closeNative(&env,NULL,handle);check(pending&&destroys==1&&unloads==1&&allocated==freed);
    }
    printf("Rknn expression JNI ownership: %u checks passed (real JNI header, fake VM/loader/RKNN)\n",checks);return 0;
}
