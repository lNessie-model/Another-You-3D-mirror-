package com.mirror.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

/** Real material/layout tests. Java source checks do not replace Mali shader/pixel validation. */
public final class AvatarBatchSpecializedShaderTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        for(boolean unlit:new boolean[]{false,true})for(boolean texture:new boolean[]{false,true})for(boolean maps:new boolean[]{false,true}) {
            var m=material(unlit,texture,maps);
            String fs=AvatarBatchSpecializedGpu.fragmentSource(m);
            check(!fs.contains("flat ")&&!fs.contains("vMaterial")&&!fs.contains("vPbr"),"no material varying");
            check(!fs.contains("uniform vec4 uColor")&&!fs.contains("uniform float u"),"material frozen at construction");
            check(!fs.contains("uUseTexture>0.5")&&!fs.contains("uUnlit>0.5")&&!fs.contains("uPbrParams.w>0.5"),"flag branches specialized");
            check(fs.contains("texture(uAtlas,vUV)")==texture,"exact albedo sampling only when used");
            check(fs.contains("texture(uNormalMap,vUV)")==(!unlit&&maps),"normal map usage preserved");
            check(fs.contains("texture(uOrmMap,vUV)")==(!unlit&&maps),"ORM usage preserved");
            check(fs.contains("in vec2 vUV;")==(texture||(!unlit&&maps)),"UV varying exactly when needed");
            if(!unlit) {
                for(String line:AvatarGpuScene.PBR_FRAGMENT.substring(AvatarGpuScene.PBR_FRAGMENT.indexOf("roughness=clamp")).split("\n"))
                    check(fs.contains(line),"original lighting arithmetic line preserved");
                if(maps)check(fs.contains("return normalize(mat3(t,b,n)*normalize(mapped));"),"TBN double normalize unchanged");
            }else check(!fs.contains("vNormal")&&!fs.contains("vPosition")&&!fs.contains("detailNormal"),"unlit unused varyings removed");
            for(boolean mv:new boolean[]{false,true}) {
                String vs=AvatarBatchSpecializedGpu.vertexSource(mv,m);
                check(!vs.contains("aPrimitive")&&!vs.contains("flat ")&&!vs.contains("uWorld["),"scalar world, no packed id fetch");
                check(vs.contains("vec4 world=uWorld*vec4(aPosition,1.0)"),"same world expression");
                check(vs.contains(mv?"gl_Position=uViewProjection[gl_ViewID_OVR]*world":"gl_Position=uViewProjection*world"),"same VP order");
                check(vs.contains("out vec2 vUV;")==(texture||(!unlit&&maps)),"matching UV output");
                check(vs.contains("out vec3 vNormal;")==!unlit,"matching normal output");
            }
        }
        for(float f:new float[]{0f,-0f,1f,.12f,.99999994f,Float.MIN_VALUE,Float.MIN_NORMAL,Float.MAX_VALUE,1e-10f})
            check(Float.floatToRawIntBits(Float.parseFloat(AvatarBatchSpecializedGpu.literal(f)))==Float.floatToRawIntBits(f),"Java decimal roundtrip; GLSL still device gate");
        reject(()->AvatarBatchSpecializedGpu.literal(Float.NaN));reject(()->AvatarBatchSpecializedGpu.literal(Float.POSITIVE_INFINITY));
        AvatarAsset asset=AvatarGlbLoader.load(Files.readAllBytes(Path.of(args[0])));
        boolean[] active=new boolean[asset.nodes().size()];mark(asset,asset.sceneRoots(),active);
        AvatarBatchLayout layout=new AvatarBatchLayout(asset,active);int indices=0;HashSet<String> variants=new HashSet<>();
        for(var e:layout.entries()) {
            var p=asset.meshes().get(e.mesh).primitives().get(e.primitive);var mat=asset.materials().get(p.materialIndex());
            float[] color=new float[4];mat.baseColor().get(color);
            var m=new AvatarBatchSpecializedGpu.Material(color,new float[]{mat.unlit()?1:0,mat.roughness(),mat.textured()?1:0,0},
                    new float[]{mat.normalScale(),mat.occlusionStrength(),mat.metallic(),mat.pbrMaps()?1:0},0);
            variants.add(AvatarBatchSpecializedGpu.fragmentSource(m));
            check(e.firstIndex==indices,"continuous original entry order");
            for(int i=0;i<e.indexCount;i++)check(layout.indices().get(e.firstIndex+i)==p.indices().get(i)+e.firstVertex,"global IBO value preserved");
            indices+=e.indexCount;
        }
        check(indices==layout.indexCount(),"no hidden or omitted triangles");
        check(layout.entries().size()==7,"production Geralt seven entries");
        System.out.println("AvatarBatchSpecializedShaderTest: "+checks+" checks; Geralt entries="+layout.entries().size()+", material variants="+variants.size()+", programs with OVR="+(variants.size()*2));
    }
    private static AvatarBatchSpecializedGpu.Material material(boolean unlit,boolean texture,boolean maps){return new AvatarBatchSpecializedGpu.Material(new float[]{.2f,.4f,.7f,1},new float[]{unlit?1:0,.37f,texture?1:0,0},new float[]{.8f,.6f,.2f,maps?1:0},0);}
    private static void mark(AvatarAsset a,java.nio.IntBuffer nodes,boolean[] active){while(nodes.hasRemaining()){int n=nodes.get();active[n]=true;mark(a,a.nodes().get(n).children(),active);}}
    private static void reject(Runnable r){try{r.run();throw new AssertionError("expected invalid float");}catch(IllegalArgumentException ok){checks++;}}
    private static void check(boolean v,String message){if(!v)throw new AssertionError(message);checks++;}
}
