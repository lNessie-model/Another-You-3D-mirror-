// Frozen pre-optimization reference. Production source SHA256: 93A9FA2CFBE81B16C8EE40A1A3637B785C8D52681BFD3C248490F3E871D03D36
package com.mirror.bench;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Thread-confined CPU morph cache. Update each mesh once per animation change, then reuse the
 * committed output for every view. Node transforms and instance-specific rig state are external.
 * Output views remain backed by this mutable cache; they are read-only, not immutable snapshots.
 * Never update while another thread is reading/uploading these buffers.
 */
public final class AvatarDeformerReference {
    public enum NormalPolicy { RECOMPUTE_DEFORMED, AUTHORED }
    public static final class DeformationException extends IllegalArgumentException {
        DeformationException(String message){super(message);}
    }
    // Sparse index maps are optional accelerators. Dense targets keep immutable asset buffers.
    private static final int MAX_SPARSE_INDEX_BYTES=4*1024*1024;
    private final MeshState[] meshes;
    private int sparseIndexBytes;

    public AvatarDeformerReference(AvatarAsset asset,NormalPolicy policy) {
        if(asset==null||policy==null)throw new DeformationException("asset and normal policy are required");
        meshes=new MeshState[asset.meshes().size()];
        for(int i=0;i<meshes.length;i++){
            AvatarAsset.Mesh source=asset.meshes().get(i);
            meshes[i]=new MeshState(source,policy);
            float[] initial=new float[source.targetCount()];source.weights().get(initial);
            updateMesh(i,initial);
        }
    }

    /**
     * Copies a complete target-weight vector. False means the committed weights are unchanged:
     * no deformation, normal work, buffer writes, revision change or per-call allocation occurs.
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
        // Prepare every primitive first. No public output is touched until all validation succeeds.
        for(int i=0;i<mesh.primitives.length;i++)mesh.primitives[i].prepare(mesh.pendingWeights);
        long revision=mesh.revision+1;
        for(int i=0;i<mesh.primitives.length;i++)mesh.primitives[i].commit(revision);
        System.arraycopy(mesh.pendingWeights,0,mesh.weights,0,mesh.weights.length);
        mesh.initialized=true;mesh.revision=revision;return true;
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
        boolean initialized;
        long revision;
        MeshState(AvatarAsset.Mesh mesh,NormalPolicy policy){
            weights=new float[mesh.targetCount()];pendingWeights=new float[weights.length];
            primitives=new PrimitiveState[mesh.primitives().size()];List<PrimitiveOutput> list=new ArrayList<>();
            for(int i=0;i<primitives.length;i++){
                primitives[i]=new PrimitiveState(mesh.primitives().get(i),policy);
                if(primitives[i].positionDeltas.length!=weights.length)throw new DeformationException("primitive target count mismatch");
                list.add(primitives[i].output);
            }
            outputs=Collections.unmodifiableList(list);
        }
    }

    private final class PrimitiveState {
        final FloatBuffer basePositions,baseNormals;
        final IntBuffer indices;
        final Delta[] positionDeltas,normalDeltas;
        final NormalPolicy policy;
        final PrimitiveOutput output;
        final float[] pendingPositions,pendingNormals;
        final double[] accumulation;
        PrimitiveState(AvatarAsset.Primitive primitive,NormalPolicy policy){
            this.policy=policy;basePositions=primitive.positions();baseNormals=primitive.normals();indices=primitive.indices();
            int components=basePositions.remaining();
            if(components%3!=0||indices.remaining()%3!=0)throw new DeformationException("triangle/position component count mismatch");
            if(baseNormals!=null&&baseNormals.remaining()!=components)throw new DeformationException("NORMAL count mismatch");
            if(policy==NormalPolicy.AUTHORED&&baseNormals==null)throw new DeformationException("AUTHORED requires base NORMAL");
            for(int i=0;i<indices.remaining();i++)if(indices.get(i)<0||indices.get(i)>=components/3)throw new DeformationException("triangle index outside positions");
            output=new PrimitiveOutput(components);pendingPositions=new float[components];pendingNormals=new float[components];accumulation=new double[components];
            int targets=primitive.morphs().size();positionDeltas=new Delta[targets];normalDeltas=new Delta[targets];
            for(int i=0;i<targets;i++){
                AvatarAsset.Morph morph=primitive.morphs().get(i);FloatBuffer p=morph.positions(),n=morph.normals();
                if((p!=null&&p.remaining()!=components)||(n!=null&&n.remaining()!=components))throw new DeformationException("morph component count mismatch");
                if(policy==NormalPolicy.AUTHORED&&p!=null&&n==null)throw new DeformationException("AUTHORED requires NORMAL delta for every positional morph");
                positionDeltas[i]=delta(p);
                normalDeltas[i]=policy==NormalPolicy.AUTHORED?delta(n):null;
            }
        }
        void prepare(float[] weights){
            for(int i=0;i<accumulation.length;i++)accumulation[i]=basePositions.get(i);
            apply(positionDeltas,weights);
            for(int i=0;i<accumulation.length;i++){
                double value=accumulation[i];
                if(!Double.isFinite(value)||Math.abs(value)>Float.MAX_VALUE)throw new DeformationException("non-finite/overflow morph position at component "+i);
                pendingPositions[i]=(float)value;
            }
            if(policy==NormalPolicy.RECOMPUTE_DEFORMED)recomputeNormals();
            else {
                for(int i=0;i<accumulation.length;i++)accumulation[i]=baseNormals.get(i);
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
            for(int i=0;i<indices.remaining();i+=3){
                int a=indices.get(i)*3,b=indices.get(i+1)*3,c=indices.get(i+2)*3;
                double ux=(double)pendingPositions[b]-pendingPositions[a],uy=(double)pendingPositions[b+1]-pendingPositions[a+1],uz=(double)pendingPositions[b+2]-pendingPositions[a+2];
                double vx=(double)pendingPositions[c]-pendingPositions[a],vy=(double)pendingPositions[c+1]-pendingPositions[a+1],vz=(double)pendingPositions[c+2]-pendingPositions[a+2];
                double nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
                accumulation[a]+=nx;accumulation[a+1]+=ny;accumulation[a+2]+=nz;
                accumulation[b]+=nx;accumulation[b+1]+=ny;accumulation[b+2]+=nz;
                accumulation[c]+=nx;accumulation[c+1]+=ny;accumulation[c+2]+=nz;
            }
            normalizeNormals();
        }
        void normalizeNormals(){
            for(int i=0;i<accumulation.length;i+=3){
                double x=accumulation[i],y=accumulation[i+1],z=accumulation[i+2],length=x*x+y*y+z*z;
                if(!Double.isFinite(length))throw new DeformationException("non-finite NORMAL at vertex "+i/3);
                if(length==0){
                    x=baseNormals==null?0:baseNormals.get(i);y=baseNormals==null?0:baseNormals.get(i+1);z=baseNormals==null?1:baseNormals.get(i+2);
                    length=x*x+y*y+z*z;
                    if(!Double.isFinite(length))throw new DeformationException("non-finite fallback NORMAL at vertex "+i/3);
                    if(length==0){x=0;y=0;z=1;length=1;}
                }
                double inverse=1/Math.sqrt(length);
                pendingNormals[i]=(float)(x*inverse);pendingNormals[i+1]=(float)(y*inverse);pendingNormals[i+2]=(float)(z*inverse);
            }
        }
        void commit(long revision){
            System.arraycopy(pendingPositions,0,output.positions,0,pendingPositions.length);
            System.arraycopy(pendingNormals,0,output.normals,0,pendingNormals.length);
            for(int i=0;i<pendingPositions.length/3;i++)for(int c=0;c<3;c++){
                output.upload.put(i*6+c,pendingPositions[i*3+c]);output.upload.put(i*6+3+c,pendingNormals[i*3+c]);
            }
            output.revision=revision;
        }
    }

    private Delta delta(FloatBuffer source){
        if(source==null)return null;
        int nonzero=0;for(int i=0;i<source.remaining();i++)if(source.get(i)!=0)nonzero++;
        if(nonzero==0)return null;
        int[] sparse=null;
        if(nonzero<source.remaining()/4&&(long)sparseIndexBytes+nonzero*4L<=MAX_SPARSE_INDEX_BYTES){
            sparse=new int[nonzero];for(int i=0,at=0;i<source.remaining();i++)if(source.get(i)!=0)sparse[at++]=i;
            sparseIndexBytes+=nonzero*4;
        }
        return new Delta(source,sparse);
    }
    private static final class Delta {
        final FloatBuffer values;
        final int[] sparse;
        Delta(FloatBuffer values,int[] sparse){this.values=values;this.sparse=sparse;}
        void add(double[] output,double weight){
            if(sparse==null){for(int i=0;i<output.length;i++)output[i]+=weight*values.get(i);}
            else for(int i=0;i<sparse.length;i++){int component=sparse[i];output[component]+=weight*values.get(component);}
        }
    }
}
