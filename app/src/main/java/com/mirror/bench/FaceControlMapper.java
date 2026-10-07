package com.mirror.bench;

/** Pure control math. Call only for fresh drive=true raw frames, before Controller smoothing. */
public final class FaceControlMapper {
    // output[i]=input[MIRROR[i]]. Explicit MediaPipe schema permutation, not string inference.
    private static final int[] MIRROR={0,2,1,3,5,4,6,8,7,10,9,12,11,14,13,16,15,18,17,20,19,22,21,
            23,26,25,24,27,29,28,31,30,32,39,35,34,37,36,38,33,40,41,42,43,45,44,47,46,49,48,51,50};
    private FaceControlMapper(){}
    public static int[] mirrorPermutation(){return MIRROR.clone();}

    /** Immutable all-or-nothing output carrying the exact configuration revision used for both parts. */
    public static final class Mapped {
        private final long revision;
        private final float[] weights,pose;
        private Mapped(long revision,float[] ownedWeights,float[] ownedPose){this.revision=revision;weights=ownedWeights;pose=ownedPose;}
        public long revision(){return revision;}
        public float[] blendshapes52(){return weights.clone();}
        /** Identity head mapping preserves raw pose bits; corrected output is a rotation-only matrix. */
        public float[] pose(){return pose.clone();}
    }

    /** Invalid complete inputs throw; no partial result or mutated caller data is published. */
    public static Mapped mapActive(float[] raw52,float[] columnMajorPose,FaceControlCalibration calibration){
        requireConfig(calibration);
        float[] weights=copyWeights(raw52);
        float[] rawPose=copyPose(columnMajorPose);
        double[] rotation=rotation3x3Owned(rawPose);
        FaceControlCalibration.PersonalBaseline baseline=calibration.personalBaseline();
        if(baseline!=null){
            weights[1]=unipolar(weights[1],baseline.browDownLeft());
            weights[2]=unipolar(weights[2],baseline.browDownRight());
            weights[9]=unipolar(weights[9],baseline.blinkLeft());
            weights[10]=unipolar(weights[10],baseline.blinkRight());
            weights[25]=unipolar(weights[25],baseline.jawOpen());
            gaze(weights,15,13,baseline.leftOut());gaze(weights,16,14,baseline.rightOut());
            gaze(weights,17,11,baseline.leftUp());gaze(weights,18,12,baseline.rightUp());
        }
        if(calibration.mirror()){
            float[] swapped=new float[52];for(int i=0;i<52;i++)swapped[i]=weights[MIRROR[i]];weights=swapped;
        }
        if(calibration.hasNeutralPose()){
            double[] relative=new double[9];
            // Column vectors: raw=R0*Delta, hence Delta=transpose(R0)*raw.
            for(int column=0;column<3;column++)for(int row=0;row<3;row++){
                double value=0;for(int k=0;k<3;k++)value+=calibration.neutralElement(k,row)*rotation[column*3+k];
                relative[column*3+row]=value;
            }
            rotation=relative;
        }
        if(calibration.mirror()){
            // S*R*S, S=diag(-1,1,1). Do not introduce a negative model scale.
            for(int column=0;column<3;column++)for(int row=0;row<3;row++)
                if((column==0)!=(row==0))rotation[column*3+row]=-rotation[column*3+row];
        }
        float[] mappedPose=calibration.hasNeutralPose()||calibration.mirror()?poseFromRotation(rotation):rawPose;
        return new Mapped(calibration.revision(),weights,mappedPose);
    }

    /** Non-drive state is already in output coordinates. Never apply R0 or personal biases to it. */
    public static Mapped neutral(FaceControlCalibration calibration){
        requireConfig(calibration);return new Mapped(calibration.revision(),new float[52],identity());
    }

    /** Validated proper rotation for a future neutral collector; translation/positive column scale removed. */
    public static float[] rotationPose(float[] columnMajorPose){return poseFromRotation(rotation3x3(columnMajorPose));}
    static double[] rotation3x3(float[] pose){return rotation3x3Owned(copyPose(pose));}
    private static double[] rotation3x3Owned(float[] pose){
        // Match the current renderer's acceptance window; do not silently tighten legacy valid output.
        if(Math.abs(pose[3])>.001f||Math.abs(pose[7])>.001f||Math.abs(pose[11])>.001f||Math.abs(pose[15]-1)>.001f)
            throw new IllegalArgumentException("Pose must be a column-major affine 4x4 matrix");
        double[] r=new double[9];
        for(int column=0;column<3;column++){
            int p=column*4,c=column*3;double x=pose[p],y=pose[p+1],z=pose[p+2];
            double norm=Math.sqrt(x*x+y*y+z*z);
            if(norm<1e-6||norm>1e6)throw new IllegalArgumentException("Pose basis scale is degenerate or excessive");
            r[c]=x/norm;r[c+1]=y/norm;r[c+2]=z/norm;
        }
        for(int a=0;a<3;a++)for(int b=a+1;b<3;b++)if(Math.abs(dot(r,a,b))>.05)
            throw new IllegalArgumentException("Pose basis contains excessive shear");
        double det=r[0]*(r[4]*r[8]-r[7]*r[5])-r[3]*(r[1]*r[8]-r[7]*r[2])+r[6]*(r[1]*r[5]-r[4]*r[2]);
        if(det<.9)throw new IllegalArgumentException("Pose basis is reflected or degenerate");
        // Accepted numerical drift is repaired only for actual head calibration/reflection or collectors.
        // Stable modified Gram-Schmidt: keep the first axis, orthogonalize second, derive right-handed third.
        double projection=dot(r,0,1),norm=0;
        for(int row=0;row<3;row++){r[3+row]-=projection*r[row];norm+=r[3+row]*r[3+row];}
        norm=Math.sqrt(norm);for(int row=0;row<3;row++)r[3+row]/=norm;
        r[6]=r[1]*r[5]-r[2]*r[4];r[7]=r[2]*r[3]-r[0]*r[5];r[8]=r[0]*r[4]-r[1]*r[3];
        return r;
    }
    private static double dot(double[] r,int a,int b){return r[a*3]*r[b*3]+r[a*3+1]*r[b*3+1]+r[a*3+2]*r[b*3+2];}
    static float[] poseFromRotation(double[] r){
        float[] pose=identity();for(int column=0;column<3;column++)for(int row=0;row<3;row++)pose[column*4+row]=(float)r[column*3+row];return pose;
    }
    private static float[] copyPose(float[] values){
        if(values==null||values.length!=16)throw new IllegalArgumentException("Pose must contain 16 column-major floats");
        float[] copy=values.clone();for(float value:copy)if(!Float.isFinite(value))throw new IllegalArgumentException("Pose must be finite");return copy;
    }
    private static float[] copyWeights(float[] values){
        if(values==null||values.length!=52)throw new IllegalArgumentException("Expected all 52 MediaPipe coefficients");
        float[] copy=values.clone();for(float value:copy)if(!Float.isFinite(value)||value<0||value>1)
            throw new IllegalArgumentException("Raw blendshape coefficients must be finite within [0,1]");return copy;
    }
    private static float unipolar(float value,float baseline){
        if(baseline==0)return value;
        return unit(((double)value-baseline)/(1.0-baseline));
    }
    private static void gaze(float[] weights,int positive,int negative,float baseline){
        // Identity must preserve both coactive source values; net direction alone is not all source data.
        if(baseline==0)return;
        double p=weights[positive],n=weights[negative],difference=p-n;
        double shifted=difference-baseline;
        double corrected=Math.max(-1,Math.min(1,shifted/(shifted>=0?1.0-baseline:1.0+baseline)));
        double common=Math.min(Math.min(p,n),1-Math.abs(corrected));
        weights[positive]=unit(common+Math.max(corrected,0));
        weights[negative]=unit(common+Math.max(-corrected,0));
    }
    private static float unit(double value){return (float)Math.max(0,Math.min(1,value));}
    private static float[] identity(){return new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};}
    private static void requireConfig(FaceControlCalibration value){if(value==null)throw new IllegalArgumentException("An atomic calibration snapshot is required");}
}
