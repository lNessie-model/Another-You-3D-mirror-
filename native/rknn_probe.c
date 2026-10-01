#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <dlfcn.h>
#include <time.h>
#include "rknn_api.h"

static double milliseconds(void) {
    struct timespec now; clock_gettime(CLOCK_MONOTONIC,&now);
    return now.tv_sec*1000.0+now.tv_nsec/1e6;
}
static void *read_file(const char *path,uint32_t *size) {
    FILE *file=fopen(path,"rb"); if(!file) return NULL;
    fseek(file,0,SEEK_END); long length=ftell(file); rewind(file);
    if(length<=0||length>1024L*1024*1024) { fclose(file); return NULL; }
    void *data=malloc(length); if(!data) { fclose(file); return NULL; }
    if(fread(data,1,length,file)!=(size_t)length) { free(data); fclose(file); return NULL; }
    fclose(file); *size=(uint32_t)length; return data;
}
int main(int argc,char **argv) {
    if(argc!=4) { fprintf(stderr,"usage: rknn_probe model.rknn input.rgb loops\n"); return 2; }
    int loops=atoi(argv[3]); if(loops<1||loops>10000) return 2;
    void *library=dlopen("/vendor/lib64/librknnrt.so",RTLD_NOW|RTLD_LOCAL);
    if(!library) { fprintf(stderr,"dlopen: %s\n",dlerror()); return 3; }
    int (*initialize)(rknn_context*,void*,uint32_t,uint32_t,rknn_init_extend*)=dlsym(library,"rknn_init");
    int (*query)(rknn_context,rknn_query_cmd,void*,uint32_t)=dlsym(library,"rknn_query");
    int (*inputs_set)(rknn_context,uint32_t,rknn_input*)=dlsym(library,"rknn_inputs_set");
    int (*run)(rknn_context,rknn_run_extend*)=dlsym(library,"rknn_run");
    int (*outputs_get)(rknn_context,uint32_t,rknn_output*,rknn_output_extend*)=dlsym(library,"rknn_outputs_get");
    int (*outputs_release)(rknn_context,uint32_t,rknn_output*)=dlsym(library,"rknn_outputs_release");
    int (*destroy)(rknn_context)=dlsym(library,"rknn_destroy");
    if(!initialize||!query||!inputs_set||!run||!outputs_get||!outputs_release||!destroy) return 4;
    uint32_t model_size=0,input_size=0;
    void *model=read_file(argv[1],&model_size),*pixels=read_file(argv[2],&input_size);
    if(!model||!pixels) { free(model); free(pixels); return 5; }
    rknn_context context=0; int status=initialize(&context,model,model_size,0,NULL);
    free(model);
    if(status) { fprintf(stderr,"rknn_init returned %d\n",status); free(pixels); return 6; }
    rknn_sdk_version version={0}; rknn_input_output_num counts={0};
    rknn_tensor_attr input={0},output={0};
    status=query(context,RKNN_QUERY_SDK_VERSION,&version,sizeof(version));
    status|=query(context,RKNN_QUERY_IN_OUT_NUM,&counts,sizeof(counts));
    status|=query(context,RKNN_QUERY_INPUT_ATTR,&input,sizeof(input));
    status|=query(context,RKNN_QUERY_OUTPUT_ATTR,&output,sizeof(output));
    if(status||counts.n_input!=1||counts.n_output!=1||input.n_elems!=input_size) {
        fprintf(stderr,"Unexpected model inputs or query failure: %d\n",status);
        destroy(context); free(pixels); return 7;
    }
    rknn_input feed={0}; feed.buf=pixels; feed.size=input_size;
    feed.type=RKNN_TENSOR_UINT8; feed.fmt=RKNN_TENSOR_NHWC;
    double start=0,total=0; int top=0; float probability=0;
    for(int i=0;i<loops+5;i++) {
        if(i==5) start=milliseconds();
        rknn_output result={0}; result.want_float=1;
        status=inputs_set(context,1,&feed);
        if(!status) status=run(context,NULL);
        if(!status) status=outputs_get(context,1,&result,NULL);
        if(status) { fprintf(stderr,"Inference failed: %d\n",status); break; }
        float *values=(float*)result.buf;
        top=0; probability=values[0];
        for(uint32_t j=1;j<output.n_elems;j++) if(values[j]>probability) { top=j; probability=values[j]; }
        status=outputs_release(context,1,&result); if(status) break;
    }
    total=milliseconds()-start;
    printf("{\"status\":\"%s\",\"api\":\"%s\",\"driver\":\"%s\",\"loops\":%d,\"elapsed_ms\":%.3f,\"mean_ms\":%.3f,\"top_class\":%d,\"score\":%.6f,\"purpose\":\"NPU hardware smoke; MobileNet classification, not face capture\"}\n",
           status?"error":"success",version.api_version,version.drv_version,loops,total,total/loops,top,probability);
    destroy(context); free(pixels); dlclose(library); return status?8:0;
}
