package com.mirror.bench;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.UUID;
import java.util.concurrent.ThreadFactory;
import org.json.JSONException;

/**
 * Single CPU producer, bounded latest-input/latest-output handoff. Owns a private rig/deformer.
 * Three fixed output slots never alias the mutable deformer cache. GL acquires a lease, copies
 * node matrices and uploads its read-only direct buffers, then releases the lease. The caller
 * must not retain/use buffer views after release. There is no deformation per view.
 */
public final class AvatarPoseWorker implements AutoCloseable {
    public static final int OUTPUT_SLOTS=3;
    private static final int FREE=0,WRITING=1,READY=2,LEASED=3;
    private final Object lock=new Object();
    private final String workerId=UUID.randomUUID().toString();
    private final AvatarAsset asset;
    private final AvatarRig rig;
    private final AvatarDeformer deformer;
    private final float[][] meshWeights;
    private final AvatarDeformer.PrimitiveOutput[][] sourceOutputs;
    private final FloatBuffer[][] sourceBuffers;
    // Shared only by the producer; direct-to-direct FloatBuffer.put uses a scalar loop on older ART.
    private final float[] copyScratch;
    private final Slot[] slots=new Slot[OUTPUT_SLOTS];
    private final float[] pendingValues=new float[52],pendingAngles=new float[3];
    private final float[] workValues=new float[52],workAngles=new float[3],matrix=new float[16];
    private final Thread thread;
    private Slot ready;
    private boolean pending;
    private long pendingId,pendingNanos,submitted,processed,published,droppedInputs,droppedReady;
    private int maxPending,maxReady;
    private volatile boolean closed,runExitMarked;
    private volatile Throwable failure;
    private long requestedNs;

    /** Manifest is parsed once; no JSON or asset parsing runs per submitted animation snapshot. */
    public AvatarPoseWorker(AvatarAsset asset,String manifest)throws JSONException {
        this(asset,manifest,task->new Thread(task,"avatar-pose"));
    }
    AvatarPoseWorker(AvatarAsset asset,String manifest,ThreadFactory threads)throws JSONException {
        if(asset==null)throw new IllegalArgumentException("Worker requires an asset");
        if(threads==null)throw new IllegalArgumentException("Worker requires a thread factory");
        this.asset=asset;rig=new AvatarRig(asset,manifest);
        deformer=new AvatarDeformer(asset,AvatarDeformer.NormalPolicy.fromManifest(rig.normalPolicy()));
        int meshes=asset.meshes().size();meshWeights=new float[meshes][];
        sourceOutputs=new AvatarDeformer.PrimitiveOutput[meshes][];sourceBuffers=new FloatBuffer[meshes][];
        int largestPrimitive=0;
        for(int m=0;m<meshes;m++) {
            meshWeights[m]=new float[asset.meshes().get(m).targetCount()];
            int count=deformer.primitives(m).size();sourceOutputs[m]=new AvatarDeformer.PrimitiveOutput[count];sourceBuffers[m]=new FloatBuffer[count];
            for(int p=0;p<count;p++) {
                sourceOutputs[m][p]=deformer.primitives(m).get(p);sourceBuffers[m][p]=sourceOutputs[m][p].interleaved();
                largestPrimitive=Math.max(largestPrimitive,sourceBuffers[m][p].capacity());
            }
        }
        copyScratch=new float[largestPrimitive];
        for(int i=0;i<slots.length;i++)slots[i]=new Slot();
        thread=threads.newThread(this::run);
        if(thread==null||thread.getState()!=Thread.State.NEW)throw new IllegalArgumentException("Worker requires a new thread");
        thread.setDaemon(true);thread.start();
    }

    /**
     * Copies 52 coefficients and three already-filtered head angles. Returns a unique input ID,
     * or -1 after close/failure. Identical inference IDs are intentionally not a concept here:
     * interpolation/head-smoothing updates must reach the producer too.
     */
    public long submit(float[] values,float[] angles) {
        if(values==null||values.length!=52||angles==null||angles.length!=3)throw new IllegalArgumentException("Expected 52 weights and three head angles");
        for(float v:values)if(!Float.isFinite(v)||v<0||v>1)throw new IllegalArgumentException("Input weights must be finite in [0,1]");
        for(float v:angles)if(!Float.isFinite(v)||Math.abs(v)>90)throw new IllegalArgumentException("Head angles must be finite within +/-90 degrees");
        synchronized(lock) {
            if(closed||failure!=null)return -1;
            if(pending)droppedInputs++;
            System.arraycopy(values,0,pendingValues,0,52);System.arraycopy(angles,0,pendingAngles,0,3);
            pending=true;pendingId=++submitted;pendingNanos=System.nanoTime();maxPending=1;
            lock.notifyAll();return pendingId;
        }
    }

    /** Nonblocking. A new lease wrapper/generation prevents stale and double-release ABA bugs. */
    public Frame acquireLatest() {
        synchronized(lock) {
            if(closed||ready==null)return null;
            Slot slot=ready;ready=null;slot.state=LEASED;slot.generation++;
            return new Frame(this,slot,slot.generation);
        }
    }
    public void release(Frame frame) {
        synchronized(lock) {
            if(frame==null||frame.owner!=this)throw new IllegalArgumentException("Lease belongs to another worker");
            if(frame.released||frame.slot.state!=LEASED||frame.generation!=frame.slot.generation)
                throw new IllegalStateException("Lease was already released or has an obsolete generation");
            frame.released=true;frame.slot.state=FREE;lock.notifyAll();
        }
    }
    public Throwable failure(){return failure;}
    public boolean isTerminated(){return thread.getState()==Thread.State.TERMINATED&&!thread.isAlive();}
    public Status status() {
        synchronized(lock) {
            int leased=0,writing=0;for(Slot slot:slots){if(slot.state==LEASED)leased++;if(slot.state==WRITING)writing++;}
            StopReceipt stop=observeStop(leased,writing);
            return new Status(submitted,processed,published,droppedInputs,droppedReady,pending?1:0,ready==null?0:1,
                    leased,writing,maxPending,maxReady,closed,stop.threadTerminated(),failure,runExitMarked,stop.threadState(),stop);
        }
    }

    /** Signals termination and discards pending/ready work; never joins a UI or GL thread. Leases remain valid until released. */
    @Override public void close() {
        synchronized(lock) {
            if(closed)return;closed=true;requestedNs=System.nanoTime();
            if(pending){droppedInputs++;pending=false;}
            if(ready!=null){ready.state=FREE;ready=null;droppedReady++;}
            lock.notifyAll();
        }
    }

    /** Nonblocking immutable CPU ownership observation; no Thread, buffer, or Throwable escapes. */
    StopReceipt stopReceipt() {
        synchronized(lock) {
            int leased=0,writing=0;
            for(Slot slot:slots){if(slot.state==LEASED)leased++;if(slot.state==WRITING)writing++;}
            return observeStop(leased,writing);
        }
    }
    /** Called under lock. isAlive false also establishes visibility of the exited thread's actions. */
    private StopReceipt observeStop(int leased,int writing) {
        Thread.State state=thread.getState();boolean ended=state==Thread.State.TERMINATED&&!thread.isAlive();
        String type=failure==null?"":failure.getClass().getSimpleName();
        return new StopReceipt(workerId,requestedNs,System.nanoTime(),state,ended,runExitMarked,closed,
                pending?1:0,ready==null?0:1,writing,leased,failure!=null,type.substring(0,Math.min(type.length(),96)));
    }

    static final class StopReceipt {
        private final String workerId,failureType;
        private final long requestedNs,observedNs;
        private final Thread.State threadState;
        private final boolean threadTerminated,runExitMarked,closeRequested,failed;
        private final int pendingCount,readyCount,writingCount,leasedCount;
        private StopReceipt(String workerId,long requestedNs,long observedNs,Thread.State threadState,
                            boolean threadTerminated,boolean runExitMarked,boolean closeRequested,int pendingCount,int readyCount,
                            int writingCount,int leasedCount,boolean failed,String failureType) {
            this.workerId=workerId;this.requestedNs=requestedNs;this.observedNs=observedNs;this.threadState=threadState;
            this.threadTerminated=threadTerminated;this.runExitMarked=runExitMarked;this.closeRequested=closeRequested;this.pendingCount=pendingCount;this.readyCount=readyCount;
            this.writingCount=writingCount;this.leasedCount=leasedCount;this.failed=failed;this.failureType=failureType;
        }
        String workerId(){return workerId;} long requestedNs(){return requestedNs;} long observedNs(){return observedNs;}
        Thread.State threadState(){return threadState;} boolean runExitMarked(){return runExitMarked;}
        boolean threadTerminated(){return threadTerminated;}
        boolean closeRequested(){return closeRequested;} boolean failed(){return failed;} String failureType(){return failureType;}
        int pendingCount(){return pendingCount;} int readyCount(){return readyCount;}
        int writingCount(){return writingCount;} int leasedCount(){return leasedCount;}
        boolean ownershipStopped(){return closeRequested&&runExitMarked&&threadTerminated
                &&pendingCount==0&&readyCount==0&&writingCount==0&&leasedCount==0;}
        boolean closeSucceeded(){return ownershipStopped()&&!failed;}
    }

    private void run() {
        Slot writing=null;
        try {
            while(true) {
                long id,submittedAt;
                synchronized(lock) {
                    while(!closed&&(writing=claim())==null)lock.wait();
                    if(closed)return;
                    System.arraycopy(pendingValues,0,workValues,0,52);System.arraycopy(pendingAngles,0,workAngles,0,3);
                    id=pendingId;submittedAt=pendingNanos;pending=false;processed++;
                }
                calculate(writing,id,submittedAt);
                synchronized(lock) {
                    if(closed){writing.state=FREE;writing=null;return;}
                    if(ready!=null){ready.state=FREE;droppedReady++;}
                    writing.state=READY;ready=writing;writing=null;published++;maxReady=1;
                    lock.notifyAll();
                }
            }
        } catch(Throwable error) {
            synchronized(lock) {
                if(writing!=null)writing.state=FREE;
                if(failure==null)failure=error;
                if(pending){pending=false;droppedInputs++;}
                // Keep the previous successful READY/LEASED snapshots; never publish a partial calculation.
                lock.notifyAll();
            }
        } finally {
            synchronized(lock){runExitMarked=true;lock.notifyAll();}
        }
    }
    /** Called under lock: never reclaim READY while computing, so a failure preserves last success. */
    private Slot claim() {
        if(!pending)return null;
        for(Slot slot:slots)if(slot.state==FREE){slot.state=WRITING;return slot;}
        return null;
    }
    private void calculate(Slot slot,long id,long submittedAt) {
        long start=System.nanoTime();rig.update(workValues,workAngles);
        long rigDone=System.nanoTime();
        for(int m=0;m<meshWeights.length;m++){rig.copyMeshWeights(m,meshWeights[m]);deformer.updateMesh(m,meshWeights[m]);}
        long deformDone=System.nanoTime();
        for(int n=0;n<asset.nodes().size();n++)if(rig.activeNode(n)) {
            rig.copyWorldMatrix(n,matrix);System.arraycopy(matrix,0,slot.world,n*16,16);
        }
        for(int m=0;m<sourceOutputs.length;m++)for(int p=0;p<sourceOutputs[m].length;p++) {
            long revision=sourceOutputs[m][p].revision();if(slot.revisions[m][p]==revision)continue;
            copyViaArray(sourceBuffers[m][p],slot.writable[m][p],copyScratch);slot.revisions[m][p]=revision;
        }
        long done=System.nanoTime();slot.inputId=id;slot.submittedNanos=submittedAt;slot.startedNanos=start;slot.completedNanos=done;
        slot.rigMs=(rigDone-start)/1e6;slot.deformMs=(deformDone-rigDone)/1e6;slot.copyMs=(done-deformDone)/1e6;
    }

    /**
     * Android 30/31 ByteBufferAsFloatBuffer overrides get/put(float[]) with native array transfers,
     * but inherits FloatBuffer.put(FloatBuffer)'s per-float loop. Use the array overloads explicitly.
     * No per-frame allocation, reflection/hidden API, or alias of the leased slot is introduced.
     */
    static void copyViaArray(FloatBuffer source,FloatBuffer destination,float[] scratch) {
        if(source==null||destination==null||scratch==null||destination.capacity()!=source.capacity()||scratch.length<source.capacity())
            throw new IllegalArgumentException("Copy requires matching buffers and a full-primitive scratch array");
        int count=source.capacity();source.clear();destination.clear();
        source.get(scratch,0,count);destination.put(scratch,0,count);destination.flip();
    }

    private final class Slot {
        int state=FREE;long generation,inputId,submittedNanos,startedNanos,completedNanos;
        double rigMs,deformMs,copyMs;
        final float[] world=new float[asset.nodes().size()*16];
        final FloatBuffer[][] writable=new FloatBuffer[sourceOutputs.length][],readOnly=new FloatBuffer[sourceOutputs.length][];
        final long[][] revisions=new long[sourceOutputs.length][];
        Slot() {
            for(int m=0;m<sourceOutputs.length;m++) {
                int count=sourceOutputs[m].length;writable[m]=new FloatBuffer[count];readOnly[m]=new FloatBuffer[count];revisions[m]=new long[count];
                for(int p=0;p<count;p++) {
                    writable[m][p]=ByteBuffer.allocateDirect(sourceBuffers[m][p].capacity()*Float.BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
                    readOnly[m][p]=writable[m][p].asReadOnlyBuffer();
                }
            }
        }
    }

    public static final class Frame {
        private final AvatarPoseWorker owner;private final AvatarPoseWorker.Slot slot;private final long generation;
        private final long inputId,submittedNanos,startedNanos,completedNanos;
        private final double rigMs,deformMs,copyMs;
        private volatile boolean released;
        private Frame(AvatarPoseWorker owner,AvatarPoseWorker.Slot slot,long generation) {
            this.owner=owner;this.slot=slot;this.generation=generation;inputId=slot.inputId;
            submittedNanos=slot.submittedNanos;startedNanos=slot.startedNanos;completedNanos=slot.completedNanos;
            rigMs=slot.rigMs;deformMs=slot.deformMs;copyMs=slot.copyMs;
        }
        public long inputId(){return inputId;}
        public long submittedNanos(){return submittedNanos;}
        public long startedNanos(){return startedNanos;}
        public long completedNanos(){return completedNanos;}
        public double rigMs(){return rigMs;}
        public double deformMs(){return deformMs;}
        public double copyMs(){return copyMs;}
        /** Inactive scene nodes contain zeros and must not be drawn. */
        public void copyWorldMatrices(float[] destination) {
            valid();if(destination==null||destination.length!=slot.world.length)throw new IllegalArgumentException("World destination must have nodeCount*16 entries");
            System.arraycopy(slot.world,0,destination,0,destination.length);
        }
        /** Cached read-only direct view; caller owns its cursor during the lease. Invalid after release. */
        public FloatBuffer interleaved(int mesh,int primitive) {
            valid();FloatBuffer view=slot.readOnly[mesh][primitive];view.clear();return view;
        }
        public long primitiveRevision(int mesh,int primitive){valid();return slot.revisions[mesh][primitive];}
        private void valid(){if(released)throw new IllegalStateException("Lease is released");}
    }
    /** Snapshot suitable for periodic metrics; capacities are 1 pending, 1 ready, 3 total outputs. */
    public static final class Status {
        public final long submittedInputs,processedInputs,publishedFrames,droppedInputs,droppedReadyFrames;
        public final int pendingCount,readyCount,leasedCount,writingCount,maxPendingCount,maxReadyCount;
        public final boolean closed,terminated,runExitMarked;public final Throwable failure;
        public final Thread.State threadState;
        final StopReceipt stop;
        private Status(long submitted,long processed,long published,long dropped,long readyDropped,int pending,int ready,
                       int leased,int writing,int maxPending,int maxReady,boolean closed,boolean terminated,Throwable failure,
                       boolean runExitMarked,Thread.State threadState,StopReceipt stop) {
            submittedInputs=submitted;processedInputs=processed;publishedFrames=published;droppedInputs=dropped;droppedReadyFrames=readyDropped;
            pendingCount=pending;readyCount=ready;leasedCount=leased;writingCount=writing;maxPendingCount=maxPending;maxReadyCount=maxReady;
            this.closed=closed;this.terminated=terminated;this.failure=failure;
            this.runExitMarked=runExitMarked;this.threadState=threadState;
            this.stop=stop;
        }
    }
}
