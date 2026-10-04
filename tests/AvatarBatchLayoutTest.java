package com.mirror.bench;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Geometry packing is checked against independently indexed original primitives. */
public final class AvatarBatchLayoutTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        AvatarAsset asset=fixture(2);AvatarBatchLayout layout=new AvatarBatchLayout(asset,new boolean[]{true,true});
        check(layout.entries().size()==2&&layout.vertexCount()==6&&layout.indexCount()==6,"two instances packed once each");
        check(layout.indices().get(3)==3&&layout.indices().get(5)==5,"second instance indices rebased");
        check(layout.ids().get(2)==0&&layout.ids().get(3)==1,"integer id boundary");
        check(layout.colors().get(0)==.125f&&layout.colors().get(12)==.125f,"COLOR_0 replicated by node instance");
        validate(asset,new boolean[]{true,true});
        AvatarBatchLayout selected=new AvatarBatchLayout(asset,new boolean[]{false,true});
        check(selected.entries().get(0).node==1&&selected.indices().get(0)==0,"selected root filters before rebasing");
        AvatarBatchLayout white=new AvatarBatchLayout(fixture(1,false),new boolean[]{true});
        for(int c=0;c<12;c++)check(white.colors().get(c)==1,"absent COLOR_0 preserves white attribute constant");
        reject(()->new AvatarBatchLayout(asset,new boolean[]{false,false}),"empty scene");
        reject(()->new AvatarBatchLayout(asset,new boolean[1]),"active-node count");
        boolean[] all=new boolean[9];java.util.Arrays.fill(all,true);
        reject(()->new AvatarBatchLayout(fixture(9),all),"debug item count only");
        for(String path:args) {
            AvatarAsset actual=AvatarGlbLoader.load(Files.readAllBytes(Path.of(path)));
            boolean[] active=new boolean[actual.nodes().size()];mark(actual,actual.sceneRoots(),active);
            validate(actual,active);
        }
        System.out.println("AvatarBatchLayoutTest: "+checks+" checks; exact index/COLOR_0/id preservation");
    }
    private static void validate(AvatarAsset asset,boolean[] active) {
        AvatarBatchLayout l=new AvatarBatchLayout(asset,active);int entry=0,vertex=0,index=0;
        for(int n=0;n<active.length;n++)if(active[n]&&asset.nodes().get(n).meshIndex()>=0) {
            int m=asset.nodes().get(n).meshIndex();
            for(int p=0;p<asset.meshes().get(m).primitives().size();p++) {
                var source=asset.meshes().get(m).primitives().get(p);var e=l.entries().get(entry);
                check(e.node==n&&e.mesh==m&&e.primitive==p&&e.firstVertex==vertex&&e.firstIndex==index,"draw order and offsets");
                check(e.vertexCount==source.vertexCount()&&e.indexCount==source.indices().remaining(),"source counts");
                FloatBuffer colors=source.colors();
                for(int v=0;v<source.vertexCount();v++) {
                    if(l.ids().get(vertex+v)!=entry)throw new AssertionError("id mismatches vertex");
                    for(int c=0;c<4;c++)if(Float.floatToRawIntBits(l.colors().get((vertex+v)*4+c))!=
                            Float.floatToRawIntBits(colors==null?1:colors.get(v*4+c)))throw new AssertionError("COLOR_0 mismatch");
                }
                for(int i=0;i<source.indices().remaining();i++)if(l.indices().get(index+i)!=source.indices().get(i)+vertex)
                    throw new AssertionError("global index mismatch");
                checks+=3;vertex+=source.vertexCount();index+=source.indices().remaining();entry++;
            }
        }
        check(vertex==l.vertexCount()&&index==l.indexCount(),"packed geometry totals");
        check(l.colors().isReadOnly()&&l.ids().isReadOnly()&&l.indices().isReadOnly(),"immutable layout views");
        System.out.println("Batch packing: entries="+entry+" vertices="+vertex+" indices="+index+" added_id_bytes="+(vertex*4));
    }
    private static void mark(AvatarAsset a,java.nio.IntBuffer nodes,boolean[] active){while(nodes.hasRemaining()){int n=nodes.get();active[n]=true;mark(a,a.nodes().get(n).children(),active);}}
    private static AvatarAsset fixture(int nodes) {return fixture(nodes,true);}
    private static AvatarAsset fixture(int nodes,boolean hasColors) {
        var p=new AvatarAsset.Primitive(new float[]{0,0,0,1,0,0,0,1,0},null,null,
                hasColors?new float[]{.125f,.25f,.5f,1,.75f,.5f,.25f,1,1,0,0,1}:null,new int[]{0,1,2},List.of(),0);
        var mesh=new AvatarAsset.Mesh("reused",List.of(p),List.of(),new float[0]);List<AvatarAsset.Node> ns=new ArrayList<>();
        float[] matrix={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
        for(int n=0;n<nodes;n++)ns.add(new AvatarAsset.Node("node"+n,0,new int[0],matrix.clone(),matrix.clone(),null));
        return new AvatarAsset(List.of(mesh),ns,List.of(new AvatarAsset.Material("factor",new float[]{1,1,1,1},1,false)),new int[0],nodes*3,nodes,0);
    }
    private static void reject(Runnable f,String m){try{f.run();throw new AssertionError("Expected "+m);}catch(IllegalArgumentException expected){checks++;}}
    private static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);checks++;}
}
