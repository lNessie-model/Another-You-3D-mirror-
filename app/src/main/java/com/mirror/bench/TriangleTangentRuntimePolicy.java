package com.mirror.bench;

/** Narrow V50 experiment. No fallback, setting mutation or inference/backend change. */
final class TriangleTangentRuntimePolicy {
    static void requireOptions(int views,boolean asynchronous,boolean... incompatible){
        if(views!=16||!asynchronous)throw new IllegalArgumentException("Triangle tangent requires 16 views and the bounded asynchronous pose worker");
        for(boolean value:incompatible)if(value)throw new IllegalArgumentException("Triangle tangent requires original ordinary PBR without another material/background experiment");
    }
    static void requireScene(String sha,boolean pbr,boolean individual,boolean asynchronous,boolean staticNodes){
        if(!SrgbViewPolicy.GERALT.equals(sha)||!pbr||!individual||!asynchronous||staticNodes)
            throw new IllegalArgumentException("Triangle tangent requires exact original Geralt, ordinary full PBR, and bounded CPU worker");
    }
    private TriangleTangentRuntimePolicy(){}
}
