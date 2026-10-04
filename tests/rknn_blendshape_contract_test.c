#include "../native/rknn_blendshape_contract.h"
#include <assert.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>

int main(void){
    unsigned checks=0;
    rknn_tensor_attr a={0};a.n_dims=3;a.dims[0]=1;a.dims[1]=146;a.dims[2]=2;a.n_elems=292;
    /* Observed old-runtime non-image tensor: conversion still takes original XY order. */
    a.fmt=RKNN_TENSOR_UNDEFINED;a.type=RKNN_TENSOR_FLOAT16;a.size=584;
    assert(input_contract(&a));checks++;
    for(int fmt=0;fmt<=1;fmt++){a.fmt=(rknn_tensor_format)fmt;assert(input_contract(&a));checks++;}
    int rejected[]={RKNN_TENSOR_NC1HWC2,RKNN_TENSOR_FORMAT_MAX,99,-1};
    for(unsigned i=0;i<sizeof(rejected)/sizeof(rejected[0]);i++){a.fmt=(rknn_tensor_format)rejected[i];assert(!input_contract(&a));checks++;}
    a.fmt=RKNN_TENSOR_UNDEFINED;
    uint32_t shapes[][4]={{146,2,0,0},{1,2,146,0},{1,146,2,1},{146,1,2,0},{1,146,1,2},{1,73,4,0},{1,292,1,0}};
    unsigned ranks[]={2,3,4,3,4,3,3};
    for(unsigned i=0;i<7;i++){a.n_dims=ranks[i];memcpy(a.dims,shapes[i],sizeof(shapes[i]));assert(!input_contract(&a));checks++;}
    a.n_dims=3;a.dims[0]=1;a.dims[1]=146;a.dims[2]=2;
    a.n_elems=291;assert(!input_contract(&a));checks++;
    a.n_elems=292;assert(input_contract(&a));checks++;
    a.dims[1]=0;assert(!input_contract(&a));checks++;
    a.dims[1]=UINT32_MAX;assert(!input_contract(&a));checks++;
    a.n_dims=RKNN_MAX_DIMS+1;assert(!input_contract(&a));checks++;
    rknn_tensor_attr output={0};output.n_dims=1;output.dims[0]=52;output.n_elems=52;output.fmt=RKNN_TENSOR_UNDEFINED;
    assert(shape(&output,0));checks++;output.n_elems=51;assert(!shape(&output,0));checks++;
    printf("RKNN blendshape contract: %u checks passed\n",checks);return 0;
}
