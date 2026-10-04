package com.mirror.bench;

/** Render-only controls; detector inputs, canonical pose and derived rig corrections stay intact. */
final class FacePlayback {
    private static final int[] REFLECT=FaceControlMapper.mirrorPermutation();
    private static final boolean[] AMPLIFY=new boolean[BlendshapeSchema.SIZE];
    static {
        for(int i=0;i<REFLECT.length;i++){
            String name=BlendshapeSchema.name(i);
            // Eye closure/wide/squint keep their calibrated input range. Amplifying
            // all of them makes partial blinking reach full closure too early.
            AMPLIFY[i]=i!=0&&!name.startsWith("eye")&&!name.startsWith("cheekSquint")&&!name.equals("mouthClose");
        }
    }
    private FacePlayback(){}
    static void apply(float[] source,float[] pose,boolean mirror,float gain,float[] weights,float[] angles){
        if(source==null||source.length!=52||pose==null||pose.length!=3||weights==null||weights.length!=52||angles==null||angles.length!=3
                ||source==weights||pose==angles||!Float.isFinite(gain)||gain<.5f||gain>4)
            throw new IllegalArgumentException("Invalid face playback input");
        for(int i=0;i<52;i++){
            float value=source[mirror?REFLECT[i]:i];
            if(!Float.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("Invalid source facial coefficient");
            // Endpoint-preserving response: no hard saturation below input 1.
            weights[i]=!AMPLIFY[i]||gain==1?value:(float)(1-Math.pow(1-value,gain));
        }
        for(int i=0;i<3;i++){
            if(!Float.isFinite(pose[i])||Math.abs(pose[i])>90)throw new IllegalArgumentException("Invalid source head angle");
            angles[i]=(mirror&&i>0)?-pose[i]:pose[i];
        }
    }
}
