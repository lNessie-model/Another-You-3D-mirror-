package com.mirror.bench;
import android.os.Bundle;
import java.nio.ByteBuffer;
public final class SrgbDiagnosticContractTest {
 static int n;static void ok(boolean b){n++;if(!b)throw new AssertionError("check "+n);}
 static void rejected(Bundle b,boolean debug,boolean other){try{AvatarPreviewActivity.readSrgbVerification(b,debug,other);throw new AssertionError("accepted");}catch(IllegalArgumentException e){n++;}}
 public static void main(String[] args){
  ok(!AvatarPreviewActivity.readSrgbVerification(null,true,false));ok(!AvatarPreviewActivity.readSrgbVerification(new Bundle(),false,false));
  for(boolean enabled:new boolean[]{false,true}){Bundle b=new Bundle();b.putBoolean("verify_srgb_views",enabled);ok(AvatarPreviewActivity.readSrgbVerification(b,true,false)==enabled);rejected(b,false,false);if(enabled)rejected(b,true,true);}
  Bundle b=new Bundle();b.putString("verify_srgb_views",null);rejected(b,true,false);b.putString("verify_srgb_views","true");rejected(b,true,false);b.putInt("verify_srgb_views",1);rejected(b,true,false);
  b=new Bundle();b.putBoolean("verify_srgb_views",true);b.putInt("test_view_count",20);rejected(b,true,false);b.putInt("test_view_count",16);ok(AvatarPreviewActivity.readSrgbVerification(b,true,false));b.putString("test_view_preset","400x720");rejected(b,true,false);b.putString("test_view_preset","400x640");ok(AvatarPreviewActivity.readSrgbVerification(b,true,false));
  ByteBuffer a=ByteBuffer.allocate(40000),c=ByteBuffer.allocate(40000);ok(AvatarSrgbCheck.numericPass(AvatarPixelComparison.compare(a,c,100,100)));
  c.put(0,(byte)1);ok(AvatarSrgbCheck.numericPass(AvatarPixelComparison.compare(a,c,100,100)));c.put(0,(byte)2);ok(!AvatarSrgbCheck.numericPass(AvatarPixelComparison.compare(a,c,100,100)));
  c.put(0,(byte)0);c.put(3,(byte)1);ok(!AvatarSrgbCheck.numericPass(AvatarPixelComparison.compare(a,c,100,100)));
  c.put(3,(byte)0);for(int i=0;i<c.capacity();i+=4)c.put(i,(byte)1);ok(!AvatarSrgbCheck.numericPass(AvatarPixelComparison.compare(a,c,100,100)));
  var p=PanelCalibration.defaults();String vertex=InterlaceRenderer.runtimeInterlaceVertex(p),fragment=InterlaceRenderer.runtimeInterlaceFragment(p);
  ok(vertex.startsWith("#version 320 es"));ok(fragment.contains("precise")&&fragment.contains("textureLod(uViews")&&fragment.contains("backgroundAt(vUv)"));
  ok(!fragment.contains("encodeSRGB")&&!fragment.contains("pow("));
  System.out.println("SrgbDiagnosticContractTest: "+n+" checks GREEN; explicit flag and fixed numeric rejection policy");
 }
}
