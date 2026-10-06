package com.mirror.bench;

import java.nio.FloatBuffer;
import java.util.List;

/** Construction-only proof over the same immutable packed colors uploaded by AvatarBatchGpu.
 * The Scene caller separately enforces exact Geralt model identity; this is not an asset selector.
 */
final class AvatarPrimaryColorPolicy {
    private final boolean[] constantWhite;
    private final int primaryEntries,verifiedVertices;
    private AvatarPrimaryColorPolicy(boolean[] modes,int count,int vertices){constantWhite=modes;primaryEntries=count;verifiedVertices=vertices;}
    static AvatarPrimaryColorPolicy verify(AvatarBatchLayout layout) {
        if(layout==null)throw new IllegalArgumentException("Primary color proof requires packed layout");
        return verify(layout.entries(),layout.colors(),layout.vertexCount());
    }
    static AvatarPrimaryColorPolicy verify(List<AvatarBatchLayout.Entry> entries,FloatBuffer colors,int vertices) {
        if(entries==null||entries.isEmpty()||entries.size()>AvatarBatchLayout.MAX_ITEMS||colors==null
                ||vertices<=0||vertices>20_000||colors.limit()!=(long)vertices*4)
            throw new IllegalArgumentException("Complete bounded packed COLOR_0 required");
        boolean[] modes=new boolean[entries.size()];int next=0,count=0,verified=0;
        for(int i=0;i<entries.size();i++) {
            var entry=entries.get(i);
            if(entry==null||entry.node<0||entry.mesh<0||entry.primitive<0||entry.vertexCount<=0
                    ||entry.firstVertex!=next||(long)entry.firstVertex+entry.vertexCount>vertices)
                throw new IllegalArgumentException("Invalid packed COLOR_0 range at entry "+i);
            next+=entry.vertexCount;
            if(entry.mesh==0&&entry.primitive==0) {
                int first=entry.firstVertex*4,end=next*4;
                for(int component=first;component<end;component++)
                    if(Float.floatToRawIntBits(colors.get(component))!=0x3f800000)
                        throw new IllegalArgumentException("Primary COLOR_0 is not exact white at entry "+i+", component "+component);
                modes[i]=true;count++;verified+=entry.vertexCount;
            }
        }
        if(next!=vertices||count==0)throw new IllegalArgumentException("Missing complete mesh0 primitive0 packed primary");
        return new AvatarPrimaryColorPolicy(modes,count,verified);
    }
    int entryCount(){return constantWhite.length;}
    int primaryEntries(){return primaryEntries;}
    int verifiedVertices(){return verifiedVertices;}
    boolean constantWhite(int entry){return constantWhite[entry];}
}
