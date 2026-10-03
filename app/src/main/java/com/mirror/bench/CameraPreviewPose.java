package com.mirror.bench;

/** Latest-only calibrated control mailbox; GL samples the same angular convention as InterlaceRenderer. */
final class CameraPreviewPose {
    private static final long MAX_FACE_AGE_NS=500_000_000L,MAX_SUBMIT_AGE_NS=1_000_000_000L;
    private Input latest;
    private final float[] weights=new float[52],angles=new float[3];
    private long previousNs=-1;
    private record Input(float[] weights,float[] angles,boolean active,long receivedNs,long submittedNs){}
    synchronized void offer(float[] full52,float[] matrix,boolean active,long receivedNs,long submittedNs){
        if(full52==null||full52.length!=52||submittedNs<0)throw new IllegalArgumentException("Invalid preview controls");
        float[] copy=full52.clone();
        for(float value:copy)if(!Float.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("Invalid preview coefficient");
        float[] rotation=active?readRotation(matrix):new float[3];
        latest=new Input(copy,rotation,active,receivedNs,submittedNs);
    }
    synchronized void reset(){latest=null;previousNs=-1;java.util.Arrays.fill(weights,0);java.util.Arrays.fill(angles,0);}
    synchronized void sample(long now,float[] output52,float[] outputAngles){
        if(now<0||now<previousNs||output52.length!=52||outputAngles.length!=3)throw new IllegalArgumentException("Invalid preview sample");
        Input input=latest;
        boolean active=input!=null&&input.active&&input.receivedNs>=0&&now>=input.receivedNs
                &&now-input.receivedNs<=MAX_FACE_AGE_NS&&now>=input.submittedNs&&now-input.submittedNs<MAX_SUBMIT_AGE_NS;
        double seconds=previousNs<0?1d/30:(now-previousNs)/1e9;previousNs=now;
        float alpha=(float)(1-Math.exp(-seconds/(active?.10:.25)));
        for(int i=0;i<52;i++){
            float target=active?input.weights[i]:0;
            weights[i]=active?target:weights[i]+alpha*(target-weights[i]);
            if(Math.abs(weights[i]-target)<.0001f)weights[i]=target;
        }
        for(int i=0;i<3;i++)angles[i]+=alpha*((active?input.angles[i]:0)-angles[i]);
        System.arraycopy(weights,0,output52,0,52);System.arraycopy(angles,0,outputAngles,0,3);
    }
    /** Same Rz(roll)*Ry(yaw)*Rx(pitch), basis normalization and acceptance as the main runtime. */
    static float[] readRotation(float[] pose){
        if(pose==null||pose.length!=16)throw new IllegalArgumentException("Expected column-major pose");
        for(float value:pose)if(!Float.isFinite(value))throw new IllegalArgumentException("Nonfinite preview pose");
        if(Math.abs(pose[3])>.001f||Math.abs(pose[7])>.001f||Math.abs(pose[11])>.001f||Math.abs(pose[15]-1)>.001f)
            throw new IllegalArgumentException("Nonaffine preview pose");
        double[] r=new double[9];
        for(int column=0;column<3;column++){
            int p=column*4,c=column*3;double x=pose[p],y=pose[p+1],z=pose[p+2],norm=Math.sqrt(x*x+y*y+z*z);
            if(norm<1e-6||norm>1e6)throw new IllegalArgumentException("Degenerate preview pose");
            r[c]=x/norm;r[c+1]=y/norm;r[c+2]=z/norm;
        }
        for(int a=0;a<3;a++)for(int b=a+1;b<3;b++)
            if(Math.abs(r[a*3]*r[b*3]+r[a*3+1]*r[b*3+1]+r[a*3+2]*r[b*3+2])>.05)
                throw new IllegalArgumentException("Sheared preview pose");
        double determinant=r[0]*(r[4]*r[8]-r[7]*r[5])-r[3]*(r[1]*r[8]-r[7]*r[2])+r[6]*(r[1]*r[5]-r[4]*r[2]);
        if(determinant<.9)throw new IllegalArgumentException("Reflected preview pose");
        return new float[]{degrees(Math.atan2(r[5],r[8]),45),degrees(Math.asin(Math.max(-1,Math.min(1,-r[2]))),65),degrees(Math.atan2(r[1],r[0]),40)};
    }
    private static float degrees(double radians,float max){return (float)Math.max(-max,Math.min(max,Math.toDegrees(radians)));}
    static int[] viewport(int width,int height){
        if(width<1||height<1)throw new IllegalArgumentException("Preview dimensions");
        int w=Math.min(width,Math.max(1,(int)(height*.625))),h=Math.min(height,Math.max(1,(int)(w/.625)));
        return new int[]{(width-w)/2,(height-h)/2,w,h};
    }
}
