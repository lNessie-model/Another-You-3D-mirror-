package com.mirror.bench;

/** Render-only controls; detector inputs, canonical pose and derived rig corrections stay intact. */
final class FacePlayback {
    /** Current authored response. Keep this unchanged until a live visual check accepts a candidate. */
    static final float EYE_WIDE_GAMMA_CURRENT=1.65f;
    /** Offline/debug candidate measured against the captured surprise clips; never used by apply(). */
    static final float EYE_WIDE_GAMMA_CANDIDATE=3.5f;
    private static final int[] REFLECT=FaceControlMapper.mirrorPermutation();
    private static final boolean[] AMPLIFY=new boolean[BlendshapeSchema.SIZE];
    static {
        for(int i=0;i<REFLECT.length;i++){
            String name=BlendshapeSchema.name(i);
            // Brow depression and the two eye endpoints have separate responses.
            // Jaw aperture and closure retain capture proportions. Smile/brow lift uses the user gain.
            AMPLIFY[i]=i!=0&&!name.startsWith("eye")&&!name.startsWith("browDown")&&!name.startsWith("cheekSquint")&&!name.equals("mouthClose")&&!name.equals("jawOpen");
        }
    }
    private FacePlayback(){}
    static void apply(float[] source,float[] pose,boolean mirror,float gain,float[] weights,float[] angles){
        applyInternal(source,pose,mirror,gain,EYE_WIDE_GAMMA_CURRENT,weights,angles);
    }
    /**
     * Candidate-only response for offline/device A-B checks. It deliberately does not change the
     * production path above: callers must opt in and compare the resulting preview before adopting it.
     */
    static void applyEyeWideCandidate(float[] source,float[] pose,boolean mirror,float gain,float[] weights,float[] angles){
        applyEyeWideGamma(source,pose,mirror,gain,EYE_WIDE_GAMMA_CANDIDATE,weights,angles);
    }
    /** Opt-in candidate hook; production apply() still uses EYE_WIDE_GAMMA_CURRENT. */
    static void applyEyeWideGamma(float[] source,float[] pose,boolean mirror,float gain,float eyeWideGamma,float[] weights,float[] angles){
        applyInternal(source,pose,mirror,gain,eyeWideGamma,weights,angles);
    }
    private static void applyInternal(float[] source,float[] pose,boolean mirror,float gain,float eyeWideGamma,float[] weights,float[] angles){
        if(source==null||source.length!=52||pose==null||pose.length!=3||weights==null||weights.length!=52||angles==null||angles.length!=3
                ||source==weights||pose==angles||!Float.isFinite(gain)||gain<.5f||gain>4
                ||!Float.isFinite(eyeWideGamma)||eyeWideGamma<1||eyeWideGamma>4)
            throw new IllegalArgumentException("Invalid face playback input");
        for(int i=0;i<52;i++){
            float value=source[mirror?REFLECT[i]:i];
            if(!Float.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("Invalid source facial coefficient");
            // General mouth/brow response preserves its endpoint; blink has a separate strong-closure range.
            if(i==9||i==10){
                // Initial artist response, not a measured personal calibration:
                // preserve weak blinks, reach closure at a strong .72 input.
                double phase=Math.max(0,Math.min(1,(value-.04)/.68));
                weights[i]=(float)((1-Math.cos(Math.PI*phase))*.5);
            }else if(i==21||i==22)weights[i]=(float)(1-Math.pow(1-value,eyeWideGamma));
            else if(i==1||i==2)weights[i]=(float)Math.max(0,(value-.12)/.88);
            else weights[i]=!AMPLIFY[i]||gain==1?value:(float)(1-Math.pow(1-value,gain));
        }
        // A lifted inner brow must not fight a simultaneously inferred frown.
        float rest=1-weights[3];weights[1]*=rest*rest;weights[2]*=rest*rest;
        for(int i=0;i<3;i++){
            if(!Float.isFinite(pose[i])||Math.abs(pose[i])>90)throw new IllegalArgumentException("Invalid source head angle");
            angles[i]=(mirror&&i>0)?-pose[i]:pose[i];
        }
    }
}
