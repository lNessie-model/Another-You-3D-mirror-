package com.mirror.bench;

import java.nio.FloatBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.List;

/** CPU geometry tests with analytic reference normals; no Android, GL or JSON stubs. */
public final class AvatarDeformerTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        AvatarAsset asset=asset(new float[]{0,0,0, 1,0,0, 0,1,0},new int[]{0,1,2},
                new float[]{0,0,0, 0,0,0, 0,0,1},new float[]{0,0,1, 0,0,1, 0,0,1},null);
        AvatarDeformer d=new AvatarDeformer(asset,AvatarDeformer.NormalPolicy.RECOMPUTE_DEFORMED);
        AvatarDeformer.PrimitiveOutput out=d.primitives(0).get(0);
        long initial=out.revision();check(initial>0,"constructor publishes neutral pose");
        check(out.vertexCount()==3,"vertex count");
        FloatBuffer retained=out.positions();
        float[] weights={.5f};check(d.updateMesh(0,weights),"changed weights update");
        close(retained.get(8),.5,"retained buffer views reflect latest committed pose");
        close(out.normals().get(1),-.5/Math.sqrt(1.25),"analytic normal y");
        close(out.normals().get(2),1/Math.sqrt(1.25),"analytic normal z");
        close(out.interleaved().get(14),.5,"position layout xyz+nxyz");
        close(out.interleaved().get(17),1/Math.sqrt(1.25),"normal layout xyz+nxyz");
        check(out.interleaved().isDirect(),"direct upload buffer");
        close(asset.meshes().get(0).primitives().get(0).positions().get(8),0,"asset never mutated");
        weights[0]=1;close(out.positions().get(8),.5,"input weight array not retained");
        check(d.updateMesh(0,weights),"full morph");
        close(out.normals().get(1),-1/Math.sqrt(2),"full morph normal");
        check(!d.updateMesh(0,weights),"same weights skip work");
        long stable=out.revision();check(stable==initial+2,"one revision per changed pose");
        try{out.positions().put(0,4);throw new AssertionError("mutable public positions");}catch(ReadOnlyBufferException expected){checks++;}
        try{out.interleaved().put(0,4);throw new AssertionError("mutable public upload buffer");}catch(ReadOnlyBufferException expected){checks++;}
        try{d.primitives(0).clear();throw new AssertionError("mutable primitive list");}catch(UnsupportedOperationException expected){checks++;}
        out.positions().position(3);check(out.positions().position()==0,"independent buffer positions");
        invalid(()->d.updateMesh(0,new float[]{Float.NaN}),"finite");
        invalid(()->d.updateMesh(0,new float[]{Float.POSITIVE_INFINITY}),"finite");
        invalid(()->d.updateMesh(0,new float[0]),"count");
        invalid(()->d.updateMesh(0,null),"weights");
        invalid(()->d.updateMesh(2,new float[]{0}),"mesh");
        check(out.revision()==stable,"bad input keeps revision");close(out.positions().get(8),1,"bad input keeps geometry");

        AvatarAsset area=asset(new float[]{0,0,0, 2,0,0, 0,1,0, 0,0,2},new int[]{0,1,2,0,3,1},null,null,null);
        AvatarDeformer weighted=new AvatarDeformer(area,AvatarDeformer.NormalPolicy.RECOMPUTE_DEFORMED);
        FloatBuffer normals=weighted.primitives(0).get(0).normals();
        close(normals.get(1),4/Math.sqrt(20),"shared vertex area weighted y");
        close(normals.get(2),2/Math.sqrt(20),"shared vertex area weighted z");
        close(normals.get(8),1,"unshared triangle normal");

        AvatarAsset collapsed=asset(new float[]{0,0,0,1,0,0,2,0,0},new int[]{0,1,2},null,
                new float[]{0,2,0,0,0,0,0,0,1},null);
        FloatBuffer fallback=new AvatarDeformer(collapsed,AvatarDeformer.NormalPolicy.RECOMPUTE_DEFORMED).primitives(0).get(0).normals();
        close(fallback.get(1),1,"degenerate fallback normalized base normal");
        close(fallback.get(5),1,"degenerate zero-base fallback +Z");
        AvatarAsset tiny=asset(new float[]{0,0,0,1e-30f,0,0,0,1e-30f,0},new int[]{0,1,2},null,null,null);
        close(new AvatarDeformer(tiny,AvatarDeformer.NormalPolicy.RECOMPUTE_DEFORMED).primitives(0).get(0).normals().get(2),1,"tiny triangles normalize safely");

        AvatarAsset authored=asset(new float[]{0,0,0,1,0,0,0,1,0},new int[]{0,1,2},new float[9],
                new float[]{0,0,1,0,0,1,0,0,1},new float[]{0,-1,0,0,-1,0,0,-1,0});
        AvatarDeformer authoredD=new AvatarDeformer(authored,AvatarDeformer.NormalPolicy.AUTHORED);
        authoredD.updateMesh(0,new float[]{1});close(authoredD.primitives(0).get(0).normals().get(1),-1/Math.sqrt(2),"authored normal deltas");
        invalid(()->new AvatarDeformer(asset,AvatarDeformer.NormalPolicy.AUTHORED),"NORMAL");

        AvatarAsset.Primitive safe=asset.meshes().get(0).primitives().get(0);
        AvatarAsset unsafe=asset(new float[]{0,0,0,1,0,0,0,1,0},new int[]{0,1,2},
                new float[]{Float.MAX_VALUE,0,0,0,0,0,0,0,0},null,null);
        AvatarAsset.Mesh two=new AvatarAsset.Mesh("Face",List.of(safe,unsafe.meshes().get(0).primitives().get(0)),List.of("test"),new float[]{0});
        AvatarDeformer transaction=new AvatarDeformer(wrap(two),AvatarDeformer.NormalPolicy.RECOMPUTE_DEFORMED);
        long before=transaction.primitives(0).get(0).revision();
        invalid(()->transaction.updateMesh(0,new float[]{2}),"finite");
        for(AvatarDeformer.PrimitiveOutput p:transaction.primitives(0))check(p.revision()==before,"all primitive revisions remain unchanged after overflow");
        close(transaction.primitives(0).get(0).positions().get(8),0,"later primitive failure cannot publish earlier work");
        check(transaction.updateMesh(0,new float[]{.5f}),"recover after overflow");
        close(transaction.primitives(0).get(0).positions().get(8),.5,"recovery geometry");

        for(int i=0;i<20_000;i++)d.updateMesh(0,weights);
        java.lang.management.ThreadMXBean generic=java.lang.management.ManagementFactory.getThreadMXBean();
        if(generic instanceof com.sun.management.ThreadMXBean allocation&&allocation.isThreadAllocatedMemorySupported()){
            if(!allocation.isThreadAllocatedMemoryEnabled())allocation.setThreadAllocatedMemoryEnabled(true);
            long thread=Thread.currentThread().getId(),start=allocation.getThreadAllocatedBytes(thread);
            for(int i=0;i<10_000;i++)d.updateMesh(0,weights);
            long bytes=allocation.getThreadAllocatedBytes(thread)-start;
            check(bytes<=256,"unchanged weights allocation budget: "+bytes+" bytes for 10k updates");
            // Warm both bulk FloatBuffer.put and the numeric loop beyond HotSpot's compilation threshold.
            for(int i=0;i<30_000;i++){weights[0]=i%2;d.updateMesh(0,weights);}
            start=allocation.getThreadAllocatedBytes(thread);
            for(int i=0;i<10_000;i++){weights[0]=i%2;d.updateMesh(0,weights);}
            bytes=allocation.getThreadAllocatedBytes(thread)-start;
            check(bytes<=256,"changed weights allocation budget: "+bytes+" bytes for 10k updates");
        }
        System.out.println("AvatarDeformerTest: "+checks+" assertions passed");
        for(String path:args)verifyAsset(path);
    }
    private static void verifyAsset(String path)throws Exception {
        AvatarAsset a;try(java.io.InputStream stream=java.nio.file.Files.newInputStream(java.nio.file.Path.of(path))){a=AvatarGlbLoader.load(stream);}
        AvatarDeformer d=new AvatarDeformer(a,AvatarDeformer.NormalPolicy.RECOMPUTE_DEFORMED);
        float[] levels={0,.5f,1};int poses=0;
        for(int mesh=0;mesh<a.meshes().size();mesh++){
            float[] weights=new float[a.meshes().get(mesh).targetCount()];
            for(int target=0;target<weights.length;target++){
                java.util.Arrays.fill(weights,0);
                for(float level:levels){weights[target]=level;d.updateMesh(mesh,weights);validateOutput(d.primitives(mesh));poses++;}
            }
            java.util.Arrays.fill(weights,.5f);d.updateMesh(mesh,weights);validateOutput(d.primitives(mesh));poses++;
            java.util.Arrays.fill(weights,0);d.updateMesh(mesh,weights);validateOutput(d.primitives(mesh));
        }
        System.out.println("Asset morph numeric validation: "+path+"; poses="+poses+"; all positions finite, normals finite/unit; not visual acceptance");
    }
    private static void validateOutput(List<AvatarDeformer.PrimitiveOutput> outputs){
        for(AvatarDeformer.PrimitiveOutput output:outputs){
            FloatBuffer p=output.positions(),n=output.normals();
            for(int i=0;i<p.remaining();i++)if(!Float.isFinite(p.get(i)))throw new AssertionError("asset non-finite position "+i);
            for(int i=0;i<n.remaining();i+=3){double x=n.get(i),y=n.get(i+1),z=n.get(i+2),length=x*x+y*y+z*z;
                if(!Double.isFinite(length)||Math.abs(length-1)>1e-5)throw new AssertionError("asset invalid unit normal "+i/3);}
        }
    }
    private static AvatarAsset asset(float[] p,int[] i,float[] dp,float[] n,float[] dn){
        List<AvatarAsset.Morph> morphs=dp!=null||dn!=null?List.of(new AvatarAsset.Morph(dp,dn)):List.of();
        AvatarAsset.Primitive pr=new AvatarAsset.Primitive(p,n,null,null,i,morphs,0);
        return wrap(new AvatarAsset.Mesh("Face",List.of(pr),morphs.isEmpty()?List.of():List.of("test"),new float[morphs.size()]));
    }
    private static AvatarAsset wrap(AvatarAsset.Mesh m){return new AvatarAsset(List.of(m),List.of(),List.of(),new int[0],0,0,0);}
    interface Attempt{void run();}
    private static void invalid(Attempt body,String word){try{body.run();throw new AssertionError("Expected "+word);}catch(AvatarDeformer.DeformationException e){check(e.getMessage().toLowerCase().contains(word.toLowerCase()),"diagnostic: "+e.getMessage());}}
    private static void close(double actual,double expected,String label){check(Math.abs(actual-expected)<1e-5,label+": "+actual+" vs "+expected);}
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
}
