package com.mirror.bench;

/** Two independent default-OFF probes; neither changes the ordinary rendering policy. */
final class GpuTriangleTangentPolicy {
    static void requireOptions(int views,boolean asynchronous,boolean... incompatible){
        if(views!=16||!asynchronous)throw new IllegalArgumentException("GPU triangle/material ablation requires 16 views and bounded async pose");
        for(boolean value:incompatible)if(value)throw new IllegalArgumentException("Exclusive original ordinary PBR probe required");
    }
    static void requireScene(String sha,boolean pbr,boolean individual,boolean asynchronous,boolean staticNodes){
        if(!SrgbViewPolicy.GERALT.equals(sha)||!pbr||!individual||!asynchronous||staticNodes)
            throw new IllegalArgumentException("Exact original Geralt, ordinary PBR and async pose required");
    }
    private GpuTriangleTangentPolicy(){}
}
