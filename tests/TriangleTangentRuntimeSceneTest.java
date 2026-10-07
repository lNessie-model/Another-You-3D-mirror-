package com.mirror.bench;
import android.opengl.GLES30;
import java.nio.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.JSONObject;

/** Real Scene/real bounded producer. GL fake checks upload bytes and API state, not driver pixels. */
public final class TriangleTangentRuntimeSceneTest {
 static int checks;
 static void ok(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 interface Run{void run()throws Exception;}
 static void reject(Run r)throws Exception{try{r.run();throw new AssertionError("unexpected success");}catch(IllegalArgumentException|IllegalStateException expected){checks++;}}
 static JSONObject t(AvatarGpuScene s)throws Exception{return s.status().getJSONObject("triangle_tangent");}
 static AvatarPoseWorker worker(AvatarGpuScene s)throws Exception{Field f=AvatarGpuScene.class.getDeclaredField("poseWorker");f.setAccessible(true);return (AvatarPoseWorker)f.get(s);}
 static void applied(AvatarGpuScene s,float[] values,float[] angles)throws Exception{
  s.resumeCpuPose();long first=worker(s).status().submittedInputs+1,deadline=System.nanoTime()+3_000_000_000L;
  do{s.prepare(values,angles);if(s.status().getLong("applied_pose_input_id")>=first)return;Thread.sleep(4);}while(System.nanoTime()<deadline);
  throw new AssertionError("actual worker did not apply requested pose");
 }
 static void unapplied(AvatarGpuScene s,float value)throws Exception{
  var before=worker(s).status();float[] v=new float[52];v[25]=value;long id=s.triangleTangentDiagnosticSubmitUnapplied(v,new float[]{value*15,value*12,0});long deadline=System.nanoTime()+3_000_000_000L;
  do{var q=worker(s).status();if(q.submittedInputs==id&&q.publishedFrames>before.publishedFrames&&q.pendingCount==0&&q.writingCount==0&&q.readyCount==1&&q.leasedCount==0)return;Thread.sleep(4);}while(System.nanoTime()<deadline);
  throw new AssertionError("unapplied producer frame did not complete");
 }
 static void stop(AvatarGpuScene scene)throws Exception{AvatarPoseWorker w=worker(scene);scene.dispose();if(w!=null){long deadline=System.nanoTime()+3_000_000_000L;while(!w.isTerminated()&&System.nanoTime()<deadline)Thread.sleep(4);ok(w.isTerminated()&&w.status().leasedCount==0,"worker closed without leaked lease");}}
 public static void main(String[] args)throws Exception{
  var asset=AvatarGlbLoader.load(Files.readAllBytes(Path.of(args[0])));String manifest=Files.readString(Path.of(args[1]));float[] vp=new float[64];for(int i=0;i<64;i++)vp[i]=i%17==0?1:i*.001f;
  GLES30.reset();var scene=AvatarGpuScene.fromAsset(asset,manifest,SrgbViewPolicy.GERALT,true,true,AvatarGpuScene.DrawMode.INDIVIDUAL);
  try{
   ok(!t(scene).getBoolean("requested")&&t(scene).getString("actual").equals("disabled"),"default OFF with real worker");int buffers=GLES30.buffers.size(),textures=GLES30.textures.size();scene.enableTriangleTangentRuntime();
   ok(GLES30.buffers.size()==buffers&&GLES30.textures.size()==textures+1,"fixed one table, borrowed geometry");ok(scene.status().getString("pose_backend").equals("bounded_cpu_worker"),"async retained");
   ok(t(scene).getLong("snapshot_copies")==1&&t(scene).getString("actual").equals("ready_unsubmitted"),"initial uploaded neutral captured");
   reject(scene::enableTriangleTangentRuntime);reject(scene::createTriangleTangentComparison);reject(scene::createSrgbComparison);reject(scene::createPbrComparison);reject(scene::createOrmComparison);
   scene.draw(vp,4,.625f);long built=t(scene).getLong("table_builds"),uploads=t(scene).getLong("table_uploads");int queries=GLES30.getCalls;
   for(int i=0;i<3;i++)scene.draw(vp,4,.625f);
   ok(t(scene).getLong("table_builds")==built&&t(scene).getLong("table_uploads")==uploads,"four groups one build/upload");ok(GLES30.getCalls==queries,"unchanged runtime groups do not query GL");
   ok(GLES30.textureBindings.getOrDefault(0x84c3,0)==0&&GLES30.active==0x84c0,"reserved unit restored before final compositor");
   float[] face=new float[52];face[25]=.65f;face[9]=.8f;applied(scene,face,new float[]{8,11,2});
   ok(worker(scene).status().leasedCount==0,"real lease released before draw");
   for(int count:new int[]{1,4,1}){
    int start=GLES30.draws.size();scene.drawTriangleTangentRuntimeComparison(vp,count,.625f,false);int mid=GLES30.draws.size();scene.drawTriangleTangentRuntimeComparison(vp,count,.625f,true);
    ok(mid-start==7&&GLES30.draws.size()-mid==7,"same seven entries");
    for(int i=0;i<7;i++){var a=GLES30.draws.get(start+i);var b=GLES30.draws.get(mid+i);ok((a.program()!=b.program())==(i==0),"only primary shader changed");ok(a.attributes().equals(b.attributes())&&a.indexBuffer()==b.indexBuffer()&&a.count()==b.count()&&a.byteOffset()==b.byteOffset(),"same actual PN/UV/color/IBO and draw range");ok(Arrays.equals(a.world(),b.world())&&Arrays.equals(a.normal(),b.normal())&&Arrays.equals(a.vp(),b.vp()),"same uploaded world and view matrices");}
    TriangleTangentSceneTest.oracle(GLES30.draws.get(mid),GLES30.textureFloats.get(t(scene).getInt("table_texture")));
   }
   ok(t(scene).getLong("diagnostic_reference_binding_checks")==3&&t(scene).getLong("diagnostic_candidate_binding_checks")==3,"actual program/uniform/texture diagnostic checks");
   long revision=t(scene).getLong("primary_snapshot_revision"),copies=t(scene).getLong("snapshot_copies");int last=GLES30.draws.size()-7;float[] retainedGpu=GLES30.vertexData.get(GLES30.draws.get(last).attributes().get(0)).clone();
   for(float value:new float[]{.1f,.9f,.3f,.7f})unapplied(scene,value);
   ok(t(scene).getLong("primary_snapshot_revision")==revision&&t(scene).getLong("snapshot_copies")==copies,"producer slot reuse cannot change uploaded snapshot");
   int at=GLES30.draws.size();scene.draw(vp,4,.72f);TriangleTangentSceneTest.oracle(GLES30.draws.get(at),GLES30.textureFloats.get(t(scene).getInt("table_texture")));
   ok(Arrays.equals(retainedGpu,GLES30.vertexData.get(GLES30.draws.get(at).attributes().get(0))),"producer work does not mutate VBO");
   applied(scene,face,new float[]{8,11,2});revision=t(scene).getLong("primary_snapshot_revision");copies=t(scene).getLong("snapshot_copies");scene.draw(vp,4,.625f);built=t(scene).getLong("table_builds");
   applied(scene,face,new float[]{16,21,4});ok(t(scene).getLong("primary_snapshot_revision")==revision&&t(scene).getLong("snapshot_copies")==copies,"head-only update keeps PN revision/copy unchanged");scene.draw(vp,4,.625f);ok(t(scene).getLong("table_builds")==built+1,"world change rebuilds despite equal PN revision");
   final AvatarGpuScene owned=scene;Throwable[] failure=new Throwable[1];Thread wrong=new Thread(()->{try{owned.draw(vp,4,.625f);}catch(Throwable e){failure[0]=e;}});wrong.start();wrong.join();ok(failure[0] instanceof IllegalStateException,"wrong owner rejected");
  }finally{stop(scene);}ok(GLES30.buffers.isEmpty()&&GLES30.textures.isEmpty()&&GLES30.programs.isEmpty(),"all context-owned resources deleted");
  for(boolean pnFailure:new boolean[]{false,true}){
   GLES30.reset();scene=AvatarGpuScene.fromAsset(asset,manifest,SrgbViewPolicy.GERALT,true,true,AvatarGpuScene.DrawMode.INDIVIDUAL);final AvatarGpuScene s=scene;
   try{s.enableTriangleTangentRuntime();s.draw(vp,4,.625f);long revision=t(s).getLong("primary_snapshot_revision");int before=GLES30.draws.size();
    if(pnFailure){unapplied(s,.8f);GLES30.failPnUpload=true;reject(()->s.prepare(new float[52],new float[3]));GLES30.failPnUpload=false;ok(t(s).getLong("primary_snapshot_revision")==revision,"failed PN upload never commits snapshot revision");ok(worker(s).status().leasedCount==0,"failed PN upload still releases lease");}
    else{GLES30.failUpload=true;reject(()->s.draw(vp,4,.8f));GLES30.failUpload=false;}
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
    reject(s::enableTriangleTangentRuntime);ok(GLES30.programs.size()==programs&&GLES30.textures.size()==maps,"failed enable deletes only candidate ownership");ok(GLES30.active==0x84c0,"partial construction restores active unit");
    if(kind==2)ok(GLES30.samplers.get(3)==91,"reserved sampler conflict preserved");if(kind==3)ok(GLES30.textureBindings.get(0x84c3)==sentinel[0],"reserved texture conflict preserved");
   }finally{GLES30.failCompile=0;GLES30.failStorage=false;GLES30.glBindSampler(3,0);if(sentinel[0]!=0){GLES30.glActiveTexture(0x84c3);GLES30.glBindTexture(0x0de1,0);GLES30.glActiveTexture(0x84c0);GLES30.glDeleteTextures(1,sentinel,0);}stop(s);}
  }
  for(int kind=0;kind<4;kind++){
   GLES30.reset();final AvatarGpuScene s=AvatarGpuScene.fromAsset(asset,manifest,kind==1?"0".repeat(64):SrgbViewPolicy.GERALT,true,kind!=0,kind==2?AvatarGpuScene.DrawMode.BATCHED:AvatarGpuScene.DrawMode.INDIVIDUAL,false,kind==3);
   try{int programs=GLES30.programs.size(),maps=GLES30.textures.size();reject(s::enableTriangleTangentRuntime);ok(GLES30.programs.size()==programs&&GLES30.textures.size()==maps,"invalid backend/model/material rejects before candidate allocation");}finally{stop(s);}
  }
  System.out.println("TriangleTangentRuntimeSceneTest: "+checks+" checks; actual asynchronous producer/lease with GL boundary fake");
 }
}
