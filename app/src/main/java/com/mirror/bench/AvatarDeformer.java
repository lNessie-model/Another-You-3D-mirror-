package com.mirror.bench;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;

/**
 * Thread-confined CPU morph cache. Update each mesh once per animation change, then reuse the
 * committed output for every view. Node transforms and instance-specific rig state are external.
 * Output views remain backed by this mutable cache; they are read-only, not immutable snapshots.
 * Never update while another thread is reading/uploading these buffers.
 */
public final class AvatarDeformer {
    public enum NormalPolicy { RECOMPUTE_DEFORMED, AUTHORED, SMOOTH_DEFORMED;
        public static NormalPolicy fromManifest(String name){
            return switch(name){case "authored"->AUTHORED;case "recompute-deformed"->RECOMPUTE_DEFORMED;case "smooth-deformed-v1"->SMOOTH_DEFORMED;default->throw new DeformationException("Unsupported normal policy");};
        }
    }
    public static final class DeformationException extends IllegalArgumentException {
        DeformationException(String message){super(message);}
    }
    // Source arrays are private constructor-time copies; no per-component Buffer dispatch at runtime.
    // These payload bounds exclude the existing mutable output caches and Java object headers.
    private static final long MAX_SOURCE_CACHE_BYTES=32L*1024*1024;
    private static final int MAX_SPARSE_INDEX_BYTES=4*1024*1024;
    private final MeshState[] meshes;
    private final long sourceCacheBudget;
    private long sourceCacheBytes;
    private int sparseIndexBytes;

    public AvatarDeformer(AvatarAsset asset,NormalPolicy policy) {
        this(asset,policy,MAX_SOURCE_CACHE_BYTES);
    }
    // Package-visible budget override permits deterministic low-memory boundary regression tests.
    AvatarDeformer(AvatarAsset asset,NormalPolicy policy,long sourceCacheBudget) {
        if(asset==null||policy==null)throw new DeformationException("asset and normal policy are required");
        if(sourceCacheBudget<=0||sourceCacheBudget>MAX_SOURCE_CACHE_BYTES)throw new DeformationException("source cache budget outside profile");
        this.sourceCacheBudget=sourceCacheBudget;
        meshes=new MeshState[asset.meshes().size()];
        for(int i=0;i<meshes.length;i++){
            AvatarAsset.Mesh source=asset.meshes().get(i);
            meshes[i]=new MeshState(source,policy);
            float[] initial=new float[source.targetCount()];source.weights().get(initial);
            updateMesh(i,initial);
        }
    }
    /** Constructor-owned source-array payload, excluding sparse index maps and mutable output caches. */
    long sourceCacheBytes(){return sourceCacheBytes;}

    /**
     * Copies a complete target-weight vector. False means geometry is unchanged: identical weights,
     * or changes only to targets whose effective position/normal deltas are all zero. Such updates
     * perform no deformation, normal work, buffer writes, revision change or per-call allocation.
     * A failed update preserves all of this mesh's last successful outputs and weights.
     */
    public boolean updateMesh(int meshIndex,float[] targetWeights) {
        MeshState mesh=mesh(meshIndex);
        if(targetWeights==null)throw new DeformationException("weights are required");
        if(targetWeights.length!=mesh.weights.length)throw new DeformationException("target weights count mismatch for mesh "+meshIndex);
        boolean changed=!mesh.initialized;
        for(int i=0;i<targetWeights.length;i++){
            float value=targetWeights[i];
            if(!Float.isFinite(value))throw new DeformationException("non-finite weight at mesh "+meshIndex+", target "+i);
            mesh.pendingWeights[i]=value;
            if(value!=mesh.weights[i])changed=true;
        }
        if(!changed)return false;
        // Prepare all affected primitives first. No public output is touched until all validation succeeds.
        boolean geometryChanged=false;
        for(int i=0;i<mesh.primitives.length;i++) {
            PrimitiveState primitive=mesh.primitives[i];
            mesh.pendingUpdates[i]=!mesh.initialized||primitive.affected(mesh.weights,mesh.pendingWeights);
            if(mesh.pendingUpdates[i]){primitive.prepare(mesh.pendingWeights);geometryChanged=true;}
        }
        if(geometryChanged) {
            long revision=mesh.revision+1;
            for(int i=0;i<mesh.primitives.length;i++)if(mesh.pendingUpdates[i])mesh.primitives[i].commit(revision);
            mesh.revision=revision;
        }
        System.arraycopy(mesh.pendingWeights,0,mesh.weights,0,mesh.weights.length);
        mesh.initialized=true;return geometryChanged;
    }
    public List<PrimitiveOutput> primitives(int meshIndex){return mesh(meshIndex).outputs;}
    private MeshState mesh(int index){
        if(index<0||index>=meshes.length)throw new DeformationException("mesh index outside asset");
        return meshes[index];
    }

    /** Positions/normals are mesh-local; no node transform has been applied. */
    public static final class PrimitiveOutput {
        private final float[] positions,normals;
        private final FloatBuffer positionView,normalView,upload;
        private long revision;
        private PrimitiveOutput(int components){
            positions=new float[components];normals=new float[components];
            positionView=FloatBuffer.wrap(positions).asReadOnlyBuffer();normalView=FloatBuffer.wrap(normals).asReadOnlyBuffer();
            upload=ByteBuffer.allocateDirect(components*2*Float.BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
        public int vertexCount(){return positions.length/3;}
        public FloatBuffer positions(){return positionView.asReadOnlyBuffer();}
        public FloatBuffer normals(){return normalView.asReadOnlyBuffer();}
        /** Direct native-order xyz,nxyz per vertex. Cache a view and upload only when revision changes. */
        public FloatBuffer interleaved(){return upload.asReadOnlyBuffer();}
        public long revision(){return revision;}
    }

    private final class MeshState {
        final PrimitiveState[] primitives;
        final List<PrimitiveOutput> outputs;
        final float[] weights,pendingWeights;
        final boolean[] pendingUpdates;
        boolean initialized;
        long revision;
        MeshState(AvatarAsset.Mesh mesh,NormalPolicy policy){
            weights=new float[mesh.targetCount()];pendingWeights=new float[weights.length];
            primitives=new PrimitiveState[mesh.primitives().size()];pendingUpdates=new boolean[primitives.length];List<PrimitiveOutput> list=new ArrayList<>();
            for(int i=0;i<primitives.length;i++){
                primitives[i]=new PrimitiveState(mesh.primitives().get(i),policy);
                if(primitives[i].positionDeltas.length!=weights.length)throw new DeformationException("primitive target count mismatch");
                list.add(primitives[i].output);
            }
            outputs=Collections.unmodifiableList(list);
        }
    }

    private final class PrimitiveState {
        final float[] basePositions,baseNormals;
        final int[] indices;
        final Delta[] positionDeltas,normalDeltas;
        final NormalPolicy policy;
        final PrimitiveOutput output;
        final float[] pendingPositions,pendingNormals,pendingInterleaved;
        final double[] accumulation;
        final int[] normalGroups;
        PrimitiveState(AvatarAsset.Primitive primitive,NormalPolicy policy){
            this.policy=policy;basePositions=copySource(primitive.positions());baseNormals=copySource(primitive.normals());indices=copySource(primitive.indices());
            int components=basePositions.length;
            if(components%3!=0||indices.length%3!=0)throw new DeformationException("triangle/position component count mismatch");
            if(baseNormals!=null&&baseNormals.length!=components)throw new DeformationException("NORMAL count mismatch");
            if(policy==NormalPolicy.AUTHORED&&baseNormals==null)throw new DeformationException("AUTHORED requires base NORMAL");
            for(int i=0;i<indices.length;i++)if(indices[i]<0||indices[i]>=components/3)throw new DeformationException("triangle index outside positions");
            output=new PrimitiveOutput(components);pendingPositions=new float[components];pendingNormals=new float[components];
            pendingInterleaved=new float[components*2];accumulation=new double[components];
            int targets=primitive.morphs().size();positionDeltas=new Delta[targets];normalDeltas=new Delta[targets];
            for(int i=0;i<targets;i++){
                AvatarAsset.Morph morph=primitive.morphs().get(i);FloatBuffer p=morph.positions(),n=morph.normals();
                if((p!=null&&p.remaining()!=components)||(n!=null&&n.remaining()!=components))throw new DeformationException("morph component count mismatch");
                if(policy==NormalPolicy.AUTHORED&&p!=null&&n==null)throw new DeformationException("AUTHORED requires NORMAL delta for every positional morph");
                positionDeltas[i]=delta(p);
                normalDeltas[i]=policy==NormalPolicy.AUTHORED?delta(n):null;
            }
            normalGroups=policy==NormalPolicy.SMOOTH_DEFORMED?smoothingGroups(primitive):null;
        }
        boolean affected(float[] before,float[] after) {
            for(int target=0;target<after.length;target++)if(before[target]!=after[target]
                    &&(positionDeltas[target]!=null||normalDeltas[target]!=null))return true;
            return false;
        }
        void prepare(float[] weights){
            for(int i=0;i<accumulation.length;i++)accumulation[i]=basePositions[i];
            apply(positionDeltas,weights);
            for(int i=0;i<accumulation.length;i++){
                double value=accumulation[i];
                if(!Double.isFinite(value)||Math.abs(value)>Float.MAX_VALUE)throw new DeformationException("non-finite/overflow morph position at component "+i);
                pendingPositions[i]=(float)value;
            }
            if(policy!=NormalPolicy.AUTHORED)recomputeNormals();
            else {
                for(int i=0;i<accumulation.length;i++)accumulation[i]=baseNormals[i];
                apply(normalDeltas,weights);
                for(int i=0;i<accumulation.length;i++)if(!Double.isFinite(accumulation[i])||Math.abs(accumulation[i])>Float.MAX_VALUE)
                    throw new DeformationException("non-finite/overflow authored NORMAL at component "+i);
                normalizeNormals();
            }
        }
        void apply(Delta[] deltas,float[] weights){
            for(int i=0;i<weights.length;i++)if(weights[i]!=0&&deltas[i]!=null)deltas[i].add(accumulation,weights[i]);
        }
        void recomputeNormals(){
            Arrays.fill(accumulation,0);
            // Cross products use double to avoid float overflow/underflow on valid finite geometry.
            // Shared index vertices receive the sum of unnormalized (area-weighted) face normals.
            for(int i=0;i<indices.length;i+=3){
                int a=indices[i]*3,b=indices[i+1]*3,c=indices[i+2]*3;
                double ux=(double)pendingPositions[b]-pendingPositions[a],uy=(double)pendingPositions[b+1]-pendingPositions[a+1],uz=(double)pendingPositions[b+2]-pendingPositions[a+2];
                double vx=(double)pendingPositions[c]-pendingPositions[a],vy=(double)pendingPositions[c+1]-pendingPositions[a+1],vz=(double)pendingPositions[c+2]-pendingPositions[a+2];
                double nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
                if(normalGroups!=null){a=normalGroups[indices[i]]*3;b=normalGroups[indices[i+1]]*3;c=normalGroups[indices[i+2]]*3;}
                accumulation[a]+=nx;accumulation[a+1]+=ny;accumulation[a+2]+=nz;
                accumulation[b]+=nx;accumulation[b+1]+=ny;accumulation[b+2]+=nz;
                accumulation[c]+=nx;accumulation[c+1]+=ny;accumulation[c+2]+=nz;
            }
            normalizeNormals();
        }
        void normalizeNormals(){
            for(int i=0;i<accumulation.length;i+=3){
                int at=normalGroups==null?i:normalGroups[i/3]*3;
                double x=accumulation[at],y=accumulation[at+1],z=accumulation[at+2],length=x*x+y*y+z*z;
                if(!Double.isFinite(length))throw new DeformationException("non-finite NORMAL at vertex "+i/3);
                if(length==0){
                    x=baseNormals==null?0:baseNormals[i];y=baseNormals==null?0:baseNormals[i+1];z=baseNormals==null?1:baseNormals[i+2];
                    length=x*x+y*y+z*z;
                    if(!Double.isFinite(length))throw new DeformationException("non-finite fallback NORMAL at vertex "+i/3);
                    if(length==0){x=0;y=0;z=1;length=1;}
                }
                double inverse=1/Math.sqrt(length);
                pendingNormals[i]=(float)(x*inverse);pendingNormals[i+1]=(float)(y*inverse);pendingNormals[i+2]=(float)(z*inverse);
            }
        }
        /** Exact rest position, authored normal, and all POSITION deltas protect hard edges and motion seams. */
        int[] smoothingGroups(AvatarAsset.Primitive source){
            if(baseNormals==null)throw new DeformationException("SMOOTH_DEFORMED requires authored base NORMAL");
            int count=basePositions.length/3;reserveSource(count);int[] result=new int[count];
            FloatBuffer[] deltas=new FloatBuffer[source.morphs().size()];for(int i=0;i<deltas.length;i++)deltas[i]=source.morphs().get(i).positions();
            HashMap<SmoothingKey,Integer> groups=new HashMap<>();
            for(int v=0;v<count;v++){
                SmoothingKey key=new SmoothingKey(v,basePositions,baseNormals,deltas);Integer first=groups.get(key);
                if(first==null){groups.put(key,v);result[v]=v;}else result[v]=first;
            }
            return result;
        }
        void commit(long revision){
            System.arraycopy(pendingPositions,0,output.positions,0,pendingPositions.length);
            System.arraycopy(pendingNormals,0,output.normals,0,pendingNormals.length);
            for(int i=0;i<pendingPositions.length/3;i++)for(int c=0;c<3;c++){
                pendingInterleaved[i*6+c]=pendingPositions[i*3+c];pendingInterleaved[i*6+3+c]=pendingNormals[i*3+c];
            }
            output.upload.clear();output.upload.put(pendingInterleaved);output.upload.flip();
            output.revision=revision;
        }
    }
    /** Constructor-local keys borrow validated immutable buffers; only the integer groups survive. */
    private static final class SmoothingKey {
        final int vertex,hash;final float[] positions,normals;final FloatBuffer[] deltas;
        SmoothingKey(int vertex,float[] positions,float[] normals,FloatBuffer[] deltas){
            this.vertex=vertex;this.positions=positions;this.normals=normals;this.deltas=deltas;
            int h=1,at=vertex*3;
            for(int c=0;c<3;c++){h=31*h+bits(positions[at+c]);h=31*h+bits(normals[at+c]);}
            for(FloatBuffer d:deltas)if(d!=null)for(int c=0;c<3;c++)h=31*h+bits(d.get(at+c));hash=h;
        }
        private static int bits(float v){return Float.floatToIntBits(v==0?0:v);}
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object value){
            if(!(value instanceof SmoothingKey k)||hash!=k.hash||positions!=k.positions)return false;
            int a=vertex*3,b=k.vertex*3;
            for(int c=0;c<3;c++)if(positions[a+c]!=positions[b+c]||normals[a+c]!=normals[b+c])return false;
            for(FloatBuffer d:deltas)if(d!=null)for(int c=0;c<3;c++)if(d.get(a+c)!=d.get(b+c))return false;
            return true;
        }
    }

    private Delta delta(FloatBuffer source){
        if(source==null)return null;
        int nonzero=0;for(int i=0;i<source.remaining();i++)if(source.get(i)!=0)nonzero++;
        if(nonzero==0)return null;
        if(nonzero<source.remaining()/4&&(long)sparseIndexBytes+nonzero*4L<=MAX_SPARSE_INDEX_BYTES){
            reserveSource(nonzero);
            int[] sparse=new int[nonzero];float[] values=new float[nonzero];
            // Keep the old ascending component order and !=0 pruning (including +/-0).
            for(int i=0,at=0;i<source.remaining();i++)if(source.get(i)!=0){sparse[at]=i;values[at++]=source.get(i);}
            sparseIndexBytes+=nonzero*4;
            return new Delta(values,sparse);
        }
        // Dense targets preserve every value, including signed zero. Do not prune them implicitly.
        return new Delta(copySource(source),null);
    }
    private void reserveSource(int elements){
        long bytes=elements*4L;
        if(bytes>sourceCacheBudget-sourceCacheBytes)throw new DeformationException("source cache budget exceeded");
        sourceCacheBytes+=bytes;
    }
    private float[] copySource(FloatBuffer source){
        if(source==null)return null;
        reserveSource(source.remaining());float[] values=new float[source.remaining()];source.get(values);return values;
    }
    private int[] copySource(IntBuffer source){
        reserveSource(source.remaining());int[] values=new int[source.remaining()];source.get(values);return values;
    }
    private static final class Delta {
        final float[] values;
        final int[] sparse;
        Delta(float[] values,int[] sparse){this.values=values;this.sparse=sparse;}
        void add(double[] output,double weight){
            if(sparse==null){for(int i=0;i<output.length;i++)output[i]+=weight*values[i];}
            else for(int i=0;i<sparse.length;i++){int component=sparse[i];output[component]+=weight*values[i];}
        }
    }
}
