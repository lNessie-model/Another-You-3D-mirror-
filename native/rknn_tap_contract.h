#ifndef MIRROR_RKNN_TAP_CONTRACT_H
#define MIRROR_RKNN_TAP_CONTRACT_H
#include <string.h>
#include "rknn_api.h"
#define TAP_COUNT 5u
#define TAP_INPUT 292u
#ifndef MIRROR_TAP_PROFILE
#define MIRROR_TAP_PROFILE 0
#endif
#if MIRROR_TAP_PROFILE == 0
#define TAP_TOTAL 929u
#define TAP_KIND "fixed_front_end_five_tap_diagnostic"
static const char *const tap_labels[TAP_COUNT]={"input_echo","centered","scale","normalized","final52"};
static const char *const tap_names[TAP_COUNT]={"diagnostic_input_echo","model_1/tf.math.subtract/Sub","model_1/tf.math.reduce_mean_1/Mean","model_1/tf.math.truediv_1/truediv","StatefulPartitionedCall:0"};
static const uint32_t tap_elements[TAP_COUNT]={292,292,1,292,52};
static const uint32_t tap_ranks[TAP_COUNT]={3,3,3,3,1};
static const uint32_t tap_dims[TAP_COUNT][4]={{1,146,2,0},{1,146,2,0},{1,1,1,0},{1,146,2,0},{52,0,0,0}};
static const unsigned tap_terminal_singleton[TAP_COUNT]={0,1,1,1,0};
#elif MIRROR_TAP_PROFILE == 1
#define TAP_TOTAL 7036u
#define TAP_KIND "fixed_stem_five_tap_diagnostic"
static const char *const tap_labels[TAP_COUNT]={"normalized","stem_input","stem_projection","token_embedding","final52"};
static const char *const tap_names[TAP_COUNT]={"model_1/tf.math.truediv_1/truediv","diagnostic_stem_input","diagnostic_stem_projection","model_1/GhumMarkerPoserMlpMixerGeneral/MLPMixer/AddExtraTokens/concat","StatefulPartitionedCall:0"};
static const uint32_t tap_elements[TAP_COUNT]={292,292,192,6208,52};
static const uint32_t tap_ranks[TAP_COUNT]={3,4,4,4,1};
static const uint32_t tap_dims[TAP_COUNT][4]={{1,146,2,0},{1,146,1,2},{1,96,1,2},{1,64,1,97},{52,0,0,0}};
static const unsigned tap_terminal_singleton[TAP_COUNT]={1,0,0,0,0};
#elif MIRROR_TAP_PROFILE == 2
#define TAP_TOTAL 12662u
#define TAP_KIND "fixed_first_layernorm_five_tap_diagnostic"
static const char *const tap_labels[TAP_COUNT]={"token_embedding","mean","inv_std","affine","final52"};
static const char *const tap_names[TAP_COUNT]={"diagnostic_ln_token","diagnostic_ln_mean","diagnostic_ln_invstd","diagnostic_ln_affine","StatefulPartitionedCall:0"};
static const uint32_t tap_elements[TAP_COUNT]={6208,97,97,6208,52};
static const uint32_t tap_ranks[TAP_COUNT]={4,4,4,4,1};
static const uint32_t tap_dims[TAP_COUNT][4]={{1,64,1,97},{1,1,1,97},{1,1,1,97},{1,1,97,64},{52,0,0,0}};
static const unsigned tap_terminal_singleton[TAP_COUNT]={0,0,0,0,0};
#elif MIRROR_TAP_PROFILE == 3
#define TAP_TOTAL 24884u
#define TAP_KIND "fixed_first_layernorm_scale_five_tap_diagnostic"
static const char *const tap_labels[TAP_COUNT]={"gamma_conv","restore_scale","x_scaled","negative_mean_scaled","final52"};
static const char *const tap_names[TAP_COUNT]={"diagnostic_scale_gamma","diagnostic_scale_restored","diagnostic_scale_x","diagnostic_scale_negmean","StatefulPartitionedCall:0"};
static const uint32_t tap_elements[TAP_COUNT]={6208,6208,6208,6208,52};
static const uint32_t tap_ranks[TAP_COUNT]={4,4,4,4,1};
static const uint32_t tap_dims[TAP_COUNT][4]={{1,64,97,1},{1,1,97,64},{1,1,97,64},{1,1,97,64},{52,0,0,0}};
static const unsigned tap_terminal_singleton[TAP_COUNT]={0,0,0,0,0};
#else
#error Only fixed front (0), stem (1), first-LN (2) and scale (3) diagnostic profiles are supported
#endif
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
    int expanded=tap_terminal_singleton[index]&&a->n_dims==4&&a->dims[3]==1;
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
