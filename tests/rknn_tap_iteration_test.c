#include "../native/rknn_tap_iteration.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <math.h>
static unsigned checks,calls[4],released_count;
static int fail_api,bad_size,bad_index,partial_get,nonfinite;
static float storage[TAP_TOTAL];static unsigned traces;
static void check(int yes){checks++;assert(yes);}
static int set(rknn_context c,uint32_t n,rknn_input *in){(void)c;calls[0]++;check(n==1&&in[0].size==1168&&in[0].type==RKNN_TENSOR_FLOAT32&&in[0].fmt==RKNN_TENSOR_UNDEFINED&&in[0].pass_through==0);return fail_api==1?-5:0;}
static int run(rknn_context c,rknn_run_extend *e){(void)c;(void)e;calls[1]++;return fail_api==2?-6:0;}
static int get(rknn_context c,uint32_t n,rknn_output *out,rknn_output_extend *e){(void)c;(void)e;calls[2]++;check(n==5);unsigned offset=0;
    for(unsigned i=0;i<5;i++){check(out[i].index==i&&out[i].want_float==1&&!out[i].is_prealloc);out[i].buf=(fail_api==3&&!partial_get)?NULL:storage+offset;out[i].size=tap_elements[i]*4;offset+=tap_elements[i];}
    if(partial_get)for(unsigned i=1;i<5;i++)out[i].buf=NULL;
    if(bad_size)out[2].size=8;if(bad_index)out[3].index=4;return fail_api==3?-7:0;}
static int release(rknn_context c,uint32_t n,rknn_output *out){(void)c;(void)out;calls[3]++;released_count=n;for(unsigned i=0;i<TAP_TOTAL;i++)storage[i]=-999;return fail_api==4?-8:0;}
static void trace(void *u,const char *name,int begin,int rc){(void)u;(void)name;(void)rc;check(begin==(traces%2==0));traces++;}
static void reset(void){memset(calls,0,sizeof(calls));traces=released_count=0;fail_api=bad_size=bad_index=partial_get=nonfinite=0;for(unsigned i=0;i<TAP_TOTAL;i++)storage[i]=(float)i;}
int main(void){
    tap_api api={set,run,get,release};tap_iteration_result result;float input[292]={0},dest[TAP_TOTAL];
    reset();check(tap_run_iteration(&api,1,input,3,dest,&result,trace,NULL));check(released_count==5&&calls[3]==1&&traces==8);for(unsigned i=0;i<TAP_TOTAL;i++)check(dest[i]==(float)i);
    for(int api_index=1;api_index<=4;api_index++){reset();fail_api=api_index;check(!tap_run_iteration(&api,1,input,3,dest,&result,trace,NULL));check(calls[0]==1);check(calls[1]==(api_index>=2));check(calls[2]==(api_index>=3));check(calls[3]==(api_index==4));}
    reset();fail_api=3;partial_get=1;check(!tap_run_iteration(&api,1,input,3,dest,&result,trace,NULL));check(calls[3]==1&&released_count==5&&result.get_rc==-7);
    reset();bad_size=1;for(unsigned i=0;i<TAP_TOTAL;i++)dest[i]=-1;check(!tap_run_iteration(&api,1,input,3,dest,&result,trace,NULL));check(calls[3]==1&&!result.valid_output);for(unsigned i=0;i<TAP_TOTAL;i++)check(dest[i]==-1);
    reset();bad_index=1;check(!tap_run_iteration(&api,1,input,3,dest,&result,trace,NULL));check(calls[3]==1);
    reset();storage[300]=NAN;check(tap_run_iteration(&api,1,input,3,dest,&result,trace,NULL));check(isnan(dest[300])&&calls[3]==1);
    printf("Five tap iteration: %u checks passed\n",checks);return 0;
}
