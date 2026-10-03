#include "../native/rknn_expression_core.h"
#include <assert.h>
#include <stdio.h>
static unsigned checks, calls[8];
static int fail_at, bad_query, partial, bad_output, destroyed;
static float buffer[52];
static void check(int v){checks++;assert(v);}
static void attribute(rknn_tensor_attr *a,int output){
    memset(a,0,sizeof(*a));a->n_dims=output?1:3;a->dims[0]=output?52:1;
    if(!output){a->dims[1]=146;a->dims[2]=2;}
    strcpy(a->name,output?EXPRESSION_OUTPUT_NAME:EXPRESSION_INPUT_NAME);
    a->n_elems=output?52:292;a->size=output?104:584;a->size_with_stride=a->size;
    a->fmt=RKNN_TENSOR_UNDEFINED;a->type=RKNN_TENSOR_FLOAT16;
    a->qnt_type=RKNN_TENSOR_QNT_AFFINE_ASYMMETRIC;a->scale=1;
}
static int init(rknn_context *ctx,void *model,uint32_t size,uint32_t flags,rknn_init_extend *ext){
    calls[0]++;check(model!=NULL&&size==1209569&&flags==0&&!ext);*ctx=7;return fail_at==1?-11:0;
}
static int query(rknn_context ctx,rknn_query_cmd cmd,void *out,uint32_t size){
    calls[1]++;check(ctx==7);
    if(fail_at==2+(int)calls[1]-1)return -12;
    if(cmd==RKNN_QUERY_SDK_VERSION){check(size==sizeof(rknn_sdk_version));rknn_sdk_version *v=out;strcpy(v->api_version,EXPRESSION_SDK);strcpy(v->drv_version,EXPRESSION_DRIVER);if(bad_query==1)v->drv_version[0]='9';}
    else if(cmd==RKNN_QUERY_IN_OUT_NUM){check(size==sizeof(rknn_input_output_num));rknn_input_output_num *n=out;n->n_input=1;n->n_output=bad_query==2?2:1;}
    else {check(size==sizeof(rknn_tensor_attr));rknn_tensor_attr *a=out;check(a->index==0);attribute(a,cmd==RKNN_QUERY_OUTPUT_ATTR);if(bad_query==3&&cmd==RKNN_QUERY_INPUT_ATTR)a->fmt=RKNN_TENSOR_NHWC;if(bad_query==4&&cmd==RKNN_QUERY_OUTPUT_ATTR)a->qnt_type=RKNN_TENSOR_QNT_NONE;}
    return 0;
}
static int set(rknn_context ctx,uint32_t count,rknn_input *in){
    calls[2]++;check(ctx==7&&count==1&&in->index==0&&in->size==1168&&in->type==RKNN_TENSOR_FLOAT32&&in->fmt==RKNN_TENSOR_UNDEFINED&&in->pass_through==0);check(((float*)in->buf)[291]==291);return fail_at==6?-16:0;
}
static int run(rknn_context ctx,rknn_run_extend *ext){calls[3]++;check(ctx==7&&!ext);return fail_at==7?-17:0;}
static int get(rknn_context ctx,uint32_t count,rknn_output *out,rknn_output_extend *ext){
    calls[4]++;check(ctx==7&&count==1&&!ext&&out->index==0&&out->want_float==1&&out->is_prealloc==0&&out->buf==NULL);
    if(fail_at!=8||partial)out->buf=buffer;
    out->size=bad_output==1?204:bad_output==2?212:208;
    if(bad_output==3)out->index=1;if(bad_output==4)out->buf=NULL;
    return fail_at==8?-18:0;
}
static int release(rknn_context ctx,uint32_t count,rknn_output *out){
    calls[5]++;check(ctx==7&&count==1);check(out->index==(bad_output==3?1u:0u));
    for(int i=0;i<52;i++)buffer[i]=-999;return fail_at==9?-19:0;
}
static int destroy(rknn_context ctx){calls[6]++;check(ctx==7&&!destroyed);destroyed=1;return fail_at==10?-20:0;}
static ExpressionApi api={init,query,set,run,get,release,destroy};
static void reset(void){memset(calls,0,sizeof(calls));fail_at=bad_query=partial=bad_output=destroyed=0;for(int i=0;i<52;i++)buffer[i]=(float)i/51;}
static void attrs(void){
    for(int output=0;output<2;output++){
        rknn_tensor_attr a;attribute(&a,output);check(expression_attr_valid(&a,output));
        for(int field=0;field<16;field++){
            rknn_tensor_attr b=a;
            switch(field){case 0:b.index=1;break;case 1:b.n_dims++;break;case 2:b.dims[0]++;break;case 3:b.name[0]='X';break;case 4:memset(b.name,'X',sizeof(b.name));break;case 5:b.n_elems++;break;case 6:b.size++;break;case 7:b.fmt=RKNN_TENSOR_NCHW;break;case 8:b.type=RKNN_TENSOR_FLOAT32;break;case 9:b.qnt_type=RKNN_TENSOR_QNT_NONE;break;case 10:b.zp=1;break;case 11:b.scale=.5;break;case 12:b.scale=NAN;break;case 13:b.w_stride=1;break;case 14:b.size_with_stride++;break;case 15:b.n_dims=RKNN_MAX_DIMS+1;break;}
            check(!expression_attr_valid(&b,output));
        }
        if(!output){a.dims[1]=2;a.dims[2]=146;check(!expression_attr_valid(&a,0));}
    }
}
int main(void){
    attrs();unsigned char dummy=0;float input[292],output[52];for(int i=0;i<292;i++)input[i]=(float)i;
    ExpressionCore core;
    reset();check(expression_open(&core,&api,&dummy,1209569));check(calls[1]==4);
    check(expression_run(&core,input,output));check(calls[5]==1);for(int i=0;i<52;i++)check(output[i]==(float)i/51);
    check(expression_close(&core));check(expression_close(&core));check(calls[6]==1);check(!expression_run(&core,input,output));
    for(int fail=1;fail<=5;fail++){reset();fail_at=fail;check(!expression_open(&core,&api,&dummy,1209569));check(calls[6]==1);check(expression_close(&core));check(calls[6]==1);}
    for(int bad=1;bad<=4;bad++){reset();bad_query=bad;check(!expression_open(&core,&api,&dummy,1209569));check(calls[6]==1);}
    for(int fail=6;fail<=9;fail++){
        reset();check(expression_open(&core,&api,&dummy,1209569));fail_at=fail;for(int i=0;i<52;i++)output[i]=-7;
        check(!expression_run(&core,input,output));for(int i=0;i<52;i++)check(output[i]==-7);
        check(calls[2]==1&&calls[3]==(unsigned)(fail>=7)&&calls[4]==(unsigned)(fail>=8)&&calls[5]==(unsigned)(fail>=9));
        check(core.poisoned);check(!expression_run(&core,input,output));check(calls[2]==1);check(expression_close(&core));
    }
    reset();check(expression_open(&core,&api,&dummy,1209569));fail_at=8;partial=1;check(!expression_run(&core,input,output));check(calls[5]==1);check(expression_close(&core));
    for(int bad=1;bad<=7;bad++){
        reset();check(expression_open(&core,&api,&dummy,1209569));bad_output=bad;
        if(bad==5)buffer[51]=NAN;if(bad==6)buffer[0]=-0.000001f;if(bad==7)buffer[0]=1.000001f;
        for(int i=0;i<52;i++)output[i]=-8;
        check(!expression_run(&core,input,output));check(calls[5]==1);for(int i=0;i<52;i++)check(output[i]==-8);check(expression_close(&core));
    }
    reset();check(expression_open(&core,&api,&dummy,1209569));input[291]=INFINITY;check(!expression_run(&core,input,output));check(!core.poisoned&&calls[2]==0);input[291]=291;check(expression_run(&core,input,output));fail_at=10;check(!expression_close(&core));check(core.destroy_rc==-20);check(expression_close(&core));check(calls[6]==1);
    printf("Rknn expression core: %u checks passed\n",checks);return 0;
}
