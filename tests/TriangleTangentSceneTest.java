package com.mirror.bench;
import android.opengl.GLES30;
import java.nio.file.*;
import java.util.*;
public final class TriangleTangentSceneTest {
 static int checks;
 static void ok(boolean b,String s){if(!b)throw new AssertionError(s);checks++;}
 interface Run{void run()throws Exception;}static void reject(Run r)throws Exception{try{r.run();throw new AssertionError("accepted");}catch(IllegalStateException|IllegalArgumentException expected){checks++;}}
 public static void main(String[] args)throws Exception{
  var asset=AvatarGlbLoader.load(Files.readAllBytes(Path.of(args[0])));String manifest=Files.readString(Path.of(args[1]));
  GLES30.reset();var scene=AvatarGpuScene.fromAsset(asset,manifest,SrgbViewPolicy.GERALT,true,false,AvatarGpuScene.DrawMode.INDIVIDUAL);
  int buffers=GLES30.buffers.size(),textures=GLES30.textures.size();var comparison=scene.createTriangleTangentComparison();
  ok(buffers==GLES30.buffers.size()&&textures+1==GLES30.textures.size(),"borrow all geometry; one table");
  reject(scene::createTriangleTangentComparison);reject(scene::createSrgbComparison);reject(scene::createPbrComparison);reject(scene::createOrmComparison);
  int[] sentinel=new int[1];GLES30.glGenTextures(1,sentinel,0);GLES30.glActiveTexture(0x84c3);GLES30.glBindTexture(0x0de1,sentinel[0]);GLES30.glBindSampler(3,77);GLES30.glActiveTexture(0x84c0);
  GLES30.glPixelStorei(0x0cf2,13);GLES30.glPixelStorei(0x0cf3,7);GLES30.glPixelStorei(0x0cf4,5);GLES30.glBindBuffer(0x88ec,88);
  float[] vp=new float[64];for(int j=0;j<64;j++)vp[j]=j%17==0?1:j*.001f;
  for(int count:new int[]{1,4,1}){
   float[] weights=new float[52];weights[25]=.8f;scene.prepare(weights,new float[]{7,19,-4});
   int start=GLES30.draws.size();comparison.draw(vp,count,.625f,false);int middle=GLES30.draws.size();comparison.draw(vp,count,.625f,true);
   ok(middle-start==7&&GLES30.draws.size()-middle==7,"same seven entries");
   for(int i=0;i<7;i++){
    var a=GLES30.draws.get(start+i);var b=GLES30.draws.get(middle+i);
    ok((a.program()!=b.program())==(i==0),"only primary shader different");
    ok(a.indexBuffer()==b.indexBuffer()&&a.attributes().equals(b.attributes()),"same actual PN/color/UV/IBO");
    ok(a.count()==b.count()&&a.byteOffset()==b.byteOffset(),"same triangle range");
    ok(Arrays.equals(a.vp(),b.vp())&&Arrays.equals(a.world(),b.world())&&Arrays.equals(a.normal(),b.normal()),"same matrices");
   }
   ok(GLES30.textureBindings.get(0x84c3)==sentinel[0]&&GLES30.samplers.get(3)==77,"unit3 texture/sampler restored");
   ok(GLES30.unpack==88&&GLES30.pixelStore.get(0x0cf2)==13&&GLES30.pixelStore.get(0x0cf3)==7&&GLES30.pixelStore.get(0x0cf4)==5,"unpack state restored");
   oracle(GLES30.draws.get(middle),GLES30.textureFloats.get(comparison.status().getInt("table_texture")));
  }
  long built=comparison.status().getLong("table_builds");comparison.draw(vp,4,.625f,true);ok(comparison.status().getLong("table_builds")==built,"same displayed pose/world table reused");
  comparison.draw(vp,4,.75f,true);ok(comparison.status().getLong("table_builds")==built+1,"physical aspect fit invalidates");
  GLES30.failDraw=GLES30.draws.size()+1;reject(()->comparison.draw(vp,4,.625f,true));GLES30.failDraw=0;
  comparison.draw(vp,4,.625f,false);ok(!GLES30.linkedSources.get(GLES30.current).contains("uTriangleTangent"),"failure restores reference selection");
  scene.prepare(new float[52],new float[]{14,2,1});long up=comparison.status().getLong("table_uploads");int before=GLES30.draws.size();GLES30.failUpload=true;
  reject(()->comparison.draw(vp,4,.625f,true));ok(GLES30.draws.size()==before&&comparison.status().getLong("table_uploads")==up,"failed upload cannot draw stale table with new PN");GLES30.failUpload=false;
  comparison.draw(vp,4,.625f,true);ok(comparison.status().getLong("table_uploads")==up+1,"same CPU key retries failed upload before draw");
  GLES30.wrongCurrent=true;reject(()->comparison.draw(vp,4,.625f,true));GLES30.wrongCurrent=false;
  Throwable[] err=new Throwable[1];Thread thread=new Thread(()->{try{comparison.draw(vp,4,.625f,true);}catch(Throwable e){err[0]=e;}});thread.start();thread.join();ok(err[0] instanceof IllegalStateException,"owner guard");
  comparison.close();comparison.close();GLES30.glDeleteTextures(1,sentinel,0);scene.dispose();ok(GLES30.buffers.isEmpty()&&GLES30.textures.isEmpty()&&GLES30.programs.isEmpty(),"all resources released");
  for(int failure=0;failure<2;failure++){
   GLES30.reset();var broken=AvatarGpuScene.fromAsset(asset,manifest,SrgbViewPolicy.GERALT,true,false,AvatarGpuScene.DrawMode.INDIVIDUAL);int programs=GLES30.programs.size(),maps=GLES30.textures.size();
   if(failure==0)GLES30.failCompile=GLES30.compileCalls+3;else GLES30.failStorage=true;
   reject(broken::createTriangleTangentComparison);ok(GLES30.programs.size()==programs&&GLES30.textures.size()==maps,"partial creation cleans only candidate ownership");GLES30.failStorage=false;GLES30.failCompile=0;broken.dispose();
  }
  System.out.println("TriangleTangentSceneTest: "+checks+" checks");
 }
 // Independent affine-edge identity checks on the actual uploaded VBO, original IBO/UV and GL table.
 static void oracle(GLES30.Draw draw,float[] table){
  float[] pn=GLES30.vertexData.get(draw.attributes().get(0)),uv=GLES30.vertexData.get(draw.attributes().get(4)),m=draw.world();int[] ix=GLES30.indexData.get(draw.indexBuffer());
  ok(table.length==512*36*4&&ix.length==9213*3,"real table layout matches real primary IBO");double max=0;
  for(int tri=0;tri<9213;tri++)for(int edge=1;edge<=2;edge++){
   int a=ix[tri*3],b=ix[tri*3+edge];double du=(double)uv[b*2]-uv[a*2],dv=(double)uv[b*2+1]-uv[a*2+1];
   for(int k=0;k<3;k++){
    double actual=table[tri*8+k]*du+table[tri*8+4+k]*dv;
    float pa=((m[k]*pn[a*6]+m[k+4]*pn[a*6+1])+m[k+8]*pn[a*6+2])+m[k+12];
    float pb=((m[k]*pn[b*6]+m[k+4]*pn[b*6+1])+m[k+8]*pn[b*6+2])+m[k+12];
    max=Math.max(max,Math.abs(actual-((double)pb-pa)));
   }
  }
  ok(max<.00003,"uploaded T/B reconstructs both world edges: "+max);
  boolean finite=true,padding=true;for(int i=0;i<table.length;i++){finite&=Float.isFinite(table[i]);if(i>=9213*8||i%4==3)padding&=table[i]==0;}
  ok(finite,"every uploaded component finite");ok(padding,"every unused component and padding texel zero");
 }
}
