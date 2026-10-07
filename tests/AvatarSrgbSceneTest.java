package com.mirror.bench;
import android.opengl.GLES30;
import java.nio.file.*;
import java.util.*;
public final class AvatarSrgbSceneTest {
 static int checks;
 static void ok(boolean b,String s){if(!b)throw new AssertionError(s);checks++;}
 interface Run{void run()throws Exception;}
 static void reject(Run r)throws Exception{try{r.run();throw new AssertionError("accepted");}catch(IllegalStateException|IllegalArgumentException expected){checks++;}}
 public static void main(String[] args)throws Exception{
  var asset=AvatarGlbLoader.load(Files.readAllBytes(Path.of(args[0])));String manifest=Files.readString(Path.of(args[1]));
  GLES30.reset();var s=AvatarGpuScene.fromAsset(asset,manifest,SrgbViewPolicy.GERALT,true,false,AvatarGpuScene.DrawMode.INDIVIDUAL);
  ok(!s.status().getBoolean("srgb_output_requested"),"default off");
  int buffers=GLES30.buffers.size(),textures=GLES30.textures.size();var comparison=s.createSrgbComparison();
  ok(buffers==GLES30.buffers.size()&&textures==GLES30.textures.size(),"comparison borrows all buffers/textures");
  reject(s::createSrgbComparison);reject(s::createPbrComparison);reject(s::createOrmComparison);
  float[] vp=new float[64];for(int j=0;j<64;j++)vp[j]=(j%17==0)?1:j*.0001f;
  for(int count:new int[]{1,4,1}){
   float[] weights=new float[52];weights[25]=.8f;s.prepare(weights,new float[]{7,19,-4});
   int begin=GLES30.draws.size();comparison.draw(vp,count,.625f,false);int middle=GLES30.draws.size();comparison.draw(vp,count,.625f,true);
   ok(middle-begin==7&&GLES30.draws.size()-middle==7,"same seven real draws");
   for(int i=0;i<7;i++){
    var a=GLES30.draws.get(begin+i);var b=GLES30.draws.get(middle+i);
    ok(a.program()!=b.program(),"distinct linked program");
    ok(a.indexBuffer()==b.indexBuffer()&&a.attributes().equals(b.attributes()),"same actual VBO/IBO binding");
    ok(a.count()==b.count()&&a.byteOffset()==b.byteOffset(),"same triangle order");
    ok(Arrays.equals(a.vp(),b.vp())&&Arrays.equals(a.world(),b.world())&&Arrays.equals(a.normal(),b.normal()),"same matrices");
    ok(GLES30.linkedSources.get(a.program()).contains("encodeSRGB")&&!GLES30.linkedSources.get(b.program()).contains("encodeSRGB"),"actual reference/linear shader selection");
   }
  }
  GLES30.wrongCurrent=true;reject(()->comparison.draw(vp,4,.625f,true));GLES30.wrongCurrent=false;
  Throwable[] err=new Throwable[1];Thread t=new Thread(()->{try{comparison.draw(vp,4,.625f,true);}catch(Throwable e){err[0]=e;}});t.start();t.join();ok(err[0] instanceof IllegalStateException,"owner gate");
  comparison.close();comparison.close();ok(!s.status().getBoolean("srgb_output_selected"),"close restores reference");s.dispose();
  ok(GLES30.buffers.isEmpty()&&GLES30.textures.isEmpty()&&GLES30.programs.isEmpty(),"all owned resources released");
  for(boolean valid:new boolean[]{false,true}){
   GLES30.reset();if(!valid){reject(()->AvatarGpuScene.fromAsset(asset,manifest,"bad",true,false,AvatarGpuScene.DrawMode.INDIVIDUAL,false,false,true));ok(GLES30.programs.isEmpty()&&GLES30.buffers.isEmpty(),"qualification before GL");}
   else{var c=AvatarGpuScene.fromAsset(asset,manifest,SrgbViewPolicy.GERALT,true,false,AvatarGpuScene.DrawMode.INDIVIDUAL,false,false,true);ok(c.status().getBoolean("srgb_output_requested"),"explicit construction linear");ok(c.status().getString("pbr_fragment_source_sha256").equals(AvatarPbrShaderVariant.sha256(SrgbViewPolicy.fragment(AvatarGpuScene.PBR_FRAGMENT,true))),"live status hashes actual linear fragment");reject(c::createSrgbComparison);c.dispose();}
  }
  System.out.println("AvatarSrgbSceneTest: "+checks+" checks; real asset/Scene, GL boundary only");
 }
}
