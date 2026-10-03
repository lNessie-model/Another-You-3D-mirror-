#define MIRROR_TAP_PROFILE 1
#include "../native/rknn_tap_contract.h"
#include <assert.h>
#include <stdio.h>
static unsigned checks;
static void check(int okay){checks++;assert(okay);}
int main(void){
    check(TAP_TOTAL==7036);check(TAP_COUNT==5&&TAP_INPUT==292);
    const unsigned elements[5]={292,292,192,6208,52};
    const unsigned ranks[5]={3,4,4,4,1};
    const unsigned dims[5][4]={{1,146,2,0},{1,146,1,2},{1,96,1,2},{1,64,1,97},{52,0,0,0}};
    const char *names[5]={"model_1/tf.math.truediv_1/truediv","diagnostic_stem_input","diagnostic_stem_projection","model_1/GhumMarkerPoserMlpMixerGeneral/MLPMixer/AddExtraTokens/concat","StatefulPartitionedCall:0"};
    for(unsigned i=0;i<5;i++){
        rknn_tensor_attr a={0};a.index=i;a.n_dims=ranks[i];memcpy(a.dims,dims[i],sizeof(dims[i]));a.n_elems=elements[i];a.size=elements[i]*2;a.type=RKNN_TENSOR_FLOAT16;a.fmt=RKNN_TENSOR_UNDEFINED;strcpy(a.name,names[i]);
        check(tap_elements[i]==elements[i]);check(tap_output_valid(i,&a));
        for(int fmt=0;fmt<=3;fmt++){a.fmt=(rknn_tensor_format)fmt;check(tap_output_valid(i,&a)==(fmt!=2));}a.fmt=RKNN_TENSOR_UNDEFINED;
        a.size+=2;check(!tap_output_valid(i,&a));a.size-=2;
        a.type=RKNN_TENSOR_FLOAT32;check(!tap_output_valid(i,&a));a.type=RKNN_TENSOR_FLOAT16;
        a.index=(i+1)%5;check(!tap_output_valid(i,&a));a.index=i;
        for(unsigned j=0;j<a.n_dims;j++){a.dims[j]++;check(!tap_output_valid(i,&a));a.dims[j]--;}
        if(i==0){a.n_dims=4;a.dims[3]=1;check(tap_output_valid(i,&a));a.dims[3]=2;check(!tap_output_valid(i,&a));}
        strcpy(a.name,"model_1/GhumMarkerPoserMlpMixerGeneral/conv2d/BiasAdd;model_1/GhumMarkerPoserMlpMixerGeneral/conv2d/Conv2D;model_1/GhumMark__0");check(!tap_output_valid(i,&a));
    }
    rknn_output outputs[5]={{0}};float values[7036],guarded[7038];unsigned offset=0;
    for(unsigned i=0;i<7036;i++)values[i]=(float)(i-3000);
    for(unsigned i=0;i<7038;i++)guarded[i]=-77;
    for(unsigned i=0;i<5;i++){outputs[i].index=i;outputs[i].buf=values+offset;outputs[i].size=elements[i]*4;offset+=elements[i];}
    check(offset==7036);check(tap_copy_outputs(outputs,guarded+1));
    check(guarded[0]==-77&&guarded[7037]==-77);
    for(unsigned i=0;i<7036;i++)check(guarded[i+1]==values[i]);
    for(unsigned i=0;i<7038;i++)guarded[i]=-77;
    outputs[4].size=204;check(!tap_copy_outputs(outputs,guarded+1));
    for(unsigned i=0;i<7038;i++)check(guarded[i]==-77);
    printf("Stem fixed contract: %u checks passed\n",checks);return 0;
}
