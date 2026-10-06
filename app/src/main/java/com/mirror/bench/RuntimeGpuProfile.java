package com.mirror.bench;

import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Final-default-framebuffer-only opt-in wrapper. Never measures OVR multiview rendering.
 * OVR_multiview forbids timer queries while the draw framebuffer is multiview.
 * Mutators are GL-owner-only; readers only get published JSON.
 * Fixed query pool, fixed scope ring, no per-view objects, no monitor spanning driver calls.
 * Shader/asset/NPU/clear/draw order is owned by the caller and is never changed here.
 */
public final class RuntimeGpuProfile {
    public static final int SCOPE_CAPACITY=128;
    public static final class Snapshot {
        public final long generation,publishedCpuNs;public final int pending;public final String json;
        private Snapshot(long generation,long now,int pending,String json){this.generation=generation;publishedCpuNs=now;this.pending=pending;this.json=json;}
    }
    private static final class Scope {
        long id,pacingEpoch,firstFrame;int target,w,h,vw,vh,views;boolean active;SceneViewSettings scene;
        boolean matches(long epoch,int target,boolean active,SceneViewSettings scene,int w,int h,int vw,int vh,int views){
            if(this.pacingEpoch!=epoch||this.target!=target||this.active!=active||this.w!=w||this.h!=h||this.vw!=vw||this.vh!=vh||this.views!=views)return false;
            if(this.scene==scene)return true;if(this.scene.background!=scene.background||this.scene.mirrorMotion!=scene.mirrorMotion)return false;
            for(int i=0;i<10;i++)if(Float.floatToRawIntBits(this.scene.value(i))!=Float.floatToRawIntBits(scene.value(i)))return false;
            return true;
        }
    }
    private final Thread owner=Thread.currentThread();
    private final long generation;private final boolean requested;private final LongSupplier clock;private final GpuTimerProbe probe;
    private final Scope[] scopes=new Scope[SCOPE_CAPACITY];
    private final long previousGeneration,previousPublishedNs;private final int previousPending;private final boolean previousUnknown;
    private volatile Snapshot published;
    private int scopeWrite,scopeCount,phase;private long callback,scopeSequence,currentScope,activeToken,lastPublish,scopeOverwritten,missingFinalScope,abortedCallbacks;
    private boolean closed;

    public RuntimeGpuProfile(boolean requested,long generation){this(requested,generation,null);}
    /** Call only in a real onSurfaceCreated/replacement context. Previous owner is not reused. */
    public RuntimeGpuProfile(boolean requested,long generation,RuntimeGpuProfile previous){
        this(requested,generation,previous,requested?new Gles30GpuTimerBackend():null,System::nanoTime,GpuTimerProbe.DEFAULT_SAMPLE_EVERY);
    }
    /** Package-only deterministic dependency injection; real entry above always uses actual GLES. */
    RuntimeGpuProfile(boolean requested,long generation,RuntimeGpuProfile previous,GpuTimerProbe.Driver driver,LongSupplier clock,int sampleEvery){
        if(generation<1||clock==null||(previous!=null&&generation<=previous.generation))throw new IllegalArgumentException("New GL generation must increase");
        this.generation=generation;this.requested=requested;this.clock=clock;
        for(int i=0;i<SCOPE_CAPACITY;i++)scopes[i]=new Scope();
        Snapshot old=null;boolean exact=false;
        if(previous!=null){
            // Reading a detached volatile publication is safe across owners. Never invoke old GL.
            if(previous.owner==Thread.currentThread()){
                previous.publish();old=previous.published;exact=true;previous.abandonForReplacement();
            }else old=previous.published;
        }
        previousGeneration=old==null?0:old.generation;previousPending=old==null?0:old.pending;
        previousPublishedNs=old==null?0:old.publishedCpuNs;previousUnknown=old!=null&&!exact;
        probe=new GpuTimerProbe(requested,generation,driver,clock,sampleEvery);publish();
    }
    /** Poll prior final-pass queries once; return this callback's ID. No view query is issued. */
    public long nextCallback(){
        requireOwner();requireOpen();if(phase==3)throw new IllegalStateException("Previous final GPU scope was not ended");
        callback++;phase=0;currentScope=0;activeToken=0;
        try{probe.poll(callback);}catch(RuntimeException failure){publishPreserving(failure);throw failure;}
        return callback;
    }
    /** Pure metadata capture AFTER avatarScene.prepare using the actual immutable frameSceneView.
     * Does not invoke the probe or any GL entry point. View GPU duration remains unavailable.
     */
    public void captureFrameScope(long id,long pacingEpoch,int target,boolean faceActive,SceneViewSettings scene,int w,int h,int vw,int vh,int views){
        requireFrame(id);if(phase!=0)throw new IllegalStateException("Frame scope already captured");
        if(scene==null||pacingEpoch<0||target<0||target>60||w<=0||h<=0||vw<=0||vh<=0||views<1||views>32)throw new IllegalArgumentException("Invalid actual render scope");
        if(requested){
            Scope last=scopeCount==0?null:scopes[(scopeWrite+SCOPE_CAPACITY-1)%SCOPE_CAPACITY];
            if(last==null||!last.matches(pacingEpoch,target,faceActive,scene,w,h,vw,vh,views)){
                last=scopes[scopeWrite];last.id=++scopeSequence;last.pacingEpoch=pacingEpoch;last.firstFrame=id;last.target=target;last.active=faceActive;
                last.scene=scene;last.w=w;last.h=h;last.vw=vw;last.vh=vh;last.views=views;
                scopeWrite=(scopeWrite+1)%SCOPE_CAPACITY;if(scopeCount<SCOPE_CAPACITY)scopeCount++;else scopeOverwritten++;
            }
            currentScope=last.id;
        }
        phase=2;
    }
    /** Invoke only AFTER binding draw framebuffer 0 and finishing its viewport/state/texture setup,
     * immediately before the ordinary final draw. End before any later framebuffer change.
     * The explicit bound flag is a caller assertion, not a substitute for integration review.
     * Reuses exactly the captured frame metadata even if UI controls change meanwhile.
     */
    public void beginFinal(long id,boolean defaultDrawFramebufferBound){
        requireFrame(id);if(phase!=2&&phase!=0)throw new IllegalStateException("Unexpected final scope order");
        if(!defaultDrawFramebufferBound)throw new IllegalArgumentException("Final GPU timer requires draw framebuffer 0 already bound");
        phase=3;if(requested&&currentScope==0){missingFinalScope++;activeToken=0;return;}
        activeToken=probe.begin(GpuTimerProbe.Stage.INTERLACE_WITH_BACKGROUND,id,currentScope);
    }
    public void endFinal(){requireOwner();if(phase!=3)throw new IllegalStateException("No final scope");try{probe.end(activeToken);}finally{activeToken=0;phase=4;}}
    /** Call after ordinary successful frame submission. Reporting allocates at most once per second. */
    public void finishCallback(){
        requireOwner();requireOpen();if(phase==3)throw new IllegalStateException("Unclosed final GPU scope");
        long now=clock.getAsLong();if(now<lastPublish||now-lastPublish>=1_000_000_000L)publish();
    }
    /** In the renderer's catch path, before rethrowing its original error; never replaces that error. */
    public void abort(Throwable original){
        requireOwner();if(original==null)throw new IllegalArgumentException("Original render error required");
        try{if(activeToken!=0)probe.abort(activeToken);}catch(RuntimeException|Error cleanup){if(cleanup!=original)original.addSuppressed(cleanup);}
        finally{activeToken=0;phase=4;abortedCallbacks++;publishPreserving(original);}
    }
    /** Read-only from ANY thread. This method never calls probe, clock, GL, parsing or a monitor. */
    public String statusJson(){return published.json;}
    public Snapshot statusSnapshot(){return published;}
    /** GL owner only. No old-ID deletion and no call to EGL/GL after context loss. */
    public void abandonForReplacement(){
        requireOwner();if(closed)return;closed=true;probe.abandonContext();activeToken=0;phase=4;publish();
    }
    public void close(){
        requireOwner();if(closed)return;closed=true;
        try{probe.close();}catch(RuntimeException|Error original){publishPreserving(original);throw original;}
        finally{activeToken=0;phase=4;}
        publish();
    }
    private void publishPreserving(Throwable original){try{publish();}catch(RuntimeException|Error failure){if(failure!=original)original.addSuppressed(failure);}}
    private void publish(){
        requireOwner();GpuTimerProbe.Snapshot state=probe.snapshot();long now=clock.getAsLong();
        try{
            JSONObject out=new JSONObject().put("schema_version",2).put("requested",requested).put("state",state.state).put("error",state.error)
                    .put("closed",closed).put("context_generation",generation).put("published_cpu_ns",now)
                    .put("cpu_clock_domain","java_system_nanotime_android_clock_monotonic")
                    .put("counter_bits",state.counterBits).put("sample_every_callbacks",state.sampleEvery).put("query_pool_capacity",GpuTimerProbe.SLOTS)
                    .put("pending_queries",state.pending).put("tombstones",state.tombstones).put("pool_full_skips",state.poolFull)
                    .put("disjoint_checks",state.disjointChecks).put("disjoint_events",state.disjointEvents)
                    .put("history_capacity",GpuTimerProbe.HISTORY).put("history_overwritten",state.historyOverwritten)
                    .put("scope_capacity",SCOPE_CAPACITY).put("scope_history_overwritten",scopeOverwritten)
                    .put("final_skipped_missing_scope",missingFinalScope).put("aborted_callbacks",abortedCallbacks)
                    .put("previous_generation",previousGeneration).put("previous_pending_discarded_or_last_published",previousPending)
                    .put("previous_publication_cpu_ns",previousPublishedNs).put("previous_pending_unknown",previousUnknown)
                    .put("previous_pending_upper_bound",previousGeneration==0?0:GpuTimerProbe.SLOTS)
                    .put("background_scope","not_separable_fused_shader")
                    .put("query_scope","final_draw_on_default_framebuffer_only")
                    .put("views_gpu_status","unsupported_ovr_multiview")
                    .put("views_gpu_ns",JSONObject.NULL)
                    .put("views_gpu_policy","No elapsed or timestamp query surrounds OVR multiview work; no group summation or subtraction-derived view duration")
                    .put("max_host_age_ns_exclusive",500_000_000L)
                    .put("scope","Sampled final-default-framebuffer driver-completion intervals with disjoint-free unsigned query results inside a <500ms host guard; survivors only, not exclusive GPU busy or presented FPS")
                    .put("mean_scope","Cumulative valid samples by stage across this context's scopes; not INTERACTIVE-only. Use events plus submission scope metadata to filter; missing scope metadata cannot be reconstructed from current UI")
                    .put("cpu_time_policy","CPU markers bound validity and correlate submission only; gpu_ns always comes from GL query")
                    .put("frame_boundary_policy","Frame metadata captured after pose prepare without a query; sole query begins after framebuffer 0 bind and setup immediately before final draw, ends immediately after draw before next framebuffer change; background stays fused");
            JSONArray metadata=new JSONArray();
            for(int i=0;i<scopeCount;i++){
                Scope s=scopes[(scopeWrite-scopeCount+i+SCOPE_CAPACITY)%SCOPE_CAPACITY];
                metadata.put(new JSONObject().put("scope_id",s.id).put("pacing_epoch",s.pacingEpoch).put("first_callback",s.firstFrame)
                        .put("target_fps",s.target).put("face_active",s.active).put("output_width",s.w).put("output_height",s.h)
                        .put("view_width",s.vw).put("view_height",s.vh).put("view_count",s.views).put("scene_view",new JSONObject(s.scene.toMap())));
            }
            out.put("scopes",metadata);JSONArray events=new JSONArray();int missingMetadata=0;
            for(GpuTimerProbe.Event e:state.events){
                boolean known=false;for(int i=0;i<scopeCount;i++)if(scopes[i].id==e.scopeEpoch){known=true;break;}
                if(!known)missingMetadata++;
                events.put(new JSONObject().put("sequence",e.sequence).put("context_generation",e.contextGeneration).put("scope_id",e.scopeEpoch)
                        .put("scope_metadata_missing",!known).put("callback",e.frameId).put("stage",e.stage.name()).put("status",e.status.name())
                        .put("submitted_cpu_ns",e.submittedCpuNs).put("resolved_cpu_ns",e.resolvedCpuNs).put("gpu_ns",e.gpuNs));
            }
            out.put("events",events).put("missing_scope_metadata_events",missingMetadata);JSONArray stages=new JSONArray();
            for(GpuTimerProbe.Stage stage:GpuTimerProbe.Stage.values()){
                int i=stage.ordinal();stages.put(new JSONObject().put("stage",stage.name()).put("valid_count",state.validCounts[i]).put("missing_count",state.missingCounts[i])
                        .put("total_gpu_ns",state.totalGpuNs[i]).put("mean_gpu_ns",state.validCounts[i]==0?JSONObject.NULL:state.totalGpuNs[i]/state.validCounts[i]));
            }
            out.put("stages",stages);published=new Snapshot(generation,now,state.pending,out.toString());lastPublish=now;
        }catch(Exception failure){throw new IllegalStateException("GPU profile publication failed",failure);}
    }
    private void requireFrame(long id){requireOwner();requireOpen();if(id!=callback||id<1)throw new IllegalArgumentException("Wrong callback id");}
    private void requireOpen(){if(closed)throw new IllegalStateException("GPU profile closed");}
    private void requireOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("GPU profile belongs to GL owner");}
}
