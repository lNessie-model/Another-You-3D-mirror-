package com.mirror.bench;

/** Focused A/B checks for the opt-in eye-wide response candidate. */
public final class FacePlaybackCandidateTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void near(float a,float b,String why){check(Math.abs(a-b)<=1e-6f,why+" expected="+a+" actual="+b);}

    public static void main(String[] args){
        float[] source=new float[52],pose={-4,12,7};
        for(int i=0;i<52;i++)source[i]=((i*17)%29)/28f;
        float[] current=new float[52],candidate=new float[52],currentAngles=new float[3],candidateAngles=new float[3];
        FacePlayback.apply(source,pose,false,2.5f,current,currentAngles);
        FacePlayback.applyEyeWideCandidate(source,pose,false,2.5f,candidate,candidateAngles);
        for(int i=0;i<52;i++)if(i!=21&&i!=22)
            near(current[i],candidate[i],"candidate leaves non-eye-wide source unchanged "+i);
        for(int i:new int[]{21,22}){
            check(candidate[i]>=current[i],"candidate never weakens eye-wide response "+i);
            check(candidate[i]<=1,"candidate remains bounded "+i);
        }
        for(int i=0;i<3;i++)near(pose[i],candidateAngles[i],"candidate leaves head angles unchanged");

        // Low MediaPipe eye-wide evidence is exactly the device's observed failure mode.
        source=new float[52];source[21]=.035f;source[22]=.020f;
        FacePlayback.apply(source,new float[3],false,2.5f,current,new float[3]);
        FacePlayback.applyEyeWideCandidate(source,new float[3],false,2.5f,candidate,new float[3]);
        check(candidate[21]>current[21]&&candidate[22]>current[22],"candidate increases weak eye-wide evidence");
        check(candidate[21]<.2f&&candidate[22]<.2f,"candidate does not saturate weak eye-wide evidence");

        // Reflection must remain anatomical: candidate(mirrored source) == mirror(candidate(source)).
        int[] reflect=FaceControlMapper.mirrorPermutation();float[] reflectedSource=new float[52];
        for(int i=0;i<52;i++)reflectedSource[i]=source[reflect[i]];
        float[] mirroredCandidate=new float[52],equivalent=new float[52];
        FacePlayback.applyEyeWideCandidate(source,new float[3],true,2.5f,mirroredCandidate,new float[3]);
        FacePlayback.applyEyeWideCandidate(reflectedSource,new float[3],false,2.5f,equivalent,new float[3]);
        for(int i=0;i<52;i++)near(mirroredCandidate[i],equivalent[i],"candidate preserves mirror permutation "+i);

        // Endpoints and monotonicity are required before considering a live rollout.
        for(int channel:new int[]{21,22}){
            float previous=-1;
            for(int step=0;step<=100;step++){
                source=new float[52];source[channel]=step/100f;
                FacePlayback.applyEyeWideCandidate(source,new float[3],false,1,candidate,new float[3]);
                check(candidate[channel]>=previous&&candidate[channel]>=0&&candidate[channel]<=1,"bounded monotone candidate "+channel+" step "+step);
                previous=candidate[channel];
            }
            near(1,previous,"candidate reaches eye-wide endpoint "+channel);
        }
        System.out.println("FacePlaybackCandidateTest: "+checks+" opt-in eye-wide A/B checks passed; production default remains "+FacePlayback.EYE_WIDE_GAMMA_CURRENT);
    }
}
