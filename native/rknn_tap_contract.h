#ifndef MIRROR_RKNN_TAP_CONTRACT_H
#define MIRROR_RKNN_TAP_CONTRACT_H
#include <string.h>
#include "rknn_api.h"
#define TAP_COUNT 5u
#define TAP_TOTAL 929u
#define TAP_INPUT 292u
static const char *const tap_labels[TAP_COUNT]={"input_echo","centered","scale","normalized","final52"};
static const char *const tap_names[TAP_COUNT]={"diagnostic_input_echo","model_1/tf.math.subtract/Sub","model_1/tf.math.reduce_mean_1/Mean","model_1/tf.math.truediv_1/truediv","StatefulPartitionedCall:0"};
static const uint32_t tap_elements[TAP_COUNT]={292,292,1,292,52};
static const uint32_t tap_ranks[TAP_COUNT]={3,3,3,3,1};
static const uint32_t tap_dims[TAP_COUNT][4]={{1,146,2,0},{1,146,2,0},{1,1,1,0},{1,146,2,0},{52,0,0,0}};
static int tap_name_is(const rknn_tensor_attr *a,const char *expected){
    return memchr(a->name,0,sizeof(a->name))&&strcmp(a->name,expected)==0;
}
static int tap_input_valid(const rknn_tensor_attr *a){
    return a&&a->index==0&&tap_name_is(a,"serving_default_input_points:0")&&a->n_dims==3
        &&a->dims[0]==1&&a->dims[1]==146&&a->dims[2]==2&&a->n_elems==292&&a->size==584
        &&a->type==RKNN_TENSOR_FLOAT16&&a->fmt==RKNN_TENSOR_UNDEFINED;
}
static int tap_output_valid(unsigned index,const rknn_tensor_attr *a){
    if(!a||index>=TAP_COUNT||a->index!=index||!tap_name_is(a,tap_names[index])
       ||a->type!=RKNN_TENSOR_FLOAT16||a->n_elems!=tap_elements[index]||a->size!=tap_elements[index]*2
       ||(a->fmt!=RKNN_TENSOR_NCHW&&a->fmt!=RKNN_TENSOR_NHWC&&a->fmt!=RKNN_TENSOR_UNDEFINED))return 0;
    int expanded=(index==1||index==2||index==3)&&a->n_dims==4&&a->dims[3]==1;
    if(a->n_dims!=tap_ranks[index]&&!expanded)return 0;
    for(unsigned i=0;i<tap_ranks[index];i++)if(a->dims[i]!=tap_dims[index][i])return 0;
    return 1;
}
/* Prevalidate every returned buffer before copying any tensor into the caller's fixed stack buffer.
   NaN values are retained for diagnostics, not silently replaced or treated as a valid numerical result. */
static int tap_copy_outputs(const rknn_output outputs[TAP_COUNT],float destination[TAP_TOTAL]){
    if(!outputs||!destination)return 0;
    for(unsigned i=0;i<TAP_COUNT;i++)if(outputs[i].index!=i||!outputs[i].buf||outputs[i].size!=tap_elements[i]*sizeof(float))return 0;
    unsigned offset=0;for(unsigned i=0;i<TAP_COUNT;i++){memcpy(destination+offset,outputs[i].buf,tap_elements[i]*sizeof(float));offset+=tap_elements[i];}
    return 1;
}
#endif
