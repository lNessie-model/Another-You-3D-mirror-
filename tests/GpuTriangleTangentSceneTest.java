package com.mirror.bench;
import android.opengl.GLES30;
import android.opengl.GLES31;
import java.nio.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.JSONObject;

/** Real Scene/real bounded producer. GL fake checks upload bytes and API state, not driver pixels. */
public final class GpuTriangleTangentSceneTest {
 static int checks;
 static void ok(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 interface Run{void run()throws Exception;}
 static void reject(Run r)throws Exception{try{r.run();throw new AssertionError("unexpected success");}catch(IllegalArgumentException|IllegalStateException expected){checks++;}}
 static JSONObject t(AvatarGpuScene s)throws Exception{return s.status().getJSONObject("gpu_triangle_tangent");}
 static AvatarPoseWorker worker(AvatarGpuScene s)throws Exception{Field f=AvatarGpuScene.class.getDeclaredField("poseWorker");f.setAccessible(true);return (AvatarPoseWorker)f.get(s);}
 static void applied(AvatarGpuScene s,float[] values,float[] angles)throws Exception{
  s.resumeCpuPose();long first=worker(s).status().submittedInputs+1,deadline=System.nanoTime()+3_000_000_000L;
  do{s.prepare(values,angles);if(s.status().getLong("applied_pose_input_id")>=first)return;Thread.sleep(4);}while(System.nanoTime()<deadline);
  throw new AssertionError("actual worker did not apply requested pose");
 }
 static void unapplied(AvatarGpuScene s,float value)throws Exception{
  var before=worker(s).status();float[] v=new float[52];v[25]=value;long id=s.gpuTriangleTangentDiagnosticSubmitUnapplied(v,new float[]{value*15,value*12,0});long deadline=System.nanoTime()+3_000_000_000L;
  do{var q=worker(s).status();if(q.submittedInputs==id&&q.publishedFrames>before.publishedFrames&&q.pendingCount==0&&q.writingCount==0&&q.readyCount==1&&q.leasedCount==0)return;Thread.sleep(4);}while(System.nanoTime()<deadline);
  throw new AssertionError("unapplied producer frame did not complete");
 }
 static void stop(AvatarGpuScene scene)throws Exception{AvatarPoseWorker w=worker(scene);scene.dispose();if(w!=null){long deadline=System.nanoTime()+3_000_000_000L;while(!w.isTerminated()&&System.nanoTime()<deadline)Thread.sleep(4);ok(w.isTerminated()&&w.status().leasedCount==0,"worker closed without leaked lease");}}
 public static void main(String[] args)throws Exception{
  var asset=AvatarGlbLoader.load(Files.readAllBytes(Path.of(args[0])));String manifest=Files.readString(Path.of(args[1]));float[] vp=new float[64];for(int i=0;i<64;i++)vp[i]=i%17==0?1:i*.001f;
  GLES30.reset();var scene=AvatarGpuScene.fromAsset(asset,manifest,SrgbViewPolicy.GERALT,true,true,AvatarGpuScene.DrawMode.INDIVIDUAL);
  try{
   ok(!t(scene).getBoolean("requested")&&t(scene).getString("actual").equals("disabled"),"default OFF with real worker");int buffers=GLES30.buffers.size(),textures=GLES30.textures.size();scene.enableGpuTriangleTangentRuntime();
   ok(GLES30.buffers.size()==buffers&&GLES30.textures.size()==textures+1,"fixed one table, borrowed geometry");ok(scene.status().getString("pose_backend").equals("bounded_cpu_worker"),"async retained");
   ok(t(scene).getLong("owned_pn_snapshot_bytes")==0&&t(scene).getString("actual").equals("ready_unsubmitted"),"initial uploaded neutral revision with no PN copy");
   reject(scene::enableGpuTriangleTangentRuntime);reject(scene::createTriangleTangentComparison);reject(scene::createSrgbComparison);reject(scene::createPbrComparison);reject(scene::createOrmComparison);
   scene.prepareGpuTriangleTangents(.625f);scene.draw(vp,4,.625f);long built=t(scene).getLong("dispatches"),uploads=t(scene).getLong("dispatches");int queries=GLES30.getCalls;
   for(int i=0;i<3;i++)scene.draw(vp,4,.625f);
   ok(t(scene).getLong("dispatches")==built&&t(scene).getLong("dispatches")==uploads,"four groups one build/upload");ok(GLES30.getCalls==queries,"unchanged runtime groups do not query GL");
   ok(GLES30.textureBindings.getOrDefault(0x84c3,0)==0&&GLES30.active==0x84c0,"reserved unit restored before final compositor");
   ok(GLES31.events.subList(0,3).equals(List.of("barrier:32","dispatch","barrier:8")),"WAR before dispatch then texture-fetch visibility");
   ok(GLES31.ssbo.values().stream().allMatch(x->x==0)&&GLES31.image[0]==0&&GLES30.ssbo==0,"image and indexed/generic SSBO state restored");
   scene.prepareGpuTriangleTangents(.625f);ok(t(scene).getLong("dispatches")==built&&t(scene).getLong("reuses")==1,"same displayed pose reuses compute output");
   float[] face=new float[52];face[25]=.65f;face[9]=.8f;applied(scene,face,new float[]{8,11,2});
   ok(worker(scene).status().leasedCount==0,"real lease released before draw");
   scene.prepareGpuTriangleTangents(.625f);
   for(int count:new int[]{1,4,1}){
    int start=GLES30.draws.size();scene.drawGpuTriangleTangentRuntimeComparison(vp,count,.625f,false);int mid=GLES30.draws.size();scene.drawGpuTriangleTangentRuntimeComparison(vp,count,.625f,true);
    ok(mid-start==7&&GLES30.draws.size()-mid==7,"same seven entries");
    for(int i=0;i<7;i++){var a=GLES30.draws.get(start+i);var b=GLES30.draws.get(mid+i);ok((a.program()!=b.program())==(i==0),"only primary shader changed");ok(a.attributes().equals(b.attributes())&&a.indexBuffer()==b.indexBuffer()&&a.count()==b.count()&&a.byteOffset()==b.byteOffset(),"same actual PN/UV/color/IBO and draw range");ok(Arrays.equals(a.world(),b.world())&&Arrays.equals(a.normal(),b.normal())&&Arrays.equals(a.vp(),b.vp()),"same uploaded world and view matrices");}
    TriangleTangentSceneTest.oracle(GLES30.draws.get(mid),GLES30.textureFloats.get(t(scene).getInt("table_texture")));
   }
   ok(t(scene).getLong("diagnostic_reference_binding_checks")==3&&t(scene).getLong("diagnostic_candidate_binding_checks")==3,"actual program/uniform/texture diagnostic checks");
   long revision=t(scene).getLong("uploaded_revision"),copies=t(scene).getLong("owned_pn_snapshot_bytes");int last=GLES30.draws.size()-7;float[] retainedGpu=GLES30.vertexData.get(GLES30.draws.get(last).attributes().get(0)).clone();
   for(float value:new float[]{.1f,.9f,.3f,.7f})unapplied(scene,value);
   ok(t(scene).getLong("uploaded_revision")==revision&&t(scene).getLong("owned_pn_snapshot_bytes")==copies,"producer slot reuse cannot change uploaded snapshot");
   int at=GLES30.draws.size();scene.prepareGpuTriangleTangents(.72f);scene.draw(vp,4,.72f);TriangleTangentSceneTest.oracle(GLES30.draws.get(at),GLES30.textureFloats.get(t(scene).getInt("table_texture")));
   ok(Arrays.equals(retainedGpu,GLES30.vertexData.get(GLES30.draws.get(at).attributes().get(0))),"producer work does not mutate VBO");
   applied(scene,face,new float[]{8,11,2});revision=t(scene).getLong("uploaded_revision");copies=t(scene).getLong("owned_pn_snapshot_bytes");scene.prepareGpuTriangleTangents(.625f);scene.draw(vp,4,.625f);built=t(scene).getLong("dispatches");
   applied(scene,face,new float[]{16,21,4});ok(t(scene).getLong("uploaded_revision")==revision&&t(scene).getLong("owned_pn_snapshot_bytes")==copies,"head-only update keeps PN revision/copy unchanged");scene.prepareGpuTriangleTangents(.625f);scene.draw(vp,4,.625f);ok(t(scene).getLong("dispatches")==built+1,"world change rebuilds despite equal PN revision");
   final AvatarGpuScene owned=scene;Throwable[] failure=new Throwable[1];Thread wrong=new Thread(()->{try{owned.draw(vp,4,.625f);}catch(Throwable e){failure[0]=e;}});wrong.start();wrong.join();ok(failure[0] instanceof IllegalStateException,"wrong owner rejected");
  }finally{stop(scene);}ok(GLES30.buffers.isEmpty()&&GLES30.textures.isEmpty()&&GLES30.programs.isEmpty(),"all context-owned resources deleted");
  for(boolean pnFailure:new boolean[]{false,true}){
   GLES30.reset();scene=AvatarGpuScene.fromAsset(asset,manifest,SrgbViewPolicy.GERALT,true,true,AvatarGpuScene.DrawMode.INDIVIDUAL);final AvatarGpuScene s=scene;
   try{s.enableGpuTriangleTangentRuntime();s.prepareGpuTriangleTangents(.625f);s.draw(vp,4,.625f);long revision=t(s).getLong("uploaded_revision");int before=GLES30.draws.size();
    if(pnFailure){unapplied(s,.8f);GLES30.failPnUpload=true;reject(()->s.prepare(new float[52],new float[3]));GLES30.failPnUpload=false;ok(t(s).getLong("uploaded_revision")==revision,"failed PN upload never commits snapshot revision");ok(worker(s).status().leasedCount==0,"failed PN upload still releases lease");}
    else{GLES31.failDispatch=true;reject(()->s.prepareGpuTriangleTangents(.8f));GLES31.failDispatch=false;}
    reject(()->s.draw(vp,4,.625f));ok(GLES30.draws.size()==before&&t(s).getString("actual").equals("failed"),"failure latched; no stale candidate/fallback draw");
   }finally{stop(s);}
  }
  for(int kind=0;kind<4;kind++){
   GLES30.reset();final AvatarGpuScene s=AvatarGpuScene.fromAsset(asset,manifest,SrgbViewPolicy.GERALT,true,true,AvatarGpuScene.DrawMode.INDIVIDUAL);int programs=GLES30.programs.size(),maps=GLES30.textures.size();int[] sentinel=new int[1];
   try{
    if(kind==0)GLES30.failCompile=GLES30.compileCalls+3;
    if(kind==1)GLES30.failStorage=true;
    if(kind==2)GLES30.glBindSampler(3,91);
    if(kind==3){GLES30.glGenTextures(1,sentinel,0);GLES30.glActiveTexture(0x84c3);GLES30.glBindTexture(0x0de1,sentinel[0]);GLES30.glActiveTexture(0x84c0);maps++;}
    reject(s::enableGpuTriangleTangentRuntime);ok(GLES30.programs.size()==programs&&GLES30.textures.size()==maps,"failed enable deletes only candidate ownership");ok(GLES30.active==0x84c0,"partial construction restores active unit");
    if(kind==2)ok(GLES30.samplers.get(3)==91,"reserved sampler conflict preserved");if(kind==3)ok(GLES30.textureBindings.get(0x84c3)==sentinel[0],"reserved texture conflict preserved");
   }finally{GLES30.failCompile=0;GLES30.failStorage=false;GLES30.glBindSampler(3,0);if(sentinel[0]!=0){GLES30.glActiveTexture(0x84c3);GLES30.glBindTexture(0x0de1,0);GLES30.glActiveTexture(0x84c0);GLES30.glDeleteTextures(1,sentinel,0);}stop(s);}
  }
  for(int kind=0;kind<5;kind++){
   GLES30.reset();final AvatarGpuScene s=AvatarGpuScene.fromAsset(asset,manifest,SrgbViewPolicy.GERALT,true,true,AvatarGpuScene.DrawMode.INDIVIDUAL);
   try{
    s.enableGpuTriangleTangentRuntime();s.prepareGpuTriangleTangents(.625f);s.draw(vp,4,.625f);long revision=t(s).getLong("prepared_revision"),dispatched=t(s).getLong("dispatches");int before=GLES30.draws.size(),program=GLES30.current;int[] reserved=new int[1];
    if(kind==0){GLES31.failBarrier=GLES31.barriers+2;reject(()->s.prepareGpuTriangleTangents(.7f));GLES31.failBarrier=0;ok(t(s).getLong("dispatches")==dispatched&&t(s).getLong("prepared_revision")==revision,"failed visibility never commits cache key");ok(GLES31.ssbo.values().stream().allMatch(x->x==0)&&GLES31.image[0]==0&&GLES30.current==program,"failed dispatch restores program/SSBO/image");}
    if(kind==1){GLES30.glGenBuffers(1,reserved,0);GLES31.glBindBufferBase(0x90d2,1,reserved[0]);reject(()->s.prepareGpuTriangleTangents(.7f));ok(GLES31.ssbo.get(1)==reserved[0],"foreign indexed SSBO not overwritten");GLES31.glBindBufferBase(0x90d2,1,0);GLES30.glDeleteBuffers(1,reserved,0);}
    if(kind==2){GLES30.glGenTextures(1,reserved,0);GLES31.glBindImageTexture(0,reserved[0],0,false,0,0x88b8,0x8814);reject(()->s.prepareGpuTriangleTangents(.7f));ok(GLES31.image[0]==reserved[0],"foreign image binding not overwritten");GLES31.glBindImageTexture(0,0,0,false,0,0x88b8,0x8229);GLES30.glDeleteTextures(1,reserved,0);}
    if(kind==3){unapplied(s,.6f);s.prepare(new float[52],new float[3]);reject(()->s.draw(vp,4,.625f));ok(GLES30.draws.size()==before,"new PN cannot draw old table without prepass");}
    if(kind==4){GLES30.glGenBuffers(1,reserved,0);GLES30.glBindBuffer(0x90d2,reserved[0]);s.prepareGpuTriangleTangents(.7f);ok(GLES30.ssbo==reserved[0]&&GLES30.current==program,"successful dispatch restores arbitrary generic SSBO and program");GLES30.glBindBuffer(0x90d2,0);GLES30.glDeleteBuffers(1,reserved,0);}
    if(kind<4){reject(()->s.draw(vp,4,.625f));ok(GLES30.draws.size()==before,"failed owner cannot draw stale table");}
   }finally{stop(s);}
  }
  for(int kind=0;kind<4;kind++){
   GLES30.reset();final AvatarGpuScene s=AvatarGpuScene.fromAsset(asset,manifest,kind==1?"0".repeat(64):SrgbViewPolicy.GERALT,true,kind!=0,kind==2?AvatarGpuScene.DrawMode.BATCHED:AvatarGpuScene.DrawMode.INDIVIDUAL,false,kind==3);
   try{int programs=GLES30.programs.size(),maps=GLES30.textures.size();reject(s::enableGpuTriangleTangentRuntime);ok(GLES30.programs.size()==programs&&GLES30.textures.size()==maps,"invalid backend/model/material rejects before candidate allocation");}finally{stop(s);}
  }
  GLES30.reset();scene=AvatarGpuScene.fromAsset(asset,manifest,SrgbViewPolicy.GERALT,true,true,AvatarGpuScene.DrawMode.INDIVIDUAL);
  try{
   scene.draw(vp,4,.625f);int before=GLES30.draws.size(),programs=GLES30.programs.size(),textures=GLES30.textures.size();
   scene.enablePrimaryUnlitAblation();scene.draw(vp,4,.625f);
   ok(GLES30.programs.size()==programs&&GLES30.textures.size()==textures&&GLES31.dispatches==0,"unlit ablation creates no shader/texture/compute work");
   for(int i=0;i<7;i++){
    var a=GLES30.draws.get(i);var b=GLES30.draws.get(before+i);String uniform=a.program()+":uUnlit";
    ok(a.program()==b.program()&&a.attributes().equals(b.attributes())&&a.indexBuffer()==b.indexBuffer()&&a.count()==b.count(),"ablation original geometry/program/order");
    ok(i==0?b.scalars().get(uniform)==1f:Objects.equals(a.scalars().get(uniform),b.scalars().get(uniform)),"only primary unlit uniform changed");
   }
   var status=scene.status().getJSONObject("primary_unlit_ablation");ok(status.getBoolean("diagnostic_only")&&status.getLong("primary_draws")==1,"unlit diagnostic honest counter/scope");
  }finally{stop(scene);}
  System.out.println("GpuTriangleTangentSceneTest: "+checks+" checks; actual asynchronous producer/lease with GL boundary fake");
 }
}
