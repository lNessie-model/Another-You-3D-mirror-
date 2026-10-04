package com.mirror.bench;

/** Host shader contracts complement the mandatory Mali compilation and pixel gates. */
public final class AvatarPbrShaderTest {
    public static void main(String[] args){
        require(AvatarGpuScene.mapGpuBytes(2,1,false)==8,"base-level memory");
        require(AvatarGpuScene.mapGpuBytes(2,1,true)==12,"rectangular mip memory");
        require(AvatarGpuScene.mapGpuBytes(2048,2048,true)==22369620,"2K complete mip memory");
        for(int count=1;count<=8;count++)for(boolean multi:new boolean[]{false,true}){
            String v=AvatarBatchGpu.vertexSource(multi,count,true,true),f=AvatarBatchGpu.fragmentSource(count,true,true);
            require(v.contains("flat out highp vec4 vPbrParams;"),"separate PBR material values");
            require(f.contains("flat in highp vec4 vPbrParams;"),"constant per primitive");
            require(f.contains("texture(uOrmMap,vUV).rgb"),"linear packed map sampling");
            require(f.contains("roughness*orm.g")&&f.contains("metallic*orm.b"),"glTF ORM G/B order");
            require(f.contains("mapped.xy*=vPbrParams.x;"),"normal strength affects tangent XY");
            require(!f.contains("uniform vec4 uPbrParams;"),"batched PBR selected in vertex stage");
            require(f.contains("dFdx(vPosition)")&&f.contains("dFdy(vUV)"),"current deformed geometry supplies derivative frame");
            require(f.indexOf("if(uUnlit>0.5)")<f.indexOf("vec3 orm="),"oral unlit material skips detail shading");
        }
        System.out.println("AvatarPbrShaderTest: 128 shader contracts; no GL execution");
    }
    private static void require(boolean good,String message){if(!good)throw new AssertionError(message);}
}
