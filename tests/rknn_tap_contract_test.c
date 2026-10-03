#include "../native/rknn_tap_contract.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
static unsigned checks;
static void check(int yes){checks++;assert(yes);}
int main(void){
    rknn_tensor_attr in={0};in.n_dims=3;in.dims[0]=1;in.dims[1]=146;in.dims[2]=2;in.n_elems=292;in.size=584;in.type=RKNN_TENSOR_FLOAT16;in.fmt=RKNN_TENSOR_UNDEFINED;
    strcpy(in.name,"serving_default_input_points:0");check(tap_input_valid(&in));
    in.type=RKNN_TENSOR_FLOAT32;check(!tap_input_valid(&in));in.type=RKNN_TENSOR_FLOAT16;
    in.dims[1]=2;in.dims[2]=146;check(!tap_input_valid(&in));in.dims[1]=146;in.dims[2]=2;
    for(int fmt=0;fmt<3;fmt++){in.fmt=(rknn_tensor_format)fmt;check(!tap_input_valid(&in));}in.fmt=RKNN_TENSOR_UNDEFINED;
    in.size=1168;check(!tap_input_valid(&in));in.size=584;in.index=1;check(!tap_input_valid(&in));
    unsigned sum=0;
    for(unsigned i=0;i<TAP_COUNT;i++){
        rknn_tensor_attr a={0};a.index=i;a.n_dims=tap_ranks[i];memcpy(a.dims,tap_dims[i],sizeof(tap_dims[i]));a.n_elems=tap_elements[i];a.size=a.n_elems*2;a.type=RKNN_TENSOR_FLOAT16;a.fmt=RKNN_TENSOR_UNDEFINED;strcpy(a.name,tap_names[i]);
        check(tap_output_valid(i,&a));sum+=a.n_elems;
        a.type=RKNN_TENSOR_FLOAT32;check(!tap_output_valid(i,&a));a.type=RKNN_TENSOR_FLOAT16;
        a.size++;check(!tap_output_valid(i,&a));a.size--;
        a.index=(i+1)%5;check(!tap_output_valid(i,&a));a.index=i;
        a.name[0]='X';check(!tap_output_valid(i,&a));strcpy(a.name,tap_names[i]);
        a.fmt=RKNN_TENSOR_NC1HWC2;check(!tap_output_valid(i,&a));a.fmt=RKNN_TENSOR_UNDEFINED;
        if(i==1||i==2||i==3){a.n_dims=4;a.dims[3]=1;check(tap_output_valid(i,&a));a.dims[3]=2;check(!tap_output_valid(i,&a));}
        a.n_dims=2;a.dims[0]=146;a.dims[1]=2;check(!tap_output_valid(i,&a));
    }
    check(sum==929);check(!tap_output_valid(5,&in));
    rknn_output out[TAP_COUNT]={{0}};float values[929]={0},copied[929];unsigned offset=0;
    for(unsigned i=0;i<TAP_COUNT;i++){out[i].index=i;out[i].buf=values+offset;out[i].size=tap_elements[i]*4;offset+=tap_elements[i];}
    for(unsigned i=0;i<929;i++)values[i]=(float)i;
    check(tap_copy_outputs(out,copied));for(unsigned i=0;i<929;i++)check(values[i]==copied[i]);
    out[3].size--;for(unsigned i=0;i<929;i++)copied[i]=-1;check(!tap_copy_outputs(out,copied));for(unsigned i=0;i<929;i++)check(copied[i]==-1);
    out[3].size++;out[4].buf=NULL;check(!tap_copy_outputs(out,copied));
    printf("Five tap contract: %u checks passed\n",checks);return 0;
}
