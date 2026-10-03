package com.mirror.bench;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Debug backend layout. Preserves the individual renderer's node/primitive/triangle order. */
final class AvatarBatchLayout {
    static final int MAX_ITEMS=8;
    static final class Entry {
        final int node,mesh,primitive,firstVertex,vertexCount,firstIndex,indexCount;
        Entry(int n,int m,int p,int v,int vc,int i,int ic){node=n;mesh=m;primitive=p;firstVertex=v;vertexCount=vc;firstIndex=i;indexCount=ic;}
    }
    private final List<Entry> entries;
    private final float[] colors;
    private final float[] uvs;
    private final int[] ids,indices;
    AvatarBatchLayout(AvatarAsset asset,boolean[] activeNodes) {
        if(asset==null||activeNodes==null||activeNodes.length!=asset.nodes().size())throw new IllegalArgumentException("Batch active-node count mismatch");
        List<Entry> items=new ArrayList<>();long vertices=0,triIndices=0;
        for(int n=0;n<activeNodes.length;n++)if(activeNodes[n]) {
            int mesh=asset.nodes().get(n).meshIndex();if(mesh<0)continue;
            for(int p=0;p<asset.meshes().get(mesh).primitives().size();p++) {
                var source=asset.meshes().get(mesh).primitives().get(p);int vc=source.vertexCount(),ic=source.indices().remaining();
                if(items.size()>=MAX_ITEMS||vc<=0||ic<=0||ic%3!=0||vertices+vc>20_000||triIndices+ic>90_000)
                    throw new IllegalArgumentException("Debug batch exceeds 8 draw items / 20k vertices / 30k triangles");
                items.add(new Entry(n,mesh,p,(int)vertices,vc,(int)triIndices,ic));vertices+=vc;triIndices+=ic;
            }
        }
        if(items.isEmpty())throw new IllegalArgumentException("Debug batch requires visible geometry");
        entries=Collections.unmodifiableList(items);colors=new float[(int)vertices*4];ids=new int[(int)vertices];indices=new int[(int)triIndices];
        uvs=asset.albedoAtlas()==null?null:new float[(int)vertices*2];
        for(int id=0;id<items.size();id++) {
            Entry e=items.get(id);var source=asset.meshes().get(e.mesh).primitives().get(e.primitive);FloatBuffer color=source.colors();
            if(color!=null&&color.remaining()!=e.vertexCount*4)throw new IllegalArgumentException("Batch COLOR_0 count mismatch");
            FloatBuffer uv=source.texCoords();
            if(uvs!=null&&uv!=null)uv.get(uvs,e.firstVertex*2,e.vertexCount*2);
            for(int v=0;v<e.vertexCount;v++) {
                ids[e.firstVertex+v]=id;
                for(int c=0;c<4;c++)colors[(e.firstVertex+v)*4+c]=color==null?1:color.get(v*4+c);
            }
            IntBuffer sourceIndices=source.indices();
            for(int i=0;i<e.indexCount;i++) {
                int index=sourceIndices.get(i);if(index<0||index>=e.vertexCount)throw new IllegalArgumentException("Batch index outside primitive");
                indices[e.firstIndex+i]=index+e.firstVertex;
            }
        }
    }
    List<Entry> entries(){return entries;}
    int vertexCount(){return ids.length;}
    int indexCount(){return indices.length;}
    FloatBuffer colors(){return FloatBuffer.wrap(colors).asReadOnlyBuffer();}
    FloatBuffer uvs(){return uvs==null?null:FloatBuffer.wrap(uvs).asReadOnlyBuffer();}
    IntBuffer ids(){return IntBuffer.wrap(ids).asReadOnlyBuffer();}
    IntBuffer indices(){return IntBuffer.wrap(indices).asReadOnlyBuffer();}
}
