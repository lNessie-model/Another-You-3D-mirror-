package com.mirror.bench;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

/** Frozen old-path parity plus explicit no-op geometry semantics. Analytic normal tests remain in AvatarDeformerTest. */
public final class AvatarDeformerOptimizationTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        float[] positions={0,0,0,1,0,0,0,1,0};
        var primitive=new AvatarAsset.Primitive(positions,null,null,null,new int[]{0,1,2},List.of(
                new AvatarAsset.Morph(new float[9],null),new AvatarAsset.Morph(new float[]{0,0,0,0,0,0,0,0,1},null)),0);
        var zero=new AvatarAsset.Primitive(positions.clone(),null,null,null,new int[]{0,1,2},List.of(
                new AvatarAsset.Morph(new float[9],null),new AvatarAsset.Morph(new float[9],null)),0);
        AvatarAsset asset=new AvatarAsset(List.of(new AvatarAsset.Mesh("mixed",List.of(primitive,zero),List.of("zero","bend"),new float[2])),List.of(),List.of(),new int[0],6,2,100);
        AvatarDeformer deformer=new AvatarDeformer(asset,AvatarDeformer.NormalPolicy.RECOMPUTE_DEFORMED);
        long old=deformer.primitives(0).get(0).revision(),staticOld=deformer.primitives(0).get(1).revision();
        check(!deformer.updateMesh(0,new float[]{1,0}),"all-zero target changes commit weights without reporting geometry change");
        check(deformer.primitives(0).get(0).revision()==old,"no-op target keeps primitive revision");
        check(deformer.updateMesh(0,new float[]{.5f,1}),"real positional target changes geometry");
        check(deformer.primitives(0).get(0).revision()>old,"changed primitive revision advances");
        check(deformer.primitives(0).get(1).revision()==staticOld,"all-zero primitive skips normal recomputation/upload");
        compare(asset);
        for(String path:args)compare(AvatarGlbLoader.load(Files.readAllBytes(Path.of(path))));
        System.out.println("AvatarDeformerOptimizationTest passed: "+checks+" checks; full buffers bitwise equal to pre-optimization reference");
    }
    private static void compare(AvatarAsset asset) {
        AvatarDeformer current=new AvatarDeformer(asset,AvatarDeformer.NormalPolicy.RECOMPUTE_DEFORMED);
        AvatarDeformerReference original=new AvatarDeformerReference(asset,AvatarDeformerReference.NormalPolicy.RECOMPUTE_DEFORMED);
        Random random=new Random(941903);int poses=0;
        for(int m=0;m<asset.meshes().size();m++) {
            int targets=asset.meshes().get(m).targetCount();float[] weights=new float[targets];
            for(int pose=0;pose<targets+132;pose++) {
                for(int t=0;t<targets;t++)weights[t]=pose<targets?(t==pose?1:0):pose==targets?0:pose==targets+1?1:pose==targets+2?(t%2):pose==targets+3?((t+1)%2):random.nextFloat();
                current.updateMesh(m,weights);original.updateMesh(m,weights);
                for(int p=0;p<current.primitives(m).size();p++) {
                    equal(current.primitives(m).get(p).positions(),original.primitives(m).get(p).positions());
                    equal(current.primitives(m).get(p).normals(),original.primitives(m).get(p).normals());
                    equal(current.primitives(m).get(p).interleaved(),original.primitives(m).get(p).interleaved());
                }
                poses++;
            }
        }
        System.out.println("Golden deformer parity: "+poses+" target/random snapshots over "+asset.meshes().size()+" meshes");
    }
    private static void equal(FloatBuffer actual,FloatBuffer expected) {
        if(actual.remaining()!=expected.remaining())throw new AssertionError("buffer size differs");
        for(int i=0;i<actual.remaining();i++)if(Float.floatToIntBits(actual.get(i))!=Float.floatToIntBits(expected.get(i)))throw new AssertionError("float differs at "+i);
        checks++;
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
