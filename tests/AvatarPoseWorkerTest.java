package com.mirror.bench;

import java.nio.FloatBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;

public final class AvatarPoseWorkerTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        arrayBulkCopyContract();
        AvatarAsset asset=AvatarRigTest.fixture();String manifest=AvatarRigTest.manifest().toString();
        try(AvatarPoseWorker worker=new AvatarPoseWorker(asset,manifest)) {
            float[] input=new float[52],angles={12,25,-16};input[9]=.6f;input[25]=.7f;
            long id=worker.submit(input,angles);AvatarPoseWorker.Frame frame=awaitFrame(worker,id);
            compare(asset,manifest,input,angles,frame);
            check(frame.interleaved(0,0).isDirect()&&frame.interleaved(0,0).isReadOnly(),"direct read-only lease");
            try{frame.interleaved(0,0).put(0,1);throw new AssertionError("writable lease");}catch(ReadOnlyBufferException expected){checks++;}
            check(frame.completedNanos()>=frame.startedNanos()&&frame.startedNanos()>=frame.submittedNanos(),"monotonic timings");
            check(frame.rigMs()>=0&&frame.deformMs()>=0&&frame.copyMs()>=0,"finite phase timings");
            worker.release(frame);rejects(()->worker.release(frame),"double release");
            rejects(()->frame.interleaved(0,0),"read after release");
            // Reusing a slot must never make the previous lease valid again.
            long next=worker.submit(input,angles);AvatarPoseWorker.Frame newer=awaitFrame(worker,next);
            rejects(()->worker.release(frame),"stale lease after reuse");worker.release(newer);
        }
        boundedLatestAndClose(asset,manifest);
        readyReplacement(asset,manifest);
        errorPreservesSuccess(manifest);
        System.out.println("AvatarPoseWorkerTest passed: "+checks+" assertions");
        for(String p:args) {
            Path path=Path.of(p);byte[] model=Files.readAllBytes(path);AvatarAsset real=AvatarGlbLoader.load(model);String json=Files.readString(path.resolveSibling("avatar.json"));
            String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(model));
            check(sha.equalsIgnoreCase(new org.json.JSONObject(json).getString("modelSha256")),"tested GLB digest matches manifest");
            System.out.println("Worker asset SHA256="+sha);
            float[] input=new float[52];float[] angles=new float[3];Random random=new Random(612443);
            AvatarRig reference=new AvatarRig(real,json);
            AvatarDeformer referenceDeformer=new AvatarDeformer(real,AvatarDeformer.NormalPolicy.fromManifest(reference.normalPolicy()));
            try(AvatarPoseWorker worker=new AvatarPoseWorker(real,json)) {
                AvatarPoseWorker.Frame held=null;float[] prior=null;
                for(int pose=0;pose<64;pose++) {
                    for(int i=1;i<input.length;i++)input[i]=pose==0?0:pose==1?1:random.nextFloat();
                    angles[0]=random.nextFloat()*90-45;angles[1]=random.nextFloat()*130-65;angles[2]=random.nextFloat()*80-40;
                    long id=worker.submit(input,angles);AvatarPoseWorker.Frame frame=awaitFrame(worker,id);
                    compare(real,reference,referenceDeformer,input,angles,frame);
                    if(held!=null){check(Arrays.equals(prior,copy(held.interleaved(0,0))),"real geometry lease is stable during later copies");worker.release(held);}
                    held=frame;prior=copy(frame.interleaved(0,0));
                }
                worker.release(held);
                System.out.println("Actual GLB worker: 64 changing snapshots bitwise match synchronous output across all nodes/primitives; held-slot bytes stable");
            }
        }
    }
    private static void arrayBulkCopyContract() {
        float[] pattern={0f,-0f,Float.MIN_VALUE,-Float.MIN_VALUE,Float.MIN_NORMAL,-Float.MIN_NORMAL,Float.MAX_VALUE,-Float.MAX_VALUE,.25f,-17.75f};
        for(ByteOrder order:new ByteOrder[]{ByteOrder.BIG_ENDIAN,ByteOrder.LITTLE_ENDIAN}) {
            FloatBuffer sourceWritable=ByteBuffer.allocateDirect(pattern.length*4).order(order).asFloatBuffer();sourceWritable.put(pattern);
            FloatBuffer source=sourceWritable.asReadOnlyBuffer();
            FloatBuffer destination=ByteBuffer.allocateDirect(pattern.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            float[] scratch=new float[pattern.length+16];Arrays.fill(scratch,123);
            source.limit(2);source.position(1);destination.position(2);
            AvatarPoseWorker.copyViaArray(source,destination,scratch);
            for(int i=0;i<pattern.length;i++)check(Float.floatToRawIntBits(destination.get(i))==Float.floatToRawIntBits(pattern[i]),"array bulk copy preserves finite float bits and signed zero");
            check(destination.position()==0&&destination.limit()==pattern.length,"upload cursor ready");
            check(scratch[pattern.length]==123,"scratch tail untouched");
            Arrays.fill(scratch,99);check(destination.get(8)==.25f,"slot does not alias reusable scratch");
            rejects(()->AvatarPoseWorker.copyViaArray(source,destination,new float[1]),"short scratch");
        }
    }
    private static void boundedLatestAndClose(AvatarAsset asset,String manifest)throws Exception {
        AvatarPoseWorker worker=new AvatarPoseWorker(asset,manifest);
        AvatarPoseWorker.Frame[] held=new AvatarPoseWorker.Frame[3];float[][] old=new float[3][];
        for(int i=0;i<3;i++){float[] v=new float[52];v[9]=i*.25f;held[i]=awaitFrame(worker,worker.submit(v,new float[]{i*5,0,0}));old[i]=copy(held[i].interleaved(0,0));}
        check(worker.status().leasedCount==3,"exactly three output slots can be leased");
        float[] values=new float[52],angles=new float[3];values[9]=.4f;
        worker.submit(values,angles);values[9]=.5f;worker.submit(values,angles);values[9]=.6f;angles[0]=33;
        long latest=worker.submit(values,angles);values[9]=0;angles[0]=0;
        var pending=worker.status();check(pending.pendingCount==1&&pending.readyCount==0&&pending.writingCount==0,"bounded pending work without a free output");
        check(pending.droppedInputs==2,"superseded pending inputs counted");
        for(int i=0;i<3;i++)check(Arrays.equals(old[i],copy(held[i].interleaved(0,0))),"leased buffers never overwritten");
        worker.release(held[0]);AvatarPoseWorker.Frame frame=awaitFrame(worker,latest);
        float[] expected=new float[52];expected[9]=.6f;compare(asset,manifest,expected,new float[]{33,0,0},frame);
        check(worker.status().processedInputs==4,"only the latest of three queued inputs processed");
        // All three slots leased again: close must wake a blocked producer and preserve retained buffers.
        worker.submit(new float[52],new float[3]);worker.close();
        await(()->worker.isTerminated(),"worker close");check(worker.submit(new float[52],new float[3])==-1,"closed worker rejects input");
        check(worker.acquireLatest()==null&&worker.status().pendingCount==0,"close cannot publish stale output");
        check(Arrays.equals(old[1],copy(held[1].interleaved(0,0))),"close leaves an existing lease stable");
        worker.release(frame);worker.release(held[1]);worker.release(held[2]);worker.close();
    }
    private static void readyReplacement(AvatarAsset asset,String manifest)throws Exception {
        try(AvatarPoseWorker worker=new AvatarPoseWorker(asset,manifest)) {
            long first=worker.submit(new float[52],new float[3]);await(()->worker.status().publishedFrames==1,"first ready");
            long second=worker.submit(new float[52],new float[]{0,1,0});await(()->worker.status().publishedFrames==2,"second ready");
            check(worker.status().readyCount==1&&worker.status().droppedReadyFrames==1,"only latest completed output retained");
            AvatarPoseWorker.Frame frame=awaitFrame(worker,second);check(frame.inputId()>first,"fresh head smoothing output replaces prior input");
            worker.release(frame);
        }
    }
    private static void errorPreservesSuccess(String manifest)throws Exception {
        AvatarAsset base=AvatarRigTest.fixture();var primitive=base.meshes().get(0).primitives().get(0);
        float[] huge=new float[9];huge[0]=Float.MAX_VALUE;
        var p=new AvatarAsset.Primitive(copy(primitive.positions()),null,null,null,new int[]{0,1,2},
                List.of(new AvatarAsset.Morph(huge,null),new AvatarAsset.Morph(huge.clone(),null),new AvatarAsset.Morph(null,null)),0);
        var mesh=new AvatarAsset.Mesh("Face",List.of(p),base.meshes().get(0).targetNames(),new float[3]);
        AvatarAsset asset=new AvatarAsset(List.of(mesh),base.nodes(),base.materials(),new int[]{0},3,1,100);
        try(AvatarPoseWorker worker=new AvatarPoseWorker(asset,manifest)) {
            long valid=worker.submit(new float[52],new float[3]);await(()->worker.status().publishedFrames==1,"valid ready before failure");
            float[] bad=new float[52];bad[9]=bad[10]=1;worker.submit(bad,new float[3]);
            await(()->worker.failure()!=null&&worker.isTerminated(),"latched deformation failure");
            AvatarPoseWorker.Frame retained=awaitFrame(worker,valid);compare(asset,manifest,new float[52],new float[3],retained);
            check(worker.submit(new float[52],new float[3])==-1,"failed worker stops inputs");
            check(worker.status().readyCount==0&&worker.status().pendingCount==0,"error does not leave a backlog");worker.release(retained);
            worker.close();await(worker::isTerminated,"failed worker close");
            check(worker.stopReceipt().failed()&&!worker.stopReceipt().closeSucceeded(),"computation failure remains in stopped receipt");
            check(worker.stopReceipt().ownershipStopped(),"CPU ownership can stop while computation failure remains recorded");
        }
    }
    private static void compare(AvatarAsset asset,String manifest,float[] input,float[] angles,AvatarPoseWorker.Frame frame)throws Exception {
        AvatarRig reference=new AvatarRig(asset,manifest);
        AvatarDeformer deformer=new AvatarDeformer(asset,AvatarDeformer.NormalPolicy.fromManifest(reference.normalPolicy()));
        compare(asset,reference,deformer,input,angles,frame);
    }
    private static void compare(AvatarAsset asset,AvatarRig reference,AvatarDeformer deformer,float[] input,float[] angles,AvatarPoseWorker.Frame frame) {
        reference.update(input,angles);
        float[] all=new float[asset.nodes().size()*16],matrix=new float[16];frame.copyWorldMatrices(all);
        for(int n=0;n<asset.nodes().size();n++)if(reference.activeNode(n)) {
            reference.copyWorldMatrix(n,matrix);for(int i=0;i<16;i++)if(all[n*16+i]!=matrix[i])throw new AssertionError("world matrix differs");
        }
        for(int m=0;m<asset.meshes().size();m++) {
            float[] weights=new float[asset.meshes().get(m).targetCount()];reference.copyMeshWeights(m,weights);deformer.updateMesh(m,weights);
            for(int p=0;p<deformer.primitives(m).size();p++) {
                if(!Arrays.equals(copy(frame.interleaved(m,p)),copy(deformer.primitives(m).get(p).interleaved())))throw new AssertionError("primitive differs");
                check(frame.primitiveRevision(m,p)>0,"published primitive revision");
            }
        }
        checks++;
    }
    private static AvatarPoseWorker.Frame awaitFrame(AvatarPoseWorker worker,long inputId) {
        final AvatarPoseWorker.Frame[] found=new AvatarPoseWorker.Frame[1];
        await(()->{AvatarPoseWorker.Frame f=worker.acquireLatest();if(f==null)return false;if(f.inputId()!=inputId){worker.release(f);throw new AssertionError("Unexpected input id "+f.inputId()+", expected "+inputId);}found[0]=f;return true;},"output "+inputId);return found[0];
    }
    private static void await(BooleanSupplier condition,String label) {
        long deadline=System.nanoTime()+5_000_000_000L;
        while(!condition.getAsBoolean()){if(System.nanoTime()>deadline)throw new AssertionError("Timed out: "+label);Thread.yield();}
    }
    private static float[] copy(FloatBuffer source){float[] out=new float[source.remaining()];source.get(out);return out;}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    private static void rejects(Runnable action,String label){try{action.run();throw new AssertionError("Expected rejection: "+label);}catch(IllegalArgumentException|IllegalStateException expected){checks++;}}
}
