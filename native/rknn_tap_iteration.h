#ifndef MIRROR_RKNN_TAP_ITERATION_H
#define MIRROR_RKNN_TAP_ITERATION_H
#include <limits.h>
#include "rknn_tap_contract.h"
typedef struct {
    int (*set)(rknn_context,uint32_t,rknn_input*);
    int (*run)(rknn_context,rknn_run_extend*);
    int (*get)(rknn_context,uint32_t,rknn_output*,rknn_output_extend*);
    int (*release)(rknn_context,uint32_t,rknn_output*);
} tap_api;
typedef struct {
    int set_rc,run_rc,get_rc,release_rc,valid_output;
    uint32_t output_bytes[TAP_COUNT],output_indices[TAP_COUNT];
} tap_iteration_result;
typedef void (*tap_trace)(void*,const char*,int,int);
static int tap_run_iteration(const tap_api *api,rknn_context ctx,float input[TAP_INPUT],int format,
                            float copied[TAP_TOTAL],tap_iteration_result *result,tap_trace trace,void *user){
    if(!api||!api->set||!api->run||!api->get||!api->release||!input||!copied||!result)return 0;
    memset(result,0,sizeof(*result));result->set_rc=result->run_rc=result->get_rc=result->release_rc=INT_MAX;
    rknn_input feed={0};feed.buf=input;feed.size=TAP_INPUT*sizeof(float);feed.type=RKNN_TENSOR_FLOAT32;feed.fmt=(rknn_tensor_format)format;
    rknn_output outputs[TAP_COUNT]={0};for(unsigned i=0;i<TAP_COUNT;i++){outputs[i].index=i;outputs[i].want_float=1;}
#define TAP_CALL(field,label,expression) do {if(trace)trace(user,label,1,0);result->field=(expression);if(trace)trace(user,label,0,result->field);} while(0)
    TAP_CALL(set_rc,"inputs_set",api->set(ctx,1,&feed));
    if(result->set_rc)return 0;
    TAP_CALL(run_rc,"run",api->run(ctx,NULL));
    if(result->run_rc)return 0;
    TAP_CALL(get_rc,"outputs_get",api->get(ctx,TAP_COUNT,outputs,NULL));
    int must_release=result->get_rc==0;
    for(unsigned i=0;i<TAP_COUNT;i++){
        result->output_bytes[i]=outputs[i].size;result->output_indices[i]=outputs[i].index;
        if(outputs[i].buf)must_release=1;
    }
    if(result->get_rc==0)result->valid_output=tap_copy_outputs(outputs,copied);
    /* Preserve the original five-output array/index ownership, including a partially allocated
       failed get. No direct free, no second release, and never use runtime buffers afterward. */
    if(must_release)TAP_CALL(release_rc,"outputs_release",api->release(ctx,TAP_COUNT,outputs));
#undef TAP_CALL
    return result->get_rc==0&&result->release_rc==0&&result->valid_output;
}
#endif
