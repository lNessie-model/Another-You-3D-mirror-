package com.mirror.bench;
import java.nio.ByteBuffer;
import java.util.HashSet;
public final class AvatarPbrFastMathCheckContractTest {
 private static int checks;
 private static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
 private static ByteBuffer pixels(){ByteBuffer b=ByteBuffer.allocate(400);for(int i=0;i<100;i++)b.put(new byte[]{64,96,112,(byte)255});return b;}
 private static boolean accepted(ByteBuffer a,ByteBuffer b){return AvatarPbrFastMathCheck.pixelsMatch(AvatarPixelComparison.compare(a,b,10,10));}
 public static void main(String[]args)throws Exception{
  ByteBuffer a=pixels(),b=pixels();check(accepted(a,b));
  b.put(0,(byte)65);check(accepted(a,b));
  b.put(0,(byte)66);check(!accepted(a,b));
  b=pixels();for(int i=0;i<4;i++)b.put(i*4,(byte)65);check(!accepted(a,b));
  b=pixels();b.put(3,(byte)254);check(!accepted(a,b));
  b=pixels();b.put(0,(byte)64);check(accepted(a,b));
  var poses=AvatarPbrFastMathCheck.fixtures();check(poses.size()==9);HashSet<String> names=new HashSet<>();for(var pose:poses)check(names.add(pose.name()));
  for(String name:new String[]{"neutral","source-eyeBlinkLeft","source-eyeBlinkRight","source-jawOpen","jaw-mouth-close-corrective","head-yaw-left","head-yaw-right","smile-blink-tilt","all-controls"})check(names.contains(name));
  var p=AvatarPbrFastMathCheck.profile();check(p.getInt("expected_fixtures")==9);check(p.getInt("expected_prepare_calls")==9);check(p.getInt("expected_layer_comparisons")==288);
  check(p.getInt("views")==16);check(p.getInt("view_width")==400);check(p.getInt("view_height")==640);check(p.getInt("max_rgb_error_allowed")==1);check(p.getDouble("rmse_allowed")==.1);
  check(!p.getBoolean("performance_evidence"));check(!p.getBoolean("optical_validation"));check(!p.getBoolean("artwork_validated"));
  check(p.getString("model_sha256").equals("9381f452c53098314f97e1a1799ec55d2be37878e66bf205c7a26958afcef531"));
  check(p.getString("manifest_sha256").equals("b4d324f64ea3655fe01615ce97081c275d60d50312fd0d2b066f8a78e668032e"));
  System.out.println("AvatarPbrFastMathCheckContractTest: "+checks+" checks passed; numerical limits and fixture identity only, no GPU evidence");
 }
}
