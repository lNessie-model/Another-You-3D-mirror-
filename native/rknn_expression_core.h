#ifndef MIRROR_RKNN_EXPRESSION_CORE_H
#define MIRROR_RKNN_EXPRESSION_CORE_H
#include <stdint.h>
#include <string.h>
#include <math.h>
#include <limits.h>
#include "rknn_api.h"

/* Exact observed normalized-suffix profile, not an image/tensor-layout adapter. */
#define EXPRESSION_MODEL_BYTES 1209569u
#define EXPRESSION_INPUT_NAME "model_1/tf.math.truediv_1/truediv"
#define EXPRESSION_OUTPUT_NAME "StatefulPartitionedCall:0"
#define EXPRESSION_SDK "1.3.0 (9b36d4d74@2022-05-04T20:16:47)"
#define EXPRESSION_DRIVER "0.7.2"
#define EXPRESSION_NOT_CALLED INT_MAX
typedef struct {
    int (*init)(rknn_context*,void*,uint32_t,uint32_t,rknn_init_extend*);
    int (*query)(rknn_context,rknn_query_cmd,void*,uint32_t);
    int (*set)(rknn_context,uint32_t,rknn_input*);
    int (*run)(rknn_context,rknn_run_extend*);
    int (*get)(rknn_context,uint32_t,rknn_output*,rknn_output_extend*);
    int (*release)(rknn_context,uint32_t,rknn_output*);
    int (*destroy)(rknn_context);
} ExpressionApi;
typedef struct {
    const ExpressionApi *api;
    rknn_context context;
    int active,poisoned;
    rknn_sdk_version sdk;
    rknn_input_output_num counts;
    rknn_tensor_attr input,output;
    const char *stage;
    int rc,release_rc,destroy_rc;
} ExpressionCore;
static int expression_string_equal(const char *actual,size_t capacity,const char *expected){
    size_t n=strlen(expected);return n<capacity&&memcmp(actual,expected,n+1)==0;
}
static int expression_attr_valid(const rknn_tensor_attr *a,int output){
    if(!a||a->index!=0||a->n_dims!=(output?1u:3u)||a->dims[0]!=(output?52u:1u)
        ||(!output&&(a->dims[1]!=146||a->dims[2]!=2))
        ||!expression_string_equal(a->name,sizeof(a->name),output?EXPRESSION_OUTPUT_NAME:EXPRESSION_INPUT_NAME)
        ||a->n_elems!=(output?52u:292u)||a->size!=(output?104u:584u)
        ||a->fmt!=RKNN_TENSOR_UNDEFINED||a->type!=RKNN_TENSOR_FLOAT16
        ||a->qnt_type!=RKNN_TENSOR_QNT_AFFINE_ASYMMETRIC||a->zp!=0||a->scale!=1.0f
        ||a->w_stride!=0||a->size_with_stride!=a->size)return 0;
    return 1;
}
/* Exactly one destruction attempt per owned context, even when the API returns an error. */
static int expression_close(ExpressionCore *m){
    if(!m||!m->active)return 1;
    rknn_context owned=m->context;m->context=0;m->active=0;m->poisoned=1;
    m->destroy_rc=m->api->destroy(owned);return m->destroy_rc==0;
}
static int expression_open(ExpressionCore *m,const ExpressionApi *api,void *model,uint32_t bytes){
    memset(m,0,sizeof(*m));m->api=api;m->stage="arguments";
    m->rc=m->release_rc=m->destroy_rc=EXPRESSION_NOT_CALLED;
    if(!model||bytes!=EXPRESSION_MODEL_BYTES||!api||!api->init||!api->query||!api->set||!api->run||!api->get||!api->release||!api->destroy)return 0;
    m->stage="init";m->rc=api->init(&m->context,model,bytes,0,NULL);
    m->active=m->context!=0;
    if(m->rc||!m->active)goto failed;
    m->stage="query_sdk";m->rc=api->query(m->context,RKNN_QUERY_SDK_VERSION,&m->sdk,sizeof(m->sdk));
    if(m->rc)goto failed;
    m->stage="sdk_contract";
    if(!expression_string_equal(m->sdk.api_version,sizeof(m->sdk.api_version),EXPRESSION_SDK)||!expression_string_equal(m->sdk.drv_version,sizeof(m->sdk.drv_version),EXPRESSION_DRIVER))goto failed;
    m->stage="query_counts";m->rc=api->query(m->context,RKNN_QUERY_IN_OUT_NUM,&m->counts,sizeof(m->counts));
    if(m->rc)goto failed;
    m->stage="counts_contract";if(m->counts.n_input!=1||m->counts.n_output!=1)goto failed;
    m->stage="query_input";m->rc=api->query(m->context,RKNN_QUERY_INPUT_ATTR,&m->input,sizeof(m->input));
    if(m->rc)goto failed;
    m->stage="input_contract";if(!expression_attr_valid(&m->input,0))goto failed;
    m->stage="query_output";m->rc=api->query(m->context,RKNN_QUERY_OUTPUT_ATTR,&m->output,sizeof(m->output));
    if(m->rc)goto failed;
    m->stage="output_contract";if(!expression_attr_valid(&m->output,1))goto failed;
    m->stage="ready";return 1;
failed:
    m->poisoned=1;(void)expression_close(m);return 0;
}
/* Caller serializes context ownership. Destination is committed only after successful release. */
static int expression_run(ExpressionCore *m,const float input[292],float destination[52]){
    if(!m||!m->active||m->poisoned)return 0;
    m->stage="input_finite";m->rc=m->release_rc=EXPRESSION_NOT_CALLED;
    if(!input||!destination)return 0;
    for(unsigned i=0;i<292;i++)if(!isfinite(input[i]))return 0;
    rknn_input feed={0};feed.buf=(void*)input;feed.size=1168;feed.type=RKNN_TENSOR_FLOAT32;feed.fmt=RKNN_TENSOR_UNDEFINED;
    rknn_output result={0};result.want_float=1;
    float values[52];int valid=0;
    m->stage="inputs_set";m->rc=m->api->set(m->context,1,&feed);if(m->rc)goto failed;
    m->stage="run";m->rc=m->api->run(m->context,NULL);if(m->rc)goto failed;
    m->stage="outputs_get";m->rc=m->api->get(m->context,1,&result,NULL);
    if(!m->rc){
        m->stage="output_buffer";
        if(result.buf&&result.size==sizeof(values)&&result.index==0){
            memcpy(values,result.buf,sizeof(values));valid=1;m->stage="output_finite_range";
            for(unsigned i=0;i<52;i++)if(!isfinite(values[i])||values[i]<0.0f||values[i]>1.0f){valid=0;break;}
        }
    }
    /* A failing get may still return a buffer. Release the original result descriptor once. */
    if(!m->rc||result.buf)m->release_rc=m->api->release(m->context,1,&result);
    if(m->rc||!valid||m->release_rc!=0){if(m->release_rc!=EXPRESSION_NOT_CALLED&&m->release_rc!=0)m->stage="outputs_release";goto failed;}
    memcpy(destination,values,sizeof(values));m->stage="ready";return 1;
failed:
    m->poisoned=1;return 0;
}
#endif
