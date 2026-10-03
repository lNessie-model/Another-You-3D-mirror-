/* Isolated diagnostic executable. Does not install or alter app models, runtimes or drivers. */
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <limits.h>
#include <string.h>
#include <math.h>
#include <dlfcn.h>
#include <time.h>
#include "rknn_api.h"
#include "rknn_blendshape_contract.h"

#define INPUT_FLOATS 292u
#define OUTPUT_FLOATS 52u
#define WARMUP 5u
#define NOT_CALLED INT_MAX
static double ms(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec*1000.0+t.tv_nsec/1e6;}
static void json_string(FILE *f,const char *s,size_t limit){
    fputc('"',f);for(size_t i=0;s&&i<limit&&s[i];i++){unsigned char c=(unsigned char)s[i];
        if(c=='"'||c=='\\'){fputc('\\',f);fputc(c,f);}else if(c<32||c>126)fprintf(f,"\\u%04x",c);else fputc(c,f);
    }fputc('"',f);
}
static void code(FILE *f,int value){if(value==NOT_CALLED)fputs("null",f);else fprintf(f,"%d",value);}
static void attr(FILE *f,const char *kind,int rc,const rknn_tensor_attr *a){
    fprintf(f,"{\"event\":\"tensor_attr\",\"kind\":\"%s\",\"rc\":%d,\"index\":%u,\"name\":",kind,rc,a->index);
    json_string(f,a->name,sizeof(a->name));fprintf(f,",\"n_dims\":%u,\"dims\":[",a->n_dims);
    for(uint32_t i=0;i<a->n_dims&&i<RKNN_MAX_DIMS;i++)fprintf(f,"%s%u",i?",":"",a->dims[i]);
    fprintf(f,"],\"n_elems\":%u,\"size\":%u,\"fmt\":%d,\"type\":%d,\"qnt_type\":%d,\"zp\":%d,\"scale\":",
            a->n_elems,a->size,a->fmt,a->type,a->qnt_type,a->zp);
    if(isfinite(a->scale))fprintf(f,"%.9g",a->scale);else fputs("null",f);
    fprintf(f,",\"w_stride\":%u,\"size_with_stride\":%u}\n",a->w_stride,a->size_with_stride);
}
static unsigned char *read_file(const char *path,size_t limit,size_t *size){
    FILE *f=fopen(path,"rb");if(!f)return NULL;
    if(fseek(f,0,SEEK_END)){fclose(f);return NULL;}long n=ftell(f);
    if(n<=0||(uint64_t)n>limit||fseek(f,0,SEEK_SET)){fclose(f);return NULL;}
    unsigned char *p=malloc((size_t)n);if(!p){fclose(f);return NULL;}
    if(fread(p,1,(size_t)n,f)!=(size_t)n){free(p);fclose(f);return NULL;}fclose(f);*size=(size_t)n;return p;
}
static int number(const char *s,unsigned *result){char *end=NULL;unsigned long n=strtoul(s,&end,10);if(!s[0]||s[0]=='-'||*end||n<1||n>100000)return 0;*result=(unsigned)n;return 1;}
int main(int argc,char **argv){
    unsigned cases=0,cycles=0;
    if(argc!=8||!number(argv[4],&cases)||!number(argv[5],&cycles)||cases>4096||(uint64_t)cases*cycles>100000){
        fprintf(stderr,"usage: rknn_blendshape_probe /absolute/runtime.so model.rknn inputs.f32 cases cycles report.jsonl outputs.f32\n");return 2;}
    if(argv[1][0]!='/'){fprintf(stderr,"Runtime must be an explicit absolute path\n");return 2;}
    FILE *report=fopen(argv[6],"wx");if(!report){perror("new report");return 3;}
    FILE *outputs=fopen(argv[7],"wbx");if(!outputs){perror("new outputs");fclose(report);return 3;}
    setvbuf(report,NULL,_IOLBF,0);
    fprintf(report,"{\"event\":\"start\",\"schema_version\":1,\"cases\":%u,\"cycles\":%u,\"warmup\":%u,\"input_floats\":292,\"output_floats\":52,\"runtime_path\":",cases,cycles,WARMUP);
    json_string(report,argv[1],4096);fputs(",\"scope\":\"CPU FP32 fixed-front normalized [1,146,2] input; isolated mixed CPU/NPU suffix; not full face pipeline or render FPS\"}\n",report);
    int status=0,initialized=0,destroy_rc=NOT_CALLED;unsigned completed=0;
    size_t model_size=0,input_size=0;unsigned char *model=NULL,*input_data=NULL;void *library=NULL;rknn_context ctx=0;
    int (*init)(rknn_context*,void*,uint32_t,uint32_t,rknn_init_extend*)=NULL;
    int (*query)(rknn_context,rknn_query_cmd,void*,uint32_t)=NULL;
    int (*set)(rknn_context,uint32_t,rknn_input*)=NULL;int (*run)(rknn_context,rknn_run_extend*)=NULL;
    int (*get)(rknn_context,uint32_t,rknn_output*,rknn_output_extend*)=NULL;
    int (*release)(rknn_context,uint32_t,rknn_output*)=NULL;int (*destroy)(rknn_context)=NULL;
    model=read_file(argv[2],64u*1024u*1024u,&model_size);input_data=read_file(argv[3],4096u*INPUT_FLOATS*sizeof(float),&input_size);
    if(!model||!input_data||input_size!=(size_t)cases*INPUT_FLOATS*sizeof(float)){status=4;goto finish;}
    for(size_t i=0;i<input_size/sizeof(float);i++)if(!isfinite(((float*)input_data)[i])){status=5;goto finish;}
    library=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL);
    if(!library){fprintf(report,"{\"event\":\"dlopen_error\",\"message\":");json_string(report,dlerror(),2048);fputs("}\n",report);status=6;goto finish;}
    init=dlsym(library,"rknn_init");query=dlsym(library,"rknn_query");set=dlsym(library,"rknn_inputs_set");run=dlsym(library,"rknn_run");
    get=dlsym(library,"rknn_outputs_get");release=dlsym(library,"rknn_outputs_release");destroy=dlsym(library,"rknn_destroy");
    if(!init||!query||!set||!run||!get||!release||!destroy){status=7;goto finish;}
    double before=ms();int rc=init(&ctx,model,(uint32_t)model_size,0,NULL);
    fprintf(report,"{\"event\":\"init\",\"rc\":%d,\"elapsed_ms\":%.6f,\"model_bytes\":%zu,\"input_bytes\":%zu,\"flags\":0}\n",rc,ms()-before,model_size,input_size);
    if(rc){status=8;goto finish;}initialized=1;
    rknn_sdk_version version={0};rc=query(ctx,RKNN_QUERY_SDK_VERSION,&version,sizeof(version));
    fprintf(report,"{\"event\":\"sdk\",\"rc\":%d,\"api\":",rc);json_string(report,version.api_version,sizeof(version.api_version));fputs(",\"driver\":",report);json_string(report,version.drv_version,sizeof(version.drv_version));fputs("}\n",report);
    if(rc){status=9;goto finish;}
    rknn_input_output_num counts={0};rc=query(ctx,RKNN_QUERY_IN_OUT_NUM,&counts,sizeof(counts));
    fprintf(report,"{\"event\":\"io_count\",\"rc\":%d,\"inputs\":%u,\"outputs\":%u}\n",rc,counts.n_input,counts.n_output);
    if(rc||counts.n_input!=1||counts.n_output!=1){status=10;goto finish;}
    rknn_tensor_attr in={0},out={0};rc=query(ctx,RKNN_QUERY_INPUT_ATTR,&in,sizeof(in));attr(report,"input",rc,&in);if(rc){status=11;goto finish;}
    rc=query(ctx,RKNN_QUERY_OUTPUT_ATTR,&out,sizeof(out));attr(report,"output",rc,&out);if(rc){status=11;goto finish;}
    if(!input_contract(&in)||!shape(&out,0)){status=12;goto finish;}
    fprintf(report,"{\"event\":\"feed_contract\",\"type\":%d,\"fmt\":%d,\"pass_through\":0,\"input_order\":\"CPU FP32 fixed-front normalized C-order [1,146,2], adjacent x/y; no preprocessing or transpose in helper\",\"want_float\":1}\n",RKNN_TENSOR_FLOAT32,in.fmt);
    for(unsigned index=0;index<WARMUP+cases*cycles;index++){
        int warming=index<WARMUP;unsigned iteration=warming?index:index-WARMUP,fixture=iteration%cases;
        rknn_input feed={0};feed.buf=input_data+(size_t)fixture*INPUT_FLOATS*sizeof(float);feed.size=INPUT_FLOATS*sizeof(float);feed.type=RKNN_TENSOR_FLOAT32;feed.fmt=in.fmt;
        rknn_output result={0};result.want_float=1;
        int set_rc=NOT_CALLED,run_rc=NOT_CALLED,get_rc=NOT_CALLED,release_rc=NOT_CALLED;
        double start=ms(),set_ms=0,run_ms=0,get_ms=0,release_ms=0;unsigned nonfinite=0;float lo=INFINITY,hi=-INFINITY;
        before=ms();set_rc=set(ctx,1,&feed);set_ms=ms()-before;
        if(!set_rc){before=ms();run_rc=run(ctx,NULL);run_ms=ms()-before;}
        if(!set_rc&&!run_rc){before=ms();get_rc=get(ctx,1,&result,NULL);get_ms=ms()-before;}
        uint32_t output_bytes=result.size;int valid_output=get_rc==0&&result.buf&&result.size>=OUTPUT_FLOATS*sizeof(float);
        float values[OUTPUT_FLOATS];
        if(valid_output){memcpy(values,result.buf,sizeof(values));for(unsigned j=0;j<OUTPUT_FLOATS;j++){if(!isfinite(values[j]))nonfinite++;else{if(values[j]<lo)lo=values[j];if(values[j]>hi)hi=values[j];}}}
        if(get_rc==0||result.buf){before=ms();release_rc=release(ctx,1,&result);release_ms=ms()-before;}
        double total=ms()-start;
        int okay=set_rc==0&&run_rc==0&&get_rc==0&&release_rc==0&&valid_output&&nonfinite==0;
        long offset=-1;
        if(okay&&!warming){offset=(long)completed*OUTPUT_FLOATS;
            if(fwrite(values,sizeof(float),OUTPUT_FLOATS,outputs)!=OUTPUT_FLOATS)okay=0;else completed++;}
        fprintf(report,"{\"event\":\"iteration\",\"phase\":\"%s\",\"iteration\":%u,\"fixture\":%u,\"cycle\":%u,\"return_codes\":{\"inputs_set\":",warming?"warmup":"measurement",iteration,fixture,iteration/cases);
        code(report,set_rc);fputs(",\"run\":",report);code(report,run_rc);fputs(",\"outputs_get\":",report);code(report,get_rc);fputs(",\"outputs_release\":",report);code(report,release_rc);
        fprintf(report,"},\"timing_ms\":{\"inputs_set\":%.6f,\"run\":%.6f,\"outputs_get\":%.6f,\"outputs_release\":%.6f,\"total\":%.6f},\"output_bytes\":%u,\"nonfinite\":%u,\"output_min\":",set_ms,run_ms,get_ms,release_ms,total,output_bytes,nonfinite);
        if(isfinite(lo))fprintf(report,"%.9g",lo);else fputs("null",report);fputs(",\"output_max\":",report);if(isfinite(hi))fprintf(report,"%.9g",hi);else fputs("null",report);
        fprintf(report,",\"output_offset_floats\":%ld,\"ok\":%s}\n",offset,okay?"true":"false");
        if(!okay||ferror(report)){status=13;break;}
    }
finish:
    if(initialized){destroy_rc=destroy(ctx);if(destroy_rc&&!status)status=14;}
    if(library)dlclose(library);free(model);free(input_data);
    if(fflush(outputs)&&!status)status=15;if(fclose(outputs)&&!status)status=15;
    fprintf(report,"{\"event\":\"finish\",\"status\":\"%s\",\"exit_code\":%d,\"completed_measurements\":%u,\"expected_measurements\":%u,\"destroy_rc\":",status?"error":"success",status,completed,cases*cycles);code(report,destroy_rc);fputs("}\n",report);
    if(fclose(report)&&!status)status=16;
    printf("{\"exit_code\":%d,\"completed_measurements\":%u}\n",status,completed);return status;
}
