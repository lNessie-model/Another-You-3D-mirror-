package com.mirror.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Deterministic real-avatar inputs, independent of live capture, smoothing and diagnostic four-weight meshes. */
public final class AvatarPoseFixtures {
    private AvatarPoseFixtures(){}
    /** Bounded private-head driver smoke test; full 69-pose regression remains separate. */
    public static List<Pose> headSmoke() {
        String[] names={"neutral","source-eyeBlinkLeft","source-eyeBlinkRight","source-jawOpen","gaze-left","head-yaw-left","head-roll-left","head-roll-right","jaw-mouth-close-corrective"};
        List<Pose> full=regression();ArrayList<Pose> selected=new ArrayList<>();
        for(String name:names)selected.add(full.stream().filter(p->p.name().equals(name)).findFirst().orElseThrow());
        return Collections.unmodifiableList(selected);
    }
    public static List<Pose> regression() {
        ArrayList<Pose> poses=new ArrayList<>();poses.add(new Pose("neutral",-1,new float[52],new float[3]));
        for(int i=0;i<52;i++){float[] w=new float[52];w[i]=1;poses.add(new Pose("source-"+BlendshapeSchema.name(i),i,w,new float[3]));}
        poses.add(pose("head-pitch-up",new float[]{45,0,0}));poses.add(pose("head-pitch-down",new float[]{-45,0,0}));
        poses.add(pose("head-yaw-left",new float[]{0,65,0}));poses.add(pose("head-yaw-right",new float[]{0,-65,0}));
        poses.add(pose("head-roll-left",new float[]{0,0,40}));poses.add(pose("head-roll-right",new float[]{0,0,-40}));
        poses.add(pose("gaze-left",new float[3],15,14));poses.add(pose("gaze-right",new float[3],13,16));
        poses.add(pose("gaze-up",new float[3],17,18));poses.add(pose("gaze-down",new float[3],11,12));
        poses.add(pose("jaw-open-left-forward",new float[3],25,24,23));poses.add(pose("jaw-open-right-forward",new float[3],25,26,23));
        poses.add(pose("jaw-mouth-close-corrective",new float[3],25,27));
        poses.add(pose("smile-blink-tilt",new float[]{18,-32,20},9,10,44,45));
        poses.add(pose("head-envelope-combination",new float[]{45,52,-20},25,15,14));
        float[] all=new float[52];for(int i=1;i<52;i++)all[i]=1;
        poses.add(new Pose("all-controls",-1,all,new float[]{-30,-45,25}));
        return Collections.unmodifiableList(poses);
    }
    private static Pose pose(String name,float[] angles,int... sources){float[] w=new float[52];for(int i:sources)w[i]=1;return new Pose(name,-1,w,angles);}
    public static final class Pose {
        private final String name;private final int source;private final float[] weights,angles;
        private Pose(String name,int source,float[] weights,float[] angles){this.name=name;this.source=source;this.weights=weights.clone();this.angles=angles.clone();}
        public String name(){return name;}
        /** -1 for neutral/head/combined fixtures, otherwise the exact schema position under test. */
        public int sourceIndex(){return source;}
        public void copyWeights(float[] destination){if(destination==null||destination.length!=52)throw new IllegalArgumentException("Expected 52 weights");System.arraycopy(weights,0,destination,0,52);}
        public void copyAngles(float[] destination){if(destination==null||destination.length!=3)throw new IllegalArgumentException("Expected three angles");System.arraycopy(angles,0,destination,0,3);}
    }
}
