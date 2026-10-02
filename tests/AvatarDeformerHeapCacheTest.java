package com.mirror.bench;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

/** Source-cache payload budgets and bitwise parity across dense/sparse, both normal policies. */
public final class AvatarDeformerHeapCacheTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        AvatarAsset asset=fixture();
        var recompute=new AvatarDeformer(asset,AvatarDeformer.NormalPolicy.RECOMPUTE_DEFORMED);
        check(recompute.sourceCacheBytes()==172,"base, indices, dense and compact sparse payload counted exactly");
        var authored=new AvatarDeformer(asset,AvatarDeformer.NormalPolicy.AUTHORED);
        check(authored.sourceCacheBytes()==224,"authored normal delta payload counted");
        new AvatarDeformer(asset,AvatarDeformer.NormalPolicy.RECOMPUTE_DEFORMED,172);
        reject(()->new AvatarDeformer(asset,AvatarDeformer.NormalPolicy.RECOMPUTE_DEFORMED,171),"source cache budget");
        reject(()->new AvatarDeformer(asset,AvatarDeformer.NormalPolicy.AUTHORED,223),"source cache budget");
        reject(()->new AvatarDeformer(asset,AvatarDeformer.NormalPolicy.RECOMPUTE_DEFORMED,0),"budget");
        reject(()->new AvatarDeformer(asset,AvatarDeformer.NormalPolicy.RECOMPUTE_DEFORMED,32L*1024*1024+1),"budget");
        compare(asset,false);compare(asset,true);
        for(String path:args) {
            byte[] bytes=Files.readAllBytes(Path.of(path));AvatarAsset actual=AvatarGlbLoader.load(bytes);
            var d=new AvatarDeformer(actual,AvatarDeformer.NormalPolicy.RECOMPUTE_DEFORMED);
            long expected=expectedSourceBytes(actual,false);check(d.sourceCacheBytes()==expected,"actual model exact cache budget");
            for(var mesh:actual.meshes())for(var primitive:mesh.primitives()) {
                check(!primitive.positions().isDirect()&&primitive.positions().isReadOnly(),"asset base source is heap/read-only");
                check(!primitive.indices().isDirect()&&primitive.indices().isReadOnly(),"asset indices source is heap/read-only");
                for(var morph:primitive.morphs())if(morph.positions()!=null)
                    check(!morph.positions().isDirect()&&morph.positions().isReadOnly(),"asset morph source is heap/read-only");
            }
            System.out.println("Heap cache asset SHA256="+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))+
                    "; source_cache_bytes="+expected+"; source_readonly_heap=true");
            compare(actual,false);
        }
        System.out.println("AvatarDeformerHeapCacheTest passed: "+checks+" checks; dense/sparse and signed-zero bitwise parity");
    }
    private static AvatarAsset fixture() {
        float[] base={-0f,0,0, 1,0,0, 1,1,0, 0,1,0};
        float[] normals={-0f,0,1, 0,0,1, 0,0,1, 0,0,1};
        // >=3 nonzero components forces dense; explicit +/-0 must not be dropped on this path.
        float[] dense={-0f,Float.MIN_VALUE,.25f, 0,0,-.1f, -.2f,0,0, -0f,0,0};
        float[] sparse={-0f,0,0, 0,0,0, 0,0,Float.MIN_NORMAL, 0,-0f,0};
        float[] dn={0,-.5f,0, 0,-.5f,0, 0,-.5f,0, 0,-.5f,-0f};
        float[] sn={0,0,0, 0,0,0, .25f,0,0, -0f,0,0};
        float[] zero=new float[12];Arrays.fill(zero,-0f);
        var primitive=new AvatarAsset.Primitive(base,normals,null,null,new int[]{0,1,2,0,2,3},List.of(
                new AvatarAsset.Morph(dense,dn),new AvatarAsset.Morph(sparse,sn),new AvatarAsset.Morph(zero,zero.clone())),0);
        var mesh=new AvatarAsset.Mesh("mixed",List.of(primitive),List.of("dense","sparse","zero"),new float[3]);
        return new AvatarAsset(List.of(mesh),List.of(),List.of(),new int[0],4,2,0);
    }
    private static void compare(AvatarAsset asset,boolean authored) {
        var current=new AvatarDeformer(asset,authored?AvatarDeformer.NormalPolicy.AUTHORED:AvatarDeformer.NormalPolicy.RECOMPUTE_DEFORMED);
        var reference=new AvatarDeformerReference(asset,authored?AvatarDeformerReference.NormalPolicy.AUTHORED:AvatarDeformerReference.NormalPolicy.RECOMPUTE_DEFORMED);
        Random random=new Random(7828193);
        for(int m=0;m<asset.meshes().size();m++) {
            float[] weights=new float[asset.meshes().get(m).targetCount()];
            for(int pose=0;pose<96;pose++) {
                for(int t=0;t<weights.length;t++)weights[t]=pose==0?-0f:pose==1?Float.MIN_VALUE:pose==2?-Float.MIN_VALUE:
                        pose==3?1:pose==4?-1:pose==5?Float.MIN_NORMAL:(random.nextFloat()-.5f)*16;
                current.updateMesh(m,weights);reference.updateMesh(m,weights);
                for(int p=0;p<current.primitives(m).size();p++) {
                    equal(current.primitives(m).get(p).positions(),reference.primitives(m).get(p).positions());
                    equal(current.primitives(m).get(p).normals(),reference.primitives(m).get(p).normals());
                    equal(current.primitives(m).get(p).interleaved(),reference.primitives(m).get(p).interleaved());
                }
            }
        }
    }
    private static long expectedSourceBytes(AvatarAsset asset,boolean authored) {
        long bytes=0,sparseMap=0;
        for(var mesh:asset.meshes())for(var p:mesh.primitives()) {
            bytes+=p.positions().remaining()*4L+p.indices().remaining()*4L;
            if(p.normals()!=null)bytes+=p.normals().remaining()*4L;
            for(var morph:p.morphs())for(FloatBuffer source:new FloatBuffer[]{morph.positions(),authored?morph.normals():null}) {
                if(source==null)continue;int nonzero=0;
                for(int i=0;i<source.remaining();i++)if(source.get(i)!=0)nonzero++;
                if(nonzero==0)continue;
                if(nonzero<source.remaining()/4&&sparseMap+nonzero*4L<=4L*1024*1024) {
                    bytes+=nonzero*4L;sparseMap+=nonzero*4L;
                } else bytes+=source.remaining()*4L;
            }
        }
        return bytes;
    }
    private static void equal(FloatBuffer a,FloatBuffer b) {
        check(a.remaining()==b.remaining(),"same buffer size");
        for(int i=0;i<a.remaining();i++)if(Float.floatToRawIntBits(a.get(i))!=Float.floatToRawIntBits(b.get(i)))
            throw new AssertionError("raw float mismatch at "+i+": "+a.get(i)+" vs "+b.get(i));
        checks++;
    }
    private static void reject(Runnable r,String text) {
        try{r.run();throw new AssertionError("expected failure: "+text);}
        catch(AvatarDeformer.DeformationException e){check(e.getMessage().contains(text),"specific diagnostic: "+e.getMessage());}
    }
    private static void check(boolean v,String message){if(!v)throw new AssertionError(message);checks++;}
}
