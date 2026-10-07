package com.mirror.bench;
import android.os.Bundle;
import java.nio.ByteBuffer;
public final class TriangleTangentContractTest {
 static int checks;static void ok(boolean b,String s){if(!b)throw new AssertionError(s);checks++;}
 static void reject(Runnable r){try{r.run();throw new AssertionError("accepted");}catch(IllegalArgumentException|IllegalStateException expected){checks++;}}
 public static void main(String[] args){
  ok(!TriangleTangentCheckActivity.parseFull(null,true),"default smoke only");reject(()->TriangleTangentCheckActivity.parseFull(null,false));
  Bundle b=new Bundle();b.putBoolean("full",true);ok(TriangleTangentCheckActivity.parseFull(b,true),"explicit full");reject(()->TriangleTangentCheckActivity.parseFull(b,false));
  b.putBoolean("full",false);ok(!TriangleTangentCheckActivity.parseFull(b,true),"explicit smoke");
  b.putString("full","true");reject(()->TriangleTangentCheckActivity.parseFull(b,true));b.putString("full",null);reject(()->TriangleTangentCheckActivity.parseFull(b,true));
  ByteBuffer ref=ByteBuffer.allocateDirect(400),candidate=ByteBuffer.allocateDirect(400);for(int i=3;i<400;i+=4){ref.put(i,(byte)255);candidate.put(i,(byte)255);}
  ok(TriangleTangentCheck.numericPass(AvatarPixelComparison.compare(ref,candidate,10,10)),"exact RGBA accepted");
  candidate.put(0,(byte)1);ok(TriangleTangentCheck.numericPass(AvatarPixelComparison.compare(ref,candidate,10,10)),"one RGB step sparse accepted");
  for(int i=0;i<400;i+=4)candidate.put(i,(byte)1);ok(!TriangleTangentCheck.numericPass(AvatarPixelComparison.compare(ref,candidate,10,10)),"RMSE .1 still enforced despite max1");
  candidate.put(0,(byte)2);ok(!TriangleTangentCheck.numericPass(AvatarPixelComparison.compare(ref,candidate,10,10)),"max2 rejected");candidate.put(0,(byte)0);candidate.put(3,(byte)0);ok(!TriangleTangentCheck.numericPass(AvatarPixelComparison.compare(ref,candidate,10,10)),"alpha mismatch rejected");
  ok(AvatarPoseFixtures.headSmoke().size()==9&&AvatarPoseFixtures.regression().size()==69,"frozen fixture sizes");
  System.out.println("TriangleTangentContractTest: "+checks+" checks");
 }
}
