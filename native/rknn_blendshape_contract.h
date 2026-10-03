#ifndef MIRROR_RKNN_BLENDSHAPE_CONTRACT_H
#define MIRROR_RKNN_BLENDSHAPE_CONTRACT_H
#include <stdint.h>
#include "rknn_api.h"
static int shape(const rknn_tensor_attr *a,int input){
    if(!a->n_dims||a->n_dims>RKNN_MAX_DIMS||a->n_elems!=(input?292u:52u))return 0;
    uint64_t product=1;unsigned kept=0;uint32_t nonsingle[2]={0,0};
    for(uint32_t i=0;i<a->n_dims;i++){if(!a->dims[i]||a->dims[i]>292u)return 0;product*=a->dims[i];if(product>292u)return 0;
        if(a->dims[i]!=1){if(kept>=2)return 0;nonsingle[kept++]=a->dims[i];}}
    return product==a->n_elems&&(input?(kept==2&&nonsingle[0]==146&&nonsingle[1]==2):(kept==1&&nonsingle[0]==52));
}
static int input_contract(const rknn_tensor_attr *a){
    if(!shape(a,1))return 0;
    if(a->fmt==RKNN_TENSOR_NCHW||a->fmt==RKNN_TENSOR_NHWC)return 1;
    /* SDK's public UNDEFINED enum identifies the observed non-image coordinate tensor.
       Accept only its exact public shape; feed still uses F32/pass_through=0 and the
       queried format, leaving F32->model conversion to the runtime. No reordering. */
    return a->fmt==RKNN_TENSOR_UNDEFINED&&a->n_dims==3
            &&a->dims[0]==1&&a->dims[1]==146&&a->dims[2]==2;
}
#endif
