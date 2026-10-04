package com.mirror.bench;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

/** Pure fixture/metric tests. These deliberately do not pretend to execute a GLES driver. */
public final class AvatarMultiviewFixturesTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        var poses=AvatarPoseFixtures.regression();boolean[] covered=new boolean[52];Set<String> names=new HashSet<>();
        float[] weights=new float[52],angles=new float[3];int combinations=0;
        for(var pose:poses) {
            check(names.add(pose.name()),"unique pose name");pose.copyWeights(weights);pose.copyAngles(angles);
            int active=0;for(int i=0;i<weights.length;i++){check(Float.isFinite(weights[i])&&weights[i]>=0&&weights[i]<=1,"valid weight");if(weights[i]!=0)active++;}
            for(float v:angles)check(Float.isFinite(v)&&Math.abs(v)<=90,"valid head angle");
            if(pose.sourceIndex()>=0){covered[pose.sourceIndex()]=true;check(active==1&&weights[pose.sourceIndex()]==1,"isolated source fixture");}
            if(active>1)combinations++;
            weights[0]=.75f;pose.copyWeights(weights);check(weights[0]!=.75f,"fixture owns its input");
        }
        for(boolean has:covered)check(has,"all 52 input positions covered");
        check(poses.size()==69,"full regression retains all 69 fixtures");
        var smoke=AvatarPoseFixtures.headSmoke();
        String[] expected={"neutral","source-eyeBlinkLeft","source-eyeBlinkRight","source-jawOpen","gaze-left","head-yaw-left","head-roll-left","head-roll-right","jaw-mouth-close-corrective"};
        check(smoke.size()==expected.length,"bounded head smoke count");
        for(int i=0;i<expected.length;i++) {
            var p=smoke.get(i);check(p.name().equals(expected[i]),"head smoke order "+i);
            String expectedName=expected[i];
            var reference=poses.stream().filter(v->v.name().equals(expectedName)).findFirst().orElseThrow();
            float[] referenceWeights=new float[52],referenceAngles=new float[3];
            p.copyWeights(weights);p.copyAngles(angles);reference.copyWeights(referenceWeights);reference.copyAngles(referenceAngles);
            check(java.util.Arrays.equals(weights,referenceWeights)&&java.util.Arrays.equals(angles,referenceAngles),"smoke preserves frozen pose values");
            check(p.sourceIndex()==reference.sourceIndex(),"smoke preserves isolated source index");
        }
        try{smoke.clear();throw new AssertionError("mutable smoke list");}catch(UnsupportedOperationException expectedFailure){checks++;}
        check(combinations>=6,"gaze/jaw/combinations covered");
        try{poses.clear();throw new AssertionError("mutable pose list");}catch(UnsupportedOperationException expectedFailure){checks++;}
        ByteBuffer a=ByteBuffer.allocateDirect(16),b=ByteBuffer.allocateDirect(16);
        for(int i=0;i<4;i++){a.put(i*4,(byte)9);a.put(i*4+1,(byte)11);a.put(i*4+2,(byte)15);a.put(i*4+3,(byte)255);}
        b.put(a.duplicate());b.clear();
        var empty=AvatarPixelComparison.compare(a,b,2,2);check(empty.rgbMismatches==0&&empty.serialForeground==0&&!empty.passed(),"identical clear-only images fail");
        a.put(0,(byte)100);b.put(0,(byte)100);
        var equal=AvatarPixelComparison.compare(a,b,2,2);check(equal.rgbMismatches==0&&equal.serialForeground==1&&equal.passed(),"identical geometry passes");
        check(equal.serialSha256.equals(equal.candidateSha256),"pixel hashes match");
        b.put(0,(byte)102);var changed=AvatarPixelComparison.compare(a,b,2,2);
        check(changed.rgbMismatches==1&&changed.maxRgbError==2&&!changed.passed(),"RGB mismatch fails");
        b.put(0,(byte)100);b.put(3,(byte)0);var alpha=AvatarPixelComparison.compare(a,b,2,2);
        check(alpha.alphaMismatches==1&&alpha.candidateNonOpaque==1&&!alpha.passed(),"alpha mismatch fails");
        a.put(3,(byte)0);check(!AvatarPixelComparison.compare(a,b,2,2).passed(),"identically nonopaque buffers fail");
        a.position(3);b.position(2);AvatarPixelComparison.compare(a,b,2,2);
        check(a.position()==3&&b.position()==2,"comparison preserves caller cursors");
        invalid(()->AvatarPixelComparison.compare(a,b,0,2));invalid(()->AvatarPixelComparison.compare(a,b,3,2));
        System.out.println("AvatarMultiviewFixturesTest passed: "+checks+" checks; "+poses.size()+" deterministic poses");
    }
    private static void invalid(Runnable work){try{work.run();throw new AssertionError("expected invalid dimensions");}catch(IllegalArgumentException expected){checks++;}}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
}
