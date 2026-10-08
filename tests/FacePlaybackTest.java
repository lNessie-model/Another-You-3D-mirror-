package com.mirror.bench;

/** Anatomical reflection and bounded amplification, independent of a camera/UI. */
public final class FacePlaybackTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void near(float a,float b,String why){check(Math.abs(a-b)<1e-6,why);}
    public static void main(String[] args){
        float[] input=new float[52],pose={17,31,-24},out=new float[52],angles=new float[3];
        input[9]=.4f;input[25]=.3f;input[44]=.2f;input[0]=.44f;input[15]=.8f;
        FacePlayback.apply(input,pose,true,3,out,angles);
        check(out[10]>.5f&&out[10]<.6f,"left blink reflects through its independent gradual response");near(out[9],0,"opposite eye stays open");
        near(out[25],.3f,"jaw opening follows capture independently of expression gain");near(out[45],.488f,"one-sided smile reflects and has a bounded response");
        near(out[0],.44f,"neutral classifier is not an expression");near(out[16],.8f,"gaze reflects without expression gain");
        near(angles[0],17,"mirror preserves pitch");near(angles[1],-31,"mirror reverses yaw");near(angles[2],24,"mirror reverses roll");
        input[19]=.6f;input[21]=.3f;input[27]=.4f;input[7]=.5f;
        FacePlayback.apply(input,pose,true,4,out,angles);
        near(out[20],.6f,"squint preserves partial closure");check(out[22]>.4f&&out[22]<.5f,"wide has its own bounded response");
        near(out[27],.4f,"mouth-close corrective input preserves original proportions");near(out[8],.5f,"cheek squint is independent of mouth gain");
        float previous=-1;
        for(float gain:new float[]{.5f,1,1.5f,2.5f,3,4})for(boolean mirror:new boolean[]{false,true}){
            for(int step=0;step<=100;step++){
                input[25]=step/100f;FacePlayback.apply(input,pose,mirror,gain,out,angles);
                near(out[25],input[25],"jaw retains partial openings and both endpoints across gains and reflection");
            }
        }
        for(int step=0;step<=100;step++){
            input[25]=step/100f;FacePlayback.apply(input,pose,false,2.5f,out,angles);
            check(out[25]>=previous&&out[25]<=1,"continuous monotone mouth response");
            if(step<100)check(out[25]<1,"partial mouth input never prematurely saturates");previous=out[25];
        }
        float[] twice=new float[52],twiceAngles=new float[3];
        for(int i=0;i<52;i++)input[i]=i/52f;
        // The response itself is intentionally not an involution. Check reflection
        // algebra on unaffected coefficients; the separate response test covers
        // every nonzero schema channel against an explicit reflected source.
        for(int i:new int[]{1,2,9,10,21,22})input[i]=0;
        FacePlayback.apply(input,pose,true,1,out,angles);FacePlayback.apply(out,angles,true,1,twice,twiceAngles);
        for(int i=0;i<52;i++)near(twice[i],input[i],"two reflections preserve channel "+i);
        for(int i=0;i<3;i++)near(twiceAngles[i],pose[i],"two reflections preserve pose");
        FacePlayback.apply(input,pose,false,1,out,angles);
        for(int i=0;i<52;i++)near(out[i],input[i],"unity mouth strength preserves this unaffected-source fixture");
        // Reflection H R H in 3D is Rz(-roll) Ry(-yaw) Rx(pitch), including combined rotations.
        double[][] original=rotation(pose),mirrored=rotation(new float[]{pose[0],-pose[1],-pose[2]});
        for(int row=0;row<3;row++)for(int col=0;col<3;col++)
            check(Math.abs(mirrored[row][col]-original[row][col]*(row==0?-1:1)*(col==0?-1:1))<1e-10,"physical reflection matrix");
        var setting=SceneViewSettings.DEFAULT.withValue(0,2.34f).withValue(9,3.1f).withMirrorMotion(false).withBackground(8);
        var decoded=SceneViewSettings.fromMap(setting.toMap());
        near(decoded.scale,2.34f,"view settings retained");near(decoded.expressionGain,3.1f,"gain persists");
        check(!decoded.mirrorMotion&&decoded.background==8,"mirror and background persist");
        var old=new java.util.LinkedHashMap<>(setting.toMap());old.remove("expression_gain");old.remove("mirror_motion");
        var migrated=SceneViewSettings.fromMap(old);near(migrated.scale,2.34f,"legacy scale preserved");
        check(migrated.mirrorMotion&&migrated.expressionGain==2.5f,"old settings acquire requested defaults without reset");
        System.out.println("FacePlaybackTest: "+checks+" reflection, gain and additive preferences checks passed");
    }
    private static double[][] rotation(float[] a){
        double x=Math.toRadians(a[0]),y=Math.toRadians(a[1]),z=Math.toRadians(a[2]);
        double cx=Math.cos(x),sx=Math.sin(x),cy=Math.cos(y),sy=Math.sin(y),cz=Math.cos(z),sz=Math.sin(z);
        return new double[][]{{cz*cy,cz*sy*sx-sz*cx,cz*sy*cx+sz*sx},{sz*cy,sz*sy*sx+cz*cx,sz*sy*cx-cz*sx},{-sy,cy*sx,cy*cx}};
    }
}
