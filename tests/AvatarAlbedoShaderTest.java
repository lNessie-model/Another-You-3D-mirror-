package com.mirror.bench;

/** Complements the frozen legacy shader tests. Driver compilation/pixels are separate device gates. */
public final class AvatarAlbedoShaderTest {
    public static void main(String[] args) {
        int checks=0;
        for(int count=1;count<=8;count++)for(boolean multiview:new boolean[]{false,true}){
            String v=AvatarBatchGpu.vertexSource(multiview,count,true),f=AvatarBatchGpu.fragmentSource(count,true);
            require(v.contains("layout(location=4) in vec2 aUV;"),"static UV location");checks++;
            require(v.contains("vUV=aUV;"),"original UV interpolation");checks++;
            require(f.contains("uniform sampler2D uAtlas;"),"shared atlas sampler");checks++;
            require(f.contains("vMaterialParams.z"),"per-material texture selection");checks++;
            require(f.contains("texture(uAtlas,vUV).rgb"),"sRGB hardware sampled colour");checks++;
            require(!f.contains("uColors[")&&!f.contains("uParams["),"material selection remains in vertex stage");checks++;
            require(!AvatarBatchGpu.fragmentSource(count).contains("sampler2D"),"legacy has no texture path");checks++;
        }
        System.out.println("AvatarAlbedoShaderTest: "+checks+" checks; no GL execution");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
