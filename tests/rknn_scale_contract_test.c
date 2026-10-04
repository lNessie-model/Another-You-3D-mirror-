#define MIRROR_TAP_PROFILE 3
#include "../native/rknn_tap_contract.h"
#include <assert.h>
#include <stdio.h>
static unsigned checks;
static void check(int okay){checks++;assert(okay);}
int main(void){
    check(TAP_TOTAL==24884);check(TAP_COUNT==5&&TAP_INPUT==292);
    check(strcmp(TAP_KIND,"fixed_first_layernorm_scale_five_tap_diagnostic")==0);
    const unsigned elements[5]={6208,6208,6208,6208,52};
    const unsigned ranks[5]={4,4,4,4,1};
    const unsigned dims[5][4]={{1,64,97,1},{1,1,97,64},{1,1,97,64},{1,1,97,64},{52,0,0,0}};
    const char *names[5]={"diagnostic_scale_gamma","diagnostic_scale_restored","diagnostic_scale_x","diagnostic_scale_negmean","StatefulPartitionedCall:0"};
    for(unsigned i=0;i<5;i++){
        rknn_tensor_attr a={0};a.index=i;a.n_dims=ranks[i];memcpy(a.dims,dims[i],sizeof(dims[i]));
        a.n_elems=elements[i];a.size=elements[i]*2;a.type=RKNN_TENSOR_FLOAT16;a.fmt=RKNN_TENSOR_UNDEFINED;strcpy(a.name,names[i]);
        check(tap_elements[i]==elements[i]);check(tap_output_valid(i,&a));
        for(int fmt=0;fmt<=3;fmt++){a.fmt=(rknn_tensor_format)fmt;check(tap_output_valid(i,&a)==(fmt!=2));}a.fmt=RKNN_TENSOR_UNDEFINED;
        a.size+=2;check(!tap_output_valid(i,&a));a.size-=2;
        a.type=RKNN_TENSOR_FLOAT32;check(!tap_output_valid(i,&a));a.type=RKNN_TENSOR_FLOAT16;
        a.index=(i+1)%5;check(!tap_output_valid(i,&a));a.index=i;
        a.n_dims++;check(!tap_output_valid(i,&a));a.n_dims--;
        for(unsigned j=0;j<a.n_dims;j++){a.dims[j]++;check(!tap_output_valid(i,&a));a.dims[j]--;}
        strcpy(a.name,names[(i+1)%5]);check(!tap_output_valid(i,&a));
        strcpy(a.name,"diagnostic_scale_gamma_extra");check(!tap_output_valid(i,&a));
    }
    // Same element counts do not make gamma/restored layouts or equal-size product identities interchangeable.
    rknn_tensor_attr swapped={0};swapped.index=0;swapped.n_dims=4;memcpy(swapped.dims,dims[3],sizeof(dims[3]));
    swapped.n_elems=6208;swapped.size=12416;swapped.type=RKNN_TENSOR_FLOAT16;swapped.fmt=RKNN_TENSOR_UNDEFINED;strcpy(swapped.name,names[0]);
    check(!tap_output_valid(0,&swapped));
    rknn_output outputs[5]={{0}};float values[24884],guarded[24886];unsigned offset=0;
    for(unsigned i=0;i<24884;i++)values[i]=(float)((int)i-6000);
    for(unsigned i=0;i<24886;i++)guarded[i]=-77777;
    for(unsigned i=0;i<5;i++){outputs[i].index=i;outputs[i].buf=values+offset;outputs[i].size=elements[i]*4;offset+=elements[i];}
    check(offset==24884);check(tap_copy_outputs(outputs,guarded+1));
    check(guarded[0]==-77777&&guarded[24885]==-77777);
    for(unsigned i=0;i<24884;i++)check(guarded[i+1]==values[i]);
    for(unsigned i=0;i<24886;i++)guarded[i]=-77777;
    outputs[4].size=204;check(!tap_copy_outputs(outputs,guarded+1));
    for(unsigned i=0;i<24886;i++)check(guarded[i]==-77777);
    printf("Scale fixed contract: %u checks passed\n",checks);return 0;
}
