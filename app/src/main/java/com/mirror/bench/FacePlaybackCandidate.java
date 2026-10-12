package com.mirror.bench;

/**
 * Opt-in, offline/debug expression candidate. Production code must keep using
 * {@link FacePlayback#apply}; this class is deliberately never called by the
 * runtime path. It exposes the calibrated ranges proposed by the 8-clip audit
 * without modifying the current response policy.
 */
final class FacePlaybackCandidate {
    /** Immutable candidate response parameters; all values are validated on construction. */
    static final class Config {
        final float browBaselineLeft,browBaselineRight,browSpan,browGamma;
        final float smileBaselineLeft,smileBaselineRight,smileGamma;
        final float mouthPressGamma,mouthPressSmileSuppression;
        final float jawBaseline,jawSpan,jawGamma,jawCap;
        final float eyeSquintBaselineLeft,eyeSquintBaselineRight,eyeSquintSpan,eyeSquintGamma,eyeSquintCap;
        final float eyeWideGamma;
        final boolean horizontalGazeBlinkGate;
        final float horizontalGazeThreshold,horizontalGazeBlinkFactor;

        Config(float browBaselineLeft,float browBaselineRight,float browSpan,float browGamma,
               float smileBaselineLeft,float smileBaselineRight,float smileGamma,
               float mouthPressGamma,float mouthPressSmileSuppression,
               float jawBaseline,float jawSpan,float jawGamma,float jawCap,
               float eyeSquintBaselineLeft,float eyeSquintBaselineRight,float eyeSquintSpan,
               float eyeSquintGamma,float eyeSquintCap,float eyeWideGamma,
               boolean horizontalGazeBlinkGate,float horizontalGazeThreshold,float horizontalGazeBlinkFactor){
            this.browBaselineLeft=requireUnit(browBaselineLeft,"brow baseline left");
            this.browBaselineRight=requireUnit(browBaselineRight,"brow baseline right");
            this.browSpan=requirePositiveSpan(browSpan,"brow span");
            this.browGamma=requireGamma(browGamma,"brow gamma");
            this.smileBaselineLeft=requireSubBaseline(smileBaselineLeft,"smile baseline left");
            this.smileBaselineRight=requireSubBaseline(smileBaselineRight,"smile baseline right");
            this.smileGamma=requireGamma(smileGamma,"smile gamma");
            this.mouthPressGamma=requireGamma(mouthPressGamma,"mouth press gamma");
            this.mouthPressSmileSuppression=requireUnit(mouthPressSmileSuppression,"mouth press smile suppression");
            this.jawBaseline=requireUnit(jawBaseline,"jaw baseline");
            this.jawSpan=requirePositiveSpan(jawSpan,"jaw span");
            this.jawGamma=requireGamma(jawGamma,"jaw gamma");
            this.jawCap=requireUnit(jawCap,"jaw cap");
            this.eyeSquintBaselineLeft=requireUnit(eyeSquintBaselineLeft,"eye squint baseline left");
            this.eyeSquintBaselineRight=requireUnit(eyeSquintBaselineRight,"eye squint baseline right");
            this.eyeSquintSpan=requirePositiveSpan(eyeSquintSpan,"eye squint span");
            this.eyeSquintGamma=requireGamma(eyeSquintGamma,"eye squint gamma");
            this.eyeSquintCap=requireUnit(eyeSquintCap,"eye squint cap");
            this.eyeWideGamma=requireGamma(eyeWideGamma,"eye wide gamma");
            this.horizontalGazeBlinkGate=horizontalGazeBlinkGate;
            this.horizontalGazeThreshold=requireUnit(horizontalGazeThreshold,"horizontal gaze threshold");
            this.horizontalGazeBlinkFactor=requireUnit(horizontalGazeBlinkFactor,"horizontal gaze blink factor");
        }

        /** Conservative values from the offline audit; baselines remain zero until a live neutral session supplies them. */
        static Config auditDefaults(){
            return new Config(0,0,.40f,1.15f,0,0,3.20f,1.15f,.65f,
                    0,.40f,1f,.82f,0,0,.45f,1f,.80f,3.50f,false,.30f,.65f);
        }
        Config withPersonalBaselines(float browLeft,float browRight,float jaw,float squintLeft,float squintRight){
            return new Config(browLeft,browRight,browSpan,browGamma,smileBaselineLeft,smileBaselineRight,smileGamma,
                    mouthPressGamma,mouthPressSmileSuppression,jaw,jawSpan,jawGamma,jawCap,
                    squintLeft,squintRight,eyeSquintSpan,eyeSquintGamma,eyeSquintCap,eyeWideGamma,
                    horizontalGazeBlinkGate,horizontalGazeThreshold,horizontalGazeBlinkFactor);
        }
        Config withHorizontalGazeBlinkGate(boolean enabled){
            return new Config(browBaselineLeft,browBaselineRight,browSpan,browGamma,smileBaselineLeft,smileBaselineRight,smileGamma,
                    mouthPressGamma,mouthPressSmileSuppression,jawBaseline,jawSpan,jawGamma,jawCap,
                    eyeSquintBaselineLeft,eyeSquintBaselineRight,eyeSquintSpan,eyeSquintGamma,eyeSquintCap,eyeWideGamma,
                    enabled,horizontalGazeThreshold,horizontalGazeBlinkFactor);
        }
        private static float requireUnit(float v,String n){if(!Float.isFinite(v)||v<0||v>1)throw new IllegalArgumentException("Invalid "+n);return v;}
        private static float requireSubBaseline(float v,String n){if(!Float.isFinite(v)||v<0||v>=1)throw new IllegalArgumentException("Invalid "+n);return v;}
        private static float requirePositiveSpan(float v,String n){if(!Float.isFinite(v)||v<=0||v>2)throw new IllegalArgumentException("Invalid "+n);return v;}
        private static float requireGamma(float v,String n){if(!Float.isFinite(v)||v<1||v>8)throw new IllegalArgumentException("Invalid "+n);return v;}
    }

    private static final int[] REFLECT=FaceControlMapper.mirrorPermutation();
    private FacePlaybackCandidate(){}

    /**
     * Applies the candidate after the current mapping has produced a complete bounded frame.
     * Inputs and output ownership follow FacePlayback; caller must opt in explicitly.
     */
    static void apply(float[] source,float[] pose,boolean mirror,float gain,Config config,float[] weights,float[] angles){
        if(config==null)throw new IllegalArgumentException("Candidate config is required");
        FacePlayback.applyEyeWideCandidate(source,pose,mirror,gain,weights,angles);
        float[] raw=source.clone();
        if(mirror){float[] reflected=new float[52];for(int i=0;i<52;i++)reflected[i]=raw[REFLECT[i]];raw=reflected;}
        weights[1]=calibrated(raw[1],config.browBaselineLeft,config.browSpan,config.browGamma);
        weights[2]=calibrated(raw[2],config.browBaselineRight,config.browSpan,config.browGamma);
        float smileLeft=calibrated(raw[44],config.smileBaselineLeft,1-config.smileBaselineLeft,config.smileGamma);
        float smileRight=calibrated(raw[45],config.smileBaselineRight,1-config.smileBaselineRight,config.smileGamma);
        weights[44]=smileLeft;weights[45]=smileRight;
        float smile=(smileLeft+smileRight)*.5f;
        weights[36]=boundedResponse(raw[36],config.mouthPressGamma)*(1-config.mouthPressSmileSuppression*smile);
        weights[37]=boundedResponse(raw[37],config.mouthPressGamma)*(1-config.mouthPressSmileSuppression*smile);
        weights[25]=config.jawCap*calibrated(raw[25],config.jawBaseline,config.jawSpan,config.jawGamma);
        weights[19]=config.eyeSquintCap*calibrated(raw[19],config.eyeSquintBaselineLeft,config.eyeSquintSpan,config.eyeSquintGamma);
        weights[20]=config.eyeSquintCap*calibrated(raw[20],config.eyeSquintBaselineRight,config.eyeSquintSpan,config.eyeSquintGamma);
        if(config.horizontalGazeBlinkGate){
            float horizontal=(raw[13]+raw[14]+raw[15]+raw[16])*.25f;
            if(horizontal>config.horizontalGazeThreshold){
                weights[9]*=config.horizontalGazeBlinkFactor;
                weights[10]*=config.horizontalGazeBlinkFactor;
            }
        }
        // Keep brow coupling from undoing the calibrated independent response.
        float rest=1-weights[3];weights[1]*=rest*rest;weights[2]*=rest*rest;
        clamp(weights);
    }

    private static float calibrated(float value,float baseline,float span,float gamma){
        return boundedResponse(Math.max(0,value-baseline)/span,gamma);
    }
    private static float boundedResponse(float value,float gamma){
        float x=Math.max(0,Math.min(1,value));return (float)(1-Math.pow(1-x,gamma));
    }
    private static void clamp(float[] values){for(int i=0;i<values.length;i++)values[i]=Math.max(0,Math.min(1,values[i]));}
}
