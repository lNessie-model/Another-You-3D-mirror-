package com.mirror.bench;

import java.nio.ByteBuffer;

/** Exact acceptance gate, exercised independently of GL. A one-byte difference must fail. */
public final class AvatarOrmRg8CheckTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        var profile=AvatarOrmRg8Check.profile();
        check(profile.getInt("expected_layer_comparisons")==69*16*2,"bounded complete coverage");
        check(profile.getInt("expected_prepare_calls")==69,"one pose shared by both formats and modes");
        check(profile.getInt("max_rgb_error_allowed")==0&&profile.getInt("rmse_allowed")==0&&profile.getInt("alpha_error_allowed")==0,"strict preset thresholds");
        check(!profile.getBoolean("performance_evidence")&&!profile.getBoolean("optical_validation"),"diagnostic scope");
        ByteBuffer a=ByteBuffer.allocateDirect(16),b=ByteBuffer.allocateDirect(16);
        for(int i=0;i<16;i++){a.put(i,(byte)(i%4==3?255:100));b.put(i,a.get(i));}
        check(AvatarOrmRg8Check.pixelsMatch(AvatarPixelComparison.compare(a,b,2,2)),"identical foreground passes");
        b.put(0,(byte)101);check(!AvatarOrmRg8Check.pixelsMatch(AvatarPixelComparison.compare(a,b,2,2)),"one RGB level fails");
        b.put(0,a.get(0));b.put(3,(byte)254);check(!AvatarOrmRg8Check.pixelsMatch(AvatarPixelComparison.compare(a,b,2,2)),"alpha difference fails");
        for(int i=0;i<4;i++){a.put(i*4,(byte)9);a.put(i*4+1,(byte)11);a.put(i*4+2,(byte)15);a.put(i*4+3,(byte)255);for(int c=0;c<4;c++)b.put(i*4+c,a.get(i*4+c));}
        check(!AvatarOrmRg8Check.pixelsMatch(AvatarPixelComparison.compare(a,b,2,2)),"empty same clear fails");
        check(AvatarPoseFixtures.regression().size()==69,"uses complete existing poses");
        System.out.println("AvatarOrmRg8CheckTest: "+checks+" acceptance checks; device rendering pending");
    }
    private static void check(boolean good,String message){checks++;if(!good)throw new AssertionError(message);}
}
