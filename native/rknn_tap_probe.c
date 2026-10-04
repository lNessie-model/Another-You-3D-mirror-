/* Fixed five-tap diagnostic only. Does not alter any app model, runtime or driver. */
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <limits.h>
#include <math.h>
#include <dlfcn.h>
#include <time.h>
#include "rknn_tap_iteration.h"
#define CASES 92u
#define WARMUP 5u
static double now_ms(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec*1000.0+t.tv_nsec/1e6;}
static void string(FILE *f,const char *s,size_t bound){
    fputc('"',f);for(size_t i=0;s&&i<bound&&s[i];i++){unsigned char c=(unsigned char)s[i];if(c=='"'||c=='\\'){fputc('\\',f);fputc(c,f);}else if(c<32||c>126)fprintf(f,"\\u%04x",c);else fputc(c,f);}fputc('"',f);
}
static void code(FILE *f,int rc){if(rc==INT_MAX)fputs("null",f);else fprintf(f,"%d",rc);}
typedef struct {FILE *file;const char *phase;int iteration,tensor;unsigned sequence;double started;} trace_state;
static void trace(void *owner,const char *api,int begin,int rc){
    trace_state *s=(trace_state*)owner;
    if(begin){
        fprintf(s->file,"{\"event\":\"api_begin\",\"sequence\":%u,\"phase\":\"%s\",\"iteration\":%d,\"tensor\":%d,\"api\":\"%s\"}\n",s->sequence,s->phase,s->iteration,s->tensor,api);
        fflush(s->file);s->started=now_ms();
    }else{
        double elapsed=now_ms()-s->started;
        fprintf(s->file,"{\"event\":\"api_end\",\"sequence\":%u,\"phase\":\"%s\",\"iteration\":%d,\"tensor\":%d,\"api\":\"%s\",\"rc\":%d,\"elapsed_ms\":%.6f}\n",s->sequence,s->phase,s->iteration,s->tensor,api,rc,elapsed);s->sequence++;
    }
}
static void attr(FILE *f,const char *kind,const rknn_tensor_attr *a){
    fprintf(f,"{\"event\":\"tensor_attr\",\"kind\":\"%s\",\"index\":%u,\"name\":",kind,a->index);string(f,a->name,sizeof(a->name));
    fprintf(f,",\"n_dims\":%u,\"dims\":[",a->n_dims);for(uint32_t i=0;i<a->n_dims&&i<RKNN_MAX_DIMS;i++)fprintf(f,"%s%u",i?",":"",a->dims[i]);
    fprintf(f,"],\"n_elems\":%u,\"size\":%u,\"fmt\":%d,\"type\":%d,\"qnt_type\":%d,\"zp\":%d,\"scale\":",a->n_elems,a->size,a->fmt,a->type,a->qnt_type,a->zp);
    if(isfinite(a->scale))fprintf(f,"%.9g",a->scale);else fputs("null",f);
    fprintf(f,",\"w_stride\":%u,\"size_with_stride\":%u}\n",a->w_stride,a->size_with_stride);
}
static unsigned char *read_file(const char *path,size_t max,size_t *length){
    FILE *f=fopen(path,"rb");if(!f)return NULL;if(fseek(f,0,SEEK_END)){fclose(f);return NULL;}long bytes=ftell(f);
    if(bytes<=0||(uint64_t)bytes>max||fseek(f,0,SEEK_SET)){fclose(f);return NULL;}unsigned char *data=malloc((size_t)bytes);
    if(!data){fclose(f);return NULL;}if(fread(data,1,(size_t)bytes,f)!=(size_t)bytes){free(data);fclose(f);return NULL;}fclose(f);*length=(size_t)bytes;return data;
}
int main(int argc,char **argv){
    if(argc!=6||argv[1][0]!='/'){fprintf(stderr,"usage: rknn_tap_probe /absolute/runtime.so model.rknn inputs.f32 new-report.jsonl existing-output-directory\n");return 2;}
    FILE *report=fopen(argv[4],"wx");if(!report){perror("new report");return 3;}setvbuf(report,NULL,_IOLBF,0);
    FILE *files[TAP_COUNT]={0};int status=0,initialized=0,destroy_rc=INT_MAX;unsigned completed=0;
    unsigned char *model=NULL,*inputs=NULL;size_t model_size=0,input_size=0;void *library=NULL;rknn_context ctx=0;
    int (*init)(rknn_context*,void*,uint32_t,uint32_t,rknn_init_extend*)=NULL;
    int (*query)(rknn_context,rknn_query_cmd,void*,uint32_t)=NULL;int (*destroy)(rknn_context)=NULL;
    tap_api api={0};trace_state tracing={report,"setup",-1,-1,0,0};
    fprintf(report,"{\"event\":\"start\",\"schema_version\":1,\"kind\":\"%s\",\"cases\":92,\"warmup\":5,\"input_floats\":292,\"outputs\":5,\"output_floats_per_case\":%u,\"runtime_path\":",TAP_KIND,TAP_TOTAL);string(report,argv[1],4096);fputs(",\"performance_evidence\":false}\n",report);
    for(unsigned i=0;i<TAP_COUNT;i++){
        char path[1024];int length=snprintf(path,sizeof(path),"%s/output-%s.f32",argv[5],tap_labels[i]);
        if(length<0||(size_t)length>=sizeof(path)||(files[i]=fopen(path,"wbx"))==NULL){status=3;goto finish;}
    }
    model=read_file(argv[2],64u*1024u*1024u,&model_size);inputs=read_file(argv[3],CASES*TAP_INPUT*sizeof(float),&input_size);
    if(!model||!inputs||input_size!=CASES*TAP_INPUT*sizeof(float)){status=4;goto finish;}
    for(unsigned i=0;i<CASES*TAP_INPUT;i++)if(!isfinite(((float*)inputs)[i])){status=5;goto finish;}
    fprintf(report,"{\"event\":\"files_loaded\",\"model_bytes\":%zu,\"input_bytes\":%zu}\n",model_size,input_size);
    library=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL);if(!library){fputs("{\"event\":\"dlopen_error\",\"message\":",report);string(report,dlerror(),2048);fputs("}\n",report);status=6;goto finish;}
    init=dlsym(library,"rknn_init");query=dlsym(library,"rknn_query");destroy=dlsym(library,"rknn_destroy");
    api.set=dlsym(library,"rknn_inputs_set");api.run=dlsym(library,"rknn_run");api.get=dlsym(library,"rknn_outputs_get");api.release=dlsym(library,"rknn_outputs_release");
    if(!init||!query||!destroy||!api.set||!api.run||!api.get||!api.release){status=7;goto finish;}
    trace(&tracing,"init",1,0);int rc=init(&ctx,model,(uint32_t)model_size,0,NULL);trace(&tracing,"init",0,rc);
    if(rc){status=8;goto finish;}initialized=1;
    rknn_sdk_version sdk={0};trace(&tracing,"query_sdk",1,0);rc=query(ctx,RKNN_QUERY_SDK_VERSION,&sdk,sizeof(sdk));trace(&tracing,"query_sdk",0,rc);
    fputs("{\"event\":\"sdk\",\"api\":",report);string(report,sdk.api_version,sizeof(sdk.api_version));fputs(",\"driver\":",report);string(report,sdk.drv_version,sizeof(sdk.drv_version));fputs("}\n",report);
    if(rc){status=9;goto finish;}
    rknn_input_output_num count={0};trace(&tracing,"query_io_count",1,0);rc=query(ctx,RKNN_QUERY_IN_OUT_NUM,&count,sizeof(count));trace(&tracing,"query_io_count",0,rc);
    fprintf(report,"{\"event\":\"io_count\",\"inputs\":%u,\"outputs\":%u}\n",count.n_input,count.n_output);
    if(rc||count.n_input!=1||count.n_output!=TAP_COUNT){status=10;goto finish;}
    rknn_tensor_attr input_attr={0},output_attr[TAP_COUNT]={0};tracing.tensor=0;
    trace(&tracing,"query_input_attr",1,0);rc=query(ctx,RKNN_QUERY_INPUT_ATTR,&input_attr,sizeof(input_attr));trace(&tracing,"query_input_attr",0,rc);attr(report,"input",&input_attr);
    if(rc){status=11;goto finish;}if(!tap_input_valid(&input_attr)){status=12;goto finish;}
    for(unsigned i=0;i<TAP_COUNT;i++){
        output_attr[i].index=i;tracing.tensor=(int)i;trace(&tracing,"query_output_attr",1,0);
        rc=query(ctx,RKNN_QUERY_OUTPUT_ATTR,&output_attr[i],sizeof(output_attr[i]));trace(&tracing,"query_output_attr",0,rc);attr(report,"output",&output_attr[i]);
        if(rc){status=11;goto finish;}if(!tap_output_valid(i,&output_attr[i])){status=12;goto finish;}
    }
    fprintf(report,"{\"event\":\"feed_contract\",\"type\":0,\"fmt\":%d,\"pass_through\":0,\"want_float\":1,\"input_floats\":292,\"output_count\":5,\"ordering\":\"C-order unchanged; no transpose\"}\n",input_attr.fmt);
    for(unsigned step=0;step<WARMUP+CASES;step++){
        int warm=step<WARMUP;unsigned index=warm?step:step-WARMUP;
        tracing.phase=warm?"warmup":"measurement";tracing.iteration=(int)index;tracing.tensor=-1;
        tap_iteration_result result;float copied[TAP_TOTAL];
        int okay=tap_run_iteration(&api,ctx,((float*)inputs)+(size_t)(index%CASES)*TAP_INPUT,input_attr.fmt,copied,&result,trace,&tracing);
        unsigned offset=0;
        for(unsigned i=0;i<TAP_COUNT;i++){
            unsigned nonfinite=0;float low=INFINITY,high=-INFINITY;long file_offset=-1;
            if(result.valid_output){
                for(unsigned j=0;j<tap_elements[i];j++){float v=copied[offset+j];if(!isfinite(v))nonfinite++;else{if(v<low)low=v;if(v>high)high=v;}}
                if(okay&&!warm){file_offset=(long)index*tap_elements[i];if(fwrite(copied+offset,sizeof(float),tap_elements[i],files[i])!=tap_elements[i]){okay=0;status=15;}}
            }
            fprintf(report,"{\"event\":\"tensor_result\",\"phase\":\"%s\",\"iteration\":%u,\"fixture\":%u,\"index\":%u,\"returned_index\":%u,\"label\":\"%s\",\"file\":\"output-%s.f32\",\"elements\":%u,\"returned_bytes\":%u,\"offset_floats\":%ld,\"valid_buffer\":%s,\"nonfinite\":%u,\"minimum\":",tracing.phase,index,index%CASES,i,result.output_indices[i],tap_labels[i],tap_labels[i],tap_elements[i],result.output_bytes[i],file_offset,result.valid_output?"true":"false",nonfinite);
            if(isfinite(low))fprintf(report,"%.9g",low);else fputs("null",report);fputs(",\"maximum\":",report);if(isfinite(high))fprintf(report,"%.9g",high);else fputs("null",report);fputs("}\n",report);offset+=tap_elements[i];
        }
        if(okay&&!warm)completed++;
        fprintf(report,"{\"event\":\"iteration\",\"phase\":\"%s\",\"iteration\":%u,\"fixture\":%u,\"complete\":%s,\"return_codes\":{\"inputs_set\":",tracing.phase,index,index%CASES,okay?"true":"false");code(report,result.set_rc);fputs(",\"run\":",report);code(report,result.run_rc);fputs(",\"outputs_get\":",report);code(report,result.get_rc);fputs(",\"outputs_release\":",report);code(report,result.release_rc);fputs("}}\n",report);
        if(!okay||ferror(report)){if(!status)status=13;break;}
    }
finish:
    if(initialized){tracing.phase="cleanup";tracing.iteration=-1;tracing.tensor=-1;trace(&tracing,"destroy",1,0);destroy_rc=destroy(ctx);trace(&tracing,"destroy",0,destroy_rc);if(destroy_rc&&!status)status=14;}
    if(library)dlclose(library);free(model);free(inputs);
    for(unsigned i=0;i<TAP_COUNT;i++)if(files[i]){if(fflush(files[i])&&!status)status=15;if(fclose(files[i])&&!status)status=15;}
    fprintf(report,"{\"event\":\"finish\",\"status\":\"%s\",\"exit_code\":%d,\"completed_measurements\":%u,\"expected_measurements\":92,\"destroy_rc\":",status?"error":"success",status,completed);code(report,destroy_rc);fputs("}\n",report);
    if(fclose(report)&&!status)status=16;printf("{\"exit_code\":%d,\"completed_measurements\":%u}\n",status,completed);return status;
}
