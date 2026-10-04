package com.mirror.bench;

import java.util.Arrays;

/** Conservative immutable draw selection for the future static background cache.
 * No GPU handles or live pose data. ALL preserves the legacy selected-node order.
 */
final class AvatarDrawPartition {
    enum Pass { ALL, DYNAMIC, STATIC }
    private final boolean[] drawable,statics;
    private final int[] staticNodes;
    private final boolean trailing;

    AvatarDrawPartition(AvatarAsset asset,AvatarRig rig) {
        if(asset==null||rig==null||rig.asset()!=asset)throw new IllegalArgumentException("Matching asset/rig required");
        int size=asset.nodes().size();drawable=new boolean[size];statics=new boolean[size];
        int[] parents=new int[size];Arrays.fill(parents,-1);
        for(int n=0;n<size;n++){
            var children=asset.nodes().get(n).children();
            while(children.hasRemaining())parents[children.get()]=n;
        }
        int[] selected=new int[size];int count=0;boolean sawStatic=false,ordered=true;
        for(int n=0;n<size;n++){
            int meshIndex=asset.nodes().get(n).meshIndex();
            drawable[n]=rig.activeNode(n)&&meshIndex>=0;
            if(!drawable[n])continue;
            var mesh=asset.meshes().get(meshIndex);
            boolean safe=mesh.targetCount()==0;
            for(int parent=n;safe&&parent>=0;parent=parents[parent])
                if(parent==rig.headNodeIndex()||parent==rig.jawNodeIndex()
                        ||parent==rig.leftEyeNodeIndex()||parent==rig.rightEyeNodeIndex())safe=false;
            // Initial cache path only admits fixed, unlit props; other materials retain ordinary drawing.
            for(var primitive:mesh.primitives())if(!asset.materials().get(primitive.materialIndex()).unlit())safe=false;
            statics[n]=safe;
            if(safe){selected[count++]=n;sawStatic=true;}
            else if(sawStatic)ordered=false;
        }
        staticNodes=Arrays.copyOf(selected,count);trailing=count>0&&ordered;
    }
    boolean matchesNode(int node,Pass pass) {
        if(pass==null)throw new IllegalArgumentException("Draw pass required");
        if(node<0||node>=drawable.length)throw new IllegalArgumentException("Node outside asset");
        return drawable[node]&&(pass==Pass.ALL||(pass==Pass.STATIC?statics[node]:!statics[node]));
    }
    int staticNodeCount(){return staticNodes.length;}
    int[] staticNodes(){return staticNodes.clone();}
    /** Restoring cached props after dynamic geometry must preserve the original draw order. */
    boolean canCacheTrailingBackground(){return trailing;}
}
