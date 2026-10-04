#include <jni.h>
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
static int pending,mode,allocations,frees,loads,unloads,destroys,opens,queries;
static unsigned checks;
static const char *model_path;
static char exception_type[160],exception_text[160];
static void check(int value){checks++;assert(value);}
static void *tracked_malloc(size_t size){void *p=malloc(size);if(p)allocations++;return p;}
static void *tracked_calloc(size_t count,size_t size){void *p=calloc(count,size);if(p)allocations++;return p;}
static void tracked_free(void *p){if(p)frees++;free(p);}
#define malloc tracked_malloc
#define calloc tracked_calloc
#define free tracked_free
#pragma warning(push)
#pragma warning(disable:4152 4244)
#include "../native/rknn_face.c"
#pragma warning(pop)
#undef malloc
#undef calloc
#undef free
static int stub_init(rknn_context *ctx,void *data,uint32_t size,uint32_t flags,rknn_init_extend *ext){
    check(data&&size==16&&flags==0&&!ext);opens++;*ctx=17;return mode==2||mode==3?-5:0;
}
static int stub_query(rknn_context ctx,rknn_query_cmd command,void *buffer,uint32_t size){
    (void)size;check(ctx==17);queries++;
    if(command==RKNN_QUERY_IN_OUT_NUM){rknn_input_output_num *n=buffer;n->n_input=1;n->n_output=1;}
    else if(command==RKNN_QUERY_INPUT_ATTR||command==RKNN_QUERY_OUTPUT_ATTR){rknn_tensor_attr *a=buffer;a->n_elems=256;}
    if(mode==5&&command==RKNN_QUERY_OUTPUT_ATTR)atomic_store(&native_cleanup_fault,1);
    return 0;
}
static int stub_set(rknn_context c,uint32_t n,rknn_input *i){(void)c;(void)n;(void)i;assert(0);return -1;}
static int stub_run(rknn_context c,rknn_run_extend *e){(void)c;(void)e;assert(0);return -1;}
static int stub_get(rknn_context c,uint32_t n,rknn_output *o,rknn_output_extend *e){(void)c;(void)n;(void)o;(void)e;assert(0);return -1;}
static int stub_release(rknn_context c,uint32_t n,rknn_output *o){(void)c;(void)n;(void)o;assert(0);return -1;}
static int stub_destroy(rknn_context ctx){check(ctx==17);destroys++;return mode==1||mode==3?-4:0;}
void *dlopen(const char *path,int flags){check(!strcmp(path,"librknnrt.so")&&flags==(RTLD_NOW|RTLD_LOCAL));loads++;return (void*)1;}
void *dlsym(void *library,const char *symbol){
    check(library==(void*)1);void *result=NULL;
#define LOAD(name,fn) if(!strcmp(symbol,name)){int (*function)()=(int (*)())fn;check(sizeof(result)==sizeof(function));memcpy(&result,&function,sizeof(result));return result;}
    LOAD("rknn_init",stub_init) LOAD("rknn_query",stub_query) LOAD("rknn_inputs_set",stub_set)
    LOAD("rknn_run",stub_run) LOAD("rknn_outputs_get",stub_get) LOAD("rknn_outputs_release",stub_release) LOAD("rknn_destroy",stub_destroy)
#undef LOAD
    return NULL;
}
int dlclose(void *library){check(library==(void*)1);unloads++;return mode==4?-9:0;}
int AndroidBitmap_getInfo(JNIEnv *e,jobject b,AndroidBitmapInfo *i){(void)e;(void)b;(void)i;assert(0);return -1;}
int AndroidBitmap_lockPixels(JNIEnv *e,jobject b,void **p){(void)e;(void)b;(void)p;assert(0);return -1;}
int AndroidBitmap_unlockPixels(JNIEnv *e,jobject b){(void)e;(void)b;assert(0);return -1;}
static jboolean JNICALL exception_check(JNIEnv *e){(void)e;return (jboolean)pending;}
static jclass JNICALL find_class(JNIEnv *e,const char *name){(void)e;return (jclass)name;}
static jint JNICALL throw_new(JNIEnv *e,jclass type,const char *text){
    (void)e;pending=1;snprintf(exception_type,sizeof(exception_type),"%s",(const char*)type);
    snprintf(exception_text,sizeof(exception_text),"%s",text);return 0;
}
static const char *JNICALL chars(JNIEnv *e,jstring text,jboolean *copy){(void)e;(void)text;(void)copy;return model_path;}
static void JNICALL release_chars(JNIEnv *e,jstring text,const char *value){(void)e;(void)text;check(value==model_path);}
static struct JNINativeInterface_ table={0};
int main(int argc,char **argv){
    check(argc==3);mode=atoi(argv[1]);model_path=argv[2];
    table.ExceptionCheck=exception_check;table.FindClass=find_class;table.ThrowNew=throw_new;
    table.GetStringUTFChars=chars;table.ReleaseStringUTFChars=release_chars;JNIEnv env=&table;
    jlong handle=Java_com_mirror_bench_RknnModel_openNative(&env,NULL,(jstring)"path");
    if(mode==5){
        check(!handle&&pending);check(!strcmp(exception_type,"com/mirror/bench/NativeCleanupUnconfirmed"));
        check(allocations==frees&&destroys==1&&unloads==1);
        pending=0;int previous_opens=opens;
        check(!Java_com_mirror_bench_RknnModel_openNative(&env,NULL,(jstring)"path"));check(pending&&opens==previous_opens);
    }else if(mode==2){
        check(!handle&&pending);check(!strcmp(exception_type,"java/lang/IllegalStateException"));
        check(allocations==frees&&destroys==1&&unloads==1);
        pending=0;mode=0;handle=Java_com_mirror_bench_RknnModel_openNative(&env,NULL,(jstring)"path");check(handle&&!pending);
        Java_com_mirror_bench_RknnModel_closeNative(&env,NULL,handle);check(allocations==frees);
    }else if(mode==3){
        check(!handle&&pending);check(!strcmp(exception_type,"com/mirror/bench/NativeCleanupUnconfirmed"));
        check(destroys==1&&unloads==0&&allocations==frees+1);
        pending=0;int previous_opens=opens;
        check(!Java_com_mirror_bench_RknnModel_openNative(&env,NULL,(jstring)"path"));check(pending&&opens==previous_opens);
    }else{
        check(handle&&!pending);Java_com_mirror_bench_RknnModel_closeNative(&env,NULL,handle);
        if(mode==0){check(!pending&&destroys==1&&unloads==1&&allocations==frees);}
        else{
            check(pending);check(!strcmp(exception_type,"com/mirror/bench/NativeCleanupUnconfirmed"));
            check(destroys==1&&allocations==frees+1);check(unloads==(mode==4?1:0));
            pending=0;Java_com_mirror_bench_RknnModel_closeNative(&env,NULL,handle);check(pending&&destroys==1);
            check(unloads==(mode==4?1:0)); // Never retry ambiguous native destruction/unload.
            pending=0;int previous_queries=queries;
            check(!Java_com_mirror_bench_RknnModel_infoNative(&env,NULL,handle));check(pending&&queries==previous_queries);
            pending=0;int previous_opens=opens;
            check(!Java_com_mirror_bench_RknnModel_openNative(&env,NULL,(jstring)"path"));check(pending&&opens==previous_opens);
        }
    }
    printf("RKNN face cleanup mode %s: %u checks passed; actual JNI source, fake VM/loader/driver\n",argv[1],checks);return 0;
}
