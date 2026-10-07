package com.mirror.bench;
import java.nio.*;
import java.util.*;
public final class TriangleTangentTableTest {
 static int checks;
 static void ok(boolean b,String why){if(!b)throw new AssertionError(why);checks++;}
 static void near(float a,float b){ok(Math.abs(a-b)<1e-6,"expected "+b+" got "+a);}
 interface Run{void run();} static void reject(Run r){try{r.run();throw new AssertionError("accepted");}catch(IllegalArgumentException|IllegalStateException expected){checks++;}}
 static float[] identity(){return new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};}
 public static void main(String[] args){
  FloatBuffer uv=FloatBuffer.wrap(new float[]{0,0,1,0,0,1});IntBuffer ix=IntBuffer.wrap(new int[]{0,1,2});
  FloatBuffer pn=FloatBuffer.wrap(new float[]{0,0,0,0,0,1, 2,0,0,0,0,1, 0,3,0,0,0,1});
  var t=new TriangleTangentTable(3,ix,uv);ok(t.width()==512&&t.height()==1&&t.triangles()==1,"bounded layout");
  ok(t.update(pn,identity(),1),"first build");var b=t.packed();near(b.get(0),2);near(b.get(1),0);near(b.get(4),0);near(b.get(5),3);
  ok(!t.update(pn,identity(),1),"same revision/world reused");ok(pn.position()==0&&uv.position()==0&&ix.position()==0,"source positions immutable");
  float[] world=identity();world[0]=2;world[5]=4;world[10]=.5f;world[12]=9;world[13]=-2;
  ok(t.update(pn,world,1),"world key rebuild");near(t.packed().get(0),4);near(t.packed().get(5),12);
  float[] before=new float[t.packed().remaining()];t.packed().get(before);long rev=t.rebuilds();
  pn.put(0,Float.NaN);reject(()->t.update(pn,world,2));float[] after=new float[before.length];t.packed().get(after);
  ok(Arrays.equals(before,after)&&t.rebuilds()==rev,"failed build preserves last committed result");pn.put(0,0);
  var reverse=new TriangleTangentTable(3,IntBuffer.wrap(new int[]{0,2,1}),uv);reverse.update(pn,identity(),1);near(reverse.packed().get(0),2);near(reverse.packed().get(5),3);
  reject(()->new TriangleTangentTable(3,IntBuffer.wrap(new int[]{0,1,3}),uv));
  reject(()->new TriangleTangentTable(3,ix,FloatBuffer.wrap(new float[6])));
  reject(()->t.update(FloatBuffer.wrap(new float[17]),world,3));world[15]=2;reject(()->t.update(pn,world,3));
  int[] many=new int[257*3];for(int i=0;i<many.length;i++)many[i]=i%3;
  var edge=new TriangleTangentTable(3,IntBuffer.wrap(many),uv);edge.update(pn,identity(),1);ok(edge.height()==2,"row crossing");
  near(edge.packed().get(256*8),2);near(edge.packed().get(256*8+5),3);near(edge.packed().get(257*8),0);
  ok(t.packed().isReadOnly(),"published table read only");
  float[] large=identity();large[0]=Float.MAX_VALUE;reject(()->t.update(pn,large,9));
  FloatBuffer offsetPn=FloatBuffer.allocate(20);offsetPn.position(2);offsetPn.put(pn.duplicate());offsetPn.position(2);
  FloatBuffer offsetUv=FloatBuffer.allocate(7);offsetUv.position(1);offsetUv.put(uv.duplicate());offsetUv.position(1);
  var offset=new TriangleTangentTable(3,ix,offsetUv);offset.update(offsetPn,identity(),7);near(offset.packed().get(0),2);near(offset.packed().get(5),3);ok(offsetPn.position()==2&&offsetUv.position()==1,"nonzero input ranges untouched");
  String original=AvatarGpuScene.PBR_FRAGMENT,candidate=TriangleTangentShader.fragment(original);
  ok(candidate.contains("gl_PrimitiveID")&&!candidate.contains("dFdx(vPosition)"),"actual generator replaces position derivatives");
  ok(candidate.contains("highp int tangentTexel")&&candidate.contains("highp ivec2 tangentCoord"),"all table address arithmetic explicit highp");
  ok(candidate.contains("if(abs(det)<1e-10)return n;")&&candidate.contains("if(lengthT<1e-8)return n;"),"both original pixel guards");
  ok(candidate.substring(candidate.indexOf("t-=n*")).equals(original.substring(original.indexOf("t-=n*"))),"normal sampling and all PBR tail exact");
  ok(!original.contains("uTriangleTangent"),"reference immutable");reject(()->TriangleTangentShader.fragment(original.replace("vec3 dp1=dFdx(vPosition)","oops")));
  System.out.println("TriangleTangentTableTest: "+checks+" checks");
 }
}
