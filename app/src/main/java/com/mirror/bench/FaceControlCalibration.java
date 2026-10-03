package com.mirror.bench;

/** One immutable control revision; a caller atomically publishes this whole object, never its fields. */
public final class FaceControlCalibration {
    private final long revision;
    private final boolean mirror;
    private final double[] neutralRotation;
    private final PersonalBaseline personalBaseline;

    /** Null neutralPose/personalBaseline explicitly disables that correction. */
    public FaceControlCalibration(long revision,boolean mirror,float[] neutralPose,PersonalBaseline personalBaseline) {
        if(revision<0)throw new IllegalArgumentException("Calibration revision must be nonnegative");
        this.revision=revision;this.mirror=mirror;
        this.neutralRotation=neutralPose==null?null:FaceControlMapper.rotation3x3(neutralPose);
        this.personalBaseline=personalBaseline;
    }
    public static FaceControlCalibration defaults(){return new FaceControlCalibration(0,false,null,null);}
    public long revision(){return revision;}
    public boolean mirror(){return mirror;}
    public boolean hasNeutralPose(){return neutralRotation!=null;}
    /** Owned column-major rotation-only copy, or null if no head reference is configured. */
    public float[] neutralPose(){return neutralRotation==null?null:FaceControlMapper.poseFromRotation(neutralRotation);}
    /** Immutable value, safe to share. Session baseline must not be persisted as an installation setting. */
    public PersonalBaseline personalBaseline(){return personalBaseline;}
    double neutralElement(int row,int column){return neutralRotation[column*3+row];}

    /**
     * Explicit, already accepted neutral-sampling result, in canonical anatomical coordinates.
     * This class checks numerical safety only, not whether samples actually depict a neutral person.
     * All seven values are session-scoped. A new person/session must use null until sampled again.
     */
    public static final class PersonalBaseline {
        // Bounds cap the optional endpoint-preserving gain at 2. They are engineering bounds,
        // not validated criteria for neutral collection; the collector must also check fresh/stable input.
        public static final float MAX_BASELINE=.5f;
        private final float blinkLeft,blinkRight,jawOpen,leftOut,rightOut,leftUp,rightUp;
        public PersonalBaseline(float blinkLeft,float blinkRight,float jawOpen,
                                float leftOut,float rightOut,float leftUp,float rightUp) {
            check(blinkLeft,0,MAX_BASELINE,"left blink");check(blinkRight,0,MAX_BASELINE,"right blink");
            check(jawOpen,0,MAX_BASELINE,"jaw open");
            check(leftOut,-MAX_BASELINE,MAX_BASELINE,"left outward gaze");
            check(rightOut,-MAX_BASELINE,MAX_BASELINE,"right outward gaze");
            check(leftUp,-MAX_BASELINE,MAX_BASELINE,"left upward gaze");
            check(rightUp,-MAX_BASELINE,MAX_BASELINE,"right upward gaze");
            this.blinkLeft=blinkLeft;this.blinkRight=blinkRight;this.jawOpen=jawOpen;
            this.leftOut=leftOut;this.rightOut=rightOut;this.leftUp=leftUp;this.rightUp=rightUp;
        }
        public float blinkLeft(){return blinkLeft;}
        public float blinkRight(){return blinkRight;}
        public float jawOpen(){return jawOpen;}
        /** Per-eye outward-positive difference: eyeLookOut-eyeLookIn. */
        public float leftOut(){return leftOut;}
        public float rightOut(){return rightOut;}
        /** Per-eye upward-positive difference: eyeLookUp-eyeLookDown. */
        public float leftUp(){return leftUp;}
        public float rightUp(){return rightUp;}
        private static void check(float value,float min,float max,String label){
            if(!Float.isFinite(value)||value<min||value>max)
                throw new IllegalArgumentException(label+" baseline must be finite within ["+min+","+max+"]");
        }
    }
}
