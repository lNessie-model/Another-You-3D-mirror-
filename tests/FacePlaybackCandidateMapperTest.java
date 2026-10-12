package com.mirror.bench;

/** Offline checks for the calibrated expression candidate; production FacePlayback remains unchanged. */
public final class FacePlaybackCandidateMapperTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void near(float a,float b,float eps,String why){check(Math.abs(a-b)<=eps,why+" expected="+b+" actual="+a);}

    public static void main(String[] args){
        float[] source=new float[52],pose={2,-5,3},current=new float[52],candidate=new float[52],angles=new float[3];
        // The replay candidate must start from a conservative neutral estimate. A zero baseline
        // turns an ordinary mid-range brow score into a saturated frown before live calibration.
        source[1]=.45f;source[2]=.45f;source[19]=.45f;source[20]=.45f;source[25]=.05f;
        FacePlaybackCandidate.apply(source,pose,false,2.5f,FacePlaybackCandidate.Config.auditDefaults(),candidate,angles);
        check(candidate[1]<.25f&&candidate[2]<.25f,"audit neutral brow does not saturate");
        near(candidate[25],0,1e-6f,"audit neutral jaw is closed");
        check(candidate[19]<.25f&&candidate[20]<.25f,"audit neutral squint does not saturate");

        FacePlaybackCandidate.Config base=FacePlaybackCandidate.Config.auditDefaults()
                .withPersonalBaselines(.50f,.55f,.05f,.50f,.45f);
        source[1]=.50f;source[2]=.55f;source[25]=.05f;source[19]=.50f;source[20]=.45f;
        FacePlaybackCandidate.apply(source,pose,false,2.5f,base,candidate,angles);
        near(candidate[1],0,1e-6f,"brow left baseline is zero");
        near(candidate[2],0,1e-6f,"brow right baseline is zero");
        near(candidate[25],0,1e-6f,"jaw baseline is zero");
        near(candidate[19],0,1e-6f,"eye squint left baseline is zero");
        near(candidate[20],0,1e-6f,"eye squint right baseline is zero");
        for(int i=0;i<3;i++)near(angles[i],pose[i],1e-6f,"candidate preserves head angle "+i);

        // Baselines are sampled in incoming anatomical coordinates, then mirror is applied.
        FacePlaybackCandidate.Config asymmetric=FacePlaybackCandidate.Config.auditDefaults()
                .withPersonalBaselines(.50f,.20f,.05f,.50f,.20f);
        source=new float[52];source[1]=.50f;source[2]=.20f;source[19]=.50f;source[20]=.20f;source[25]=.05f;
        FacePlaybackCandidate.apply(source,pose,true,2.5f,asymmetric,candidate,new float[3]);
        near(candidate[1],0,1e-6f,"mirrored right brow uses right input baseline");
        near(candidate[2],0,1e-6f,"mirrored left brow uses left input baseline");
        near(candidate[19],0,1e-6f,"mirrored right squint uses right input baseline");
        near(candidate[20],0,1e-6f,"mirrored left squint uses left input baseline");

        // Smile gets its own response while press is suppressed by the smile amount.
        source=new float[52];source[44]=.20f;source[45]=.10f;source[36]=.60f;source[37]=.40f;
        FacePlayback.apply(source,pose,false,2.5f,current,new float[3]);
        FacePlaybackCandidate.apply(source,pose,false,2.5f,base,candidate,new float[3]);
        check(candidate[44]>current[44]&&candidate[45]>current[45],"smile candidate increases low-mid smile response");
        check(candidate[36]<current[36]&&candidate[37]<current[37],"smile candidate suppresses mouth press");
        check(candidate[36]>=0&&candidate[36]<=1&&candidate[37]>=0&&candidate[37]<=1,"press remains bounded");

        // Jaw calibration removes closed-mouth aperture and preserves an open range without saturation.
        source=new float[52];source[25]=.05f;
        FacePlaybackCandidate.apply(source,pose,false,2.5f,base,candidate,new float[3]);near(candidate[25],0,1e-6f,"closed jaw is zero after baseline");
        source[25]=.45f;FacePlaybackCandidate.apply(source,pose,false,2.5f,base,candidate,new float[3]);
        near(candidate[25],.82f,1e-5f,"jaw reaches configured cap at span endpoint");
        source[25]=.25f;FacePlaybackCandidate.apply(source,pose,false,2.5f,base,candidate,new float[3]);
        check(candidate[25]>.35f&&candidate[25]<.5f,"partial jaw remains natural and monotone");

        // The eye-wide exponent is configurable only in the candidate API.
        source=new float[52];source[21]=.04f;source[22]=.02f;
        FacePlaybackCandidate.apply(source,pose,false,2.5f,base,candidate,new float[3]);
        float wide35=candidate[21];
        FacePlaybackCandidate.apply(source,pose,false,2.5f,base.withEyeWideGamma(1.65f),candidate,new float[3]);
        check(wide35>candidate[21],"candidate eye-wide gamma is parameterized");

        // Squint is independently calibrated and capped, unlike a global expression gain.
        source=new float[52];source[19]=1;source[20]=1;
        FacePlaybackCandidate.apply(source,pose,false,2.5f,base,candidate,new float[3]);
        near(candidate[19],.8f,1e-6f,"left squint cap");near(candidate[20],.8f,1e-6f,"right squint cap");

        // Horizontal gaze gate is opt-in and leaves blink unchanged when disabled.
        source=new float[52];source[9]=.7f;source[10]=.7f;source[13]=.8f;source[14]=.8f;source[15]=.8f;source[16]=.8f;
        FacePlaybackCandidate.apply(source,pose,false,2.5f,base,candidate,new float[3]);
        float ungated=candidate[9];
        FacePlaybackCandidate.apply(source,pose,false,2.5f,base.withHorizontalGazeBlinkGate(true),candidate,new float[3]);
        check(candidate[9]<ungated&&candidate[10]<ungated,"horizontal gaze gate reduces blink only when enabled");
        source[13]=source[14]=source[15]=source[16]=0;
        FacePlaybackCandidate.apply(source,pose,false,2.5f,base.withHorizontalGazeBlinkGate(true),candidate,new float[3]);
        near(candidate[9],ungated,1e-6f,"gate leaves blink unchanged without horizontal gaze");

        // Reflection is anatomical when personal baselines are symmetric.
        source=new float[52];source[1]=.7f;source[2]=.4f;source[44]=.2f;source[45]=.1f;
        float[] reflected=new float[52],mirrored=new float[52],equivalent=new float[52];int[] r=FaceControlMapper.mirrorPermutation();
        for(int i=0;i<52;i++)reflected[i]=source[r[i]];
        FacePlaybackCandidate.apply(source,pose,true,2.5f,FacePlaybackCandidate.Config.auditDefaults(),mirrored,new float[3]);
        FacePlaybackCandidate.apply(reflected,pose,false,2.5f,FacePlaybackCandidate.Config.auditDefaults(),equivalent,new float[3]);
        for(int i:new int[]{1,2,44,45,25})near(mirrored[i],equivalent[i],1e-6f,"candidate reflection channel "+i);

        // Candidate evaluation cannot mutate or alter the production response policy.
        source=new float[52];for(int i=0;i<52;i++)source[i]=(i%11)/10f;
        float[] productionBefore=new float[52],productionAfter=new float[52];
        FacePlayback.apply(source,pose,true,2.5f,productionBefore,new float[3]);
        FacePlaybackCandidate.apply(source,pose,true,2.5f,base,candidate,new float[3]);
        FacePlayback.apply(source,pose,true,2.5f,productionAfter,new float[3]);
        for(int i=0;i<52;i++)near(productionAfter[i],productionBefore[i],1e-6f,"candidate leaves production policy unchanged "+i);

        // Config validation keeps malformed candidate experiments from reaching a live call.
        boolean rejected=false;try{new FacePlaybackCandidate.Config(0,0,0,1,0,0,1,1,0,0,.4f,1,.8f,0,0,.45f,1,.8f,3.5f,false,.3f,.65f);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"invalid candidate span rejected");
        System.out.println("FacePlaybackCandidateMapperTest: "+checks+" calibrated brow/smile-press/jaw/squint/gaze checks passed; production default untouched");
    }
}
