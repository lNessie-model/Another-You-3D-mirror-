package com.mirror.bench;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Actual imported scenes plus controlled hierarchy/material/geometry changes; no GL calls. */
public final class AvatarDrawPartitionTest {
    private static int checks;
    private static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    private static float[] floats(FloatBuffer b){if(b==null)return null;float[] v=new float[b.remaining()];b.get(v);return v;}
    private static int[] ints(IntBuffer b){int[] v=new int[b.remaining()];b.get(v);return v;}
    private static AvatarRig rig(AvatarAsset a,String manifest)throws Exception{return new AvatarRig(a,manifest);}
    private static int background(AvatarAsset a){
        for(int n=0;n<a.nodes().size();n++)if("StaticBackground".equals(a.nodes().get(n).name()))return n;
        throw new AssertionError("Real combined scene must have background");
    }
    private static AvatarAsset changed(AvatarAsset a,int bg,boolean underHead,boolean morph,boolean lit)throws Exception {
        List<AvatarAsset.Node> nodes=new ArrayList<>(a.nodes());
        if(underHead){
            for(int n=0;n<nodes.size();n++){
                var old=a.nodes().get(n);int[] c=ints(old.children());
                if(n==0)c=Arrays.stream(c).filter(v->v!=bg).toArray();
                if(n==1){int[] add=Arrays.copyOf(c,c.length+1);add[c.length]=bg;c=add;}
                float[] world=floats(old.worldMatrix());
                if(n==bg){world=new float[16];AvatarRig.multiply(world,0,floats(a.nodes().get(1).worldMatrix()),0,floats(old.localMatrix()),0);}
                nodes.set(n,new AvatarAsset.Node(old.name(),old.meshIndex(),c,floats(old.localMatrix()),world,floats(old.weights())));
            }
        }
        List<AvatarAsset.Mesh> meshes=new ArrayList<>(a.meshes());
        if(morph){
            int index=a.nodes().get(bg).meshIndex();var old=meshes.get(index);List<AvatarAsset.Primitive> ps=new ArrayList<>();
            for(var p:old.primitives())ps.add(new AvatarAsset.Primitive(floats(p.positions()),floats(p.normals()),floats(p.texCoords()),floats(p.colors()),ints(p.indices()),List.of(new AvatarAsset.Morph(new float[p.vertexCount()*3],null)),p.materialIndex()));
            meshes.set(index,new AvatarAsset.Mesh(old.name(),ps,List.of("testMorph"),new float[]{0}));
        }
        List<AvatarAsset.Material> materials=new ArrayList<>(a.materials());
        if(lit)for(var p:a.meshes().get(a.nodes().get(bg).meshIndex()).primitives()){
            int index=p.materialIndex();var old=materials.get(index);
            materials.set(index,new AvatarAsset.Material(old.name(),floats(old.baseColor()),old.roughness(),false,old.textured()));
        }
        return new AvatarAsset(meshes,nodes,materials,ints(a.sceneRoots()),a.vertexCount(),a.triangleCount(),a.decodedBytes(),a.albedoAtlas());
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("Three scene folders and one head-only folder required");
        AvatarAsset first=null;String firstManifest=null;
        for(int i=0;i<args.length;i++){
            Path p=Path.of(args[i]);AvatarAsset asset=AvatarGlbLoader.load(Files.readAllBytes(p.resolve("character.glb")));
            String manifest=Files.readString(p.resolve("avatar.json"));AvatarRig rig=rig(asset,manifest);
            AvatarDrawPartition partition=new AvatarDrawPartition(asset,rig);
            int all=0,dynamic=0,statics=0;
            for(int n=0;n<asset.nodes().size();n++){
                boolean a=partition.matchesNode(n,AvatarDrawPartition.Pass.ALL),d=partition.matchesNode(n,AvatarDrawPartition.Pass.DYNAMIC),s=partition.matchesNode(n,AvatarDrawPartition.Pass.STATIC);
                check(!(d&&s),"No node drawn twice");check(a==(d||s),"Split union preserves every legacy draw");
                all+=a?1:0;dynamic+=d?1:0;statics+=s?1:0;
            }
            check(dynamic==1,"Live head remains dynamic");check(all==(i<3?2:1),"Expected actual drawable nodes");
            check(statics==(i<3?1:0),"Only root sibling prop is static");
            check(partition.canCacheTrailingBackground()==(i<3),"Actual scenes eligible, head-only absent");
            int[] copy=partition.staticNodes();if(copy.length>0){copy[0]=-1;check(partition.staticNodes()[0]>=0,"Defensive node list");}
            if(i==0){first=asset;firstManifest=manifest;}
        }
        int bg=background(first);
        for(int variant=0;variant<3;variant++){
            AvatarAsset asset=changed(first,bg,variant==0,variant==1,variant==2);
            AvatarDrawPartition partition=new AvatarDrawPartition(asset,rig(asset,firstManifest));
            check(!partition.canCacheTrailingBackground(),"Animated ancestry, morphs or lit material must not enter initial cache path");
            check(partition.matchesNode(bg,AvatarDrawPartition.Pass.DYNAMIC),"Unsupported prop retains ordinary rendering");
        }
        AvatarDrawPartition p=new AvatarDrawPartition(first,rig(first,firstManifest));
        // Same graph/content, but a static draw precedes the live head in the original node order.
        int[] newToOld={0,4,1,2,3},oldToNew=new int[5];
        for(int n=0;n<5;n++)oldToNew[newToOld[n]]=n;
        List<AvatarAsset.Node> reordered=new ArrayList<>();
        for(int oldIndex:newToOld){
            var old=first.nodes().get(oldIndex);int[] children=ints(old.children());
            for(int c=0;c<children.length;c++)children[c]=oldToNew[children[c]];
            reordered.add(new AvatarAsset.Node(old.name(),old.meshIndex(),children,floats(old.localMatrix()),floats(old.worldMatrix()),floats(old.weights())));
        }
        AvatarAsset beforeHead=new AvatarAsset(first.meshes(),reordered,first.materials(),new int[]{0},first.vertexCount(),first.triangleCount(),first.decodedBytes(),first.albedoAtlas());
        AvatarDrawPartition before=new AvatarDrawPartition(beforeHead,rig(beforeHead,firstManifest));
        check(before.staticNodeCount()==1,"Static node retained in reversed draw order");
        check(!before.canCacheTrailingBackground(),"Cannot move an earlier prop draw after dynamic head");
        check(before.matchesNode(1,AvatarDrawPartition.Pass.ALL),"Original earlier prop still rendered");
        boolean rejected=false;try{p.matchesNode(0,null);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"Explicit pass required");
        rejected=false;try{new AvatarDrawPartition(first,rig(beforeHead,firstManifest));}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"Do not mix classification from a different asset/rig");
        System.out.println("AvatarDrawPartitionTest: "+checks+" checks passed; real assets and conservative fallback, no GL/device verification");
    }
}
