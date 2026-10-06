package com.mirror.bench;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Tests the actual packed-range policy, including every real Geralt COLOR_0 component. */
public final class AvatarPrimaryColorPolicyTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        var entries=List.of(entry(0,0,0,0,2),entry(1,1,0,2,1),entry(2,0,0,3,2));
        float[] values=new float[20];Arrays.fill(values,1);values[8]=.2f;values[9]=.7f;
        FloatBuffer borrowed=FloatBuffer.wrap(values).asReadOnlyBuffer();borrowed.position(7);
        var proof=AvatarPrimaryColorPolicy.verify(entries,borrowed,5);
        check(proof.entryCount()==3&&proof.primaryEntries()==2&&proof.verifiedVertices()==4,"every repeated primary instance checked");
        check(proof.constantWhite(0)&&!proof.constantWhite(1)&&proof.constantWhite(2),"mesh/primitive identity, not material");
        check(borrowed.position()==7&&borrowed.limit()==20&&values[8]==.2f,"borrowed buffer and unrelated colors unchanged");
        for(int index:new int[]{0,1,2,3,7,12,16,17,18,19})for(float invalid:new float[]{.99999994f,0f,-0f,Float.NaN,Float.POSITIVE_INFINITY}) {
            float[] altered=values.clone();altered[index]=invalid;
            reject(()->AvatarPrimaryColorPolicy.verify(entries,FloatBuffer.wrap(altered),5));
        }
        reject(()->AvatarPrimaryColorPolicy.verify(List.of(entry(0,1,0,0,5)),FloatBuffer.wrap(values),5));
        reject(()->AvatarPrimaryColorPolicy.verify(List.of(),FloatBuffer.wrap(values),5));
        reject(()->AvatarPrimaryColorPolicy.verify(List.of(entry(0,0,0,-1,5)),FloatBuffer.wrap(values),5));
        reject(()->AvatarPrimaryColorPolicy.verify(List.of(entry(0,0,0,0,6)),FloatBuffer.wrap(values),5));
        reject(()->AvatarPrimaryColorPolicy.verify(List.of(entry(0,0,0,Integer.MAX_VALUE,5)),FloatBuffer.wrap(values),5));
        reject(()->AvatarPrimaryColorPolicy.verify(List.of(entry(0,0,0,0,Integer.MAX_VALUE)),FloatBuffer.wrap(values),5));
        reject(()->AvatarPrimaryColorPolicy.verify(List.of(entry(0,0,0,0,0)),FloatBuffer.wrap(values),5));
        reject(()->AvatarPrimaryColorPolicy.verify(List.of(entry(0,0,0,0,2),entry(1,0,0,1,4)),FloatBuffer.wrap(values),5));
        reject(()->AvatarPrimaryColorPolicy.verify(List.of(entry(0,0,0,0,4)),FloatBuffer.wrap(values),5));
        FloatBuffer truncated=FloatBuffer.wrap(values);truncated.limit(19);
        reject(()->AvatarPrimaryColorPolicy.verify(entries,truncated,5));
        reject(()->AvatarPrimaryColorPolicy.verify(entries,FloatBuffer.wrap(values),Integer.MAX_VALUE));
        reject(()->AvatarPrimaryColorPolicy.verify(null,FloatBuffer.wrap(values),5));
        reject(()->AvatarPrimaryColorPolicy.verify(entries,null,5));
        AvatarAsset asset=AvatarGlbLoader.load(Files.readAllBytes(Path.of(args[0])));
        AvatarRig rig=new AvatarRig(asset,Files.readString(Path.of(args[1])));
        boolean[] active=new boolean[asset.nodes().size()];for(int i=0;i<active.length;i++)active[i]=rig.activeNode(i);
        AvatarBatchLayout layout=new AvatarBatchLayout(asset,active);
        float[] before=new float[layout.colors().remaining()];layout.colors().get(before);
        var actual=AvatarPrimaryColorPolicy.verify(layout);
        check(actual.entryCount()==7&&actual.primaryEntries()==1&&actual.verifiedVertices()==13975,"actual Geralt one full primary");
        for(int i=0;i<layout.entries().size();i++) {
            var e=layout.entries().get(i);
            check(actual.constantWhite(i)==(e.mesh==0&&e.primitive==0),"actual eligible entry only");
        }
        float[] after=new float[before.length];layout.colors().get(after);
        check(Arrays.equals(before,after),"actual packed color storage immutable");
        System.out.println("AvatarPrimaryColorPolicyTest: "+checks+" checks; actual Geralt packed colors, no GL or device claim");
    }
    private static AvatarBatchLayout.Entry entry(int node,int mesh,int primitive,int first,int count){return new AvatarBatchLayout.Entry(node,mesh,primitive,first,count,0,3);}
    private static void reject(Runnable action){try{action.run();throw new AssertionError("Expected rejected color proof");}catch(IllegalArgumentException expected){checks++;}}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
}
