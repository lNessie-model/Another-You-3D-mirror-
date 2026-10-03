package com.mirror.bench;

import java.util.Arrays;
import java.util.function.LongSupplier;

/** Bounded raw-frame neutral collection. READY is a draft for explicit confirmation, not a saved setting. */
public final class NeutralCalibrationCollector {
    public static final int MIN_SAMPLES=20,MAX_SAMPLES=64;
    public static final long STABLE_WINDOW_NS=2_000_000_000L,TOTAL_TIMEOUT_NS=20_000_000_000L;
    public static final long MAX_FRAME_AGE_NS=250_000_000L,MAX_FRAME_GAP_NS=250_000_000L,SAMPLE_SPACING_NS=50_000_000L;
    public static final double MAX_ANCHOR_ANGLE_DEGREES=3,MAX_BASELINE_SPAN=.08;
    public enum Status { IDLE,COLLECTING,READY,CANCELLED,TIMED_OUT,STALE_SESSION }
    public enum Reason { NONE,DUPLICATE_OR_OLD_SEQUENCE,NO_FACE,INVALID_FRAME,INVALID_WEIGHTS,INVALID_POSE,
        BEFORE_COLLECTION,FUTURE_FRAME,STALE_FRAME,NON_MONOTONIC_FRAME,NON_MONOTONIC_CLOCK,
        FRAME_GAP,HEAD_MOVING,COEFFICIENT_CHANGED,EYES_NOT_RELAXED,MOUTH_NOT_RELAXED,GAZE_NOT_CENTERED,
        SAMPLE_LIMIT,USER_CANCELLED,TIME_LIMIT,SESSION_MISMATCH }
    public static final class Session { private Session(){} }

    public static final class Result {
        private final float[] pose;
        private final FaceControlCalibration.PersonalBaseline personal;
        private final int samples;
        private final long firstSequence,lastSequence,firstReceivedNs,lastReceivedNs;
        private final double maxAnchorAngleDegrees,maxBaselineSpan;
        private Result(float[] pose,FaceControlCalibration.PersonalBaseline personal,int samples,
                       long firstSequence,long lastSequence,long firstReceivedNs,long lastReceivedNs,
                       double maxAnchorAngleDegrees,double maxBaselineSpan){
            this.pose=pose.clone();this.personal=personal;this.samples=samples;
            this.firstSequence=firstSequence;this.lastSequence=lastSequence;this.firstReceivedNs=firstReceivedNs;this.lastReceivedNs=lastReceivedNs;
            this.maxAnchorAngleDegrees=maxAnchorAngleDegrees;this.maxBaselineSpan=maxBaselineSpan;
        }
        public float[] neutralPose(){return pose.clone();}
        /** Session-only explicit opt-in. Numerical safety is not proof of a neutral human expression. */
        public FaceControlCalibration.PersonalBaseline personalBaseline(){return personal;}
        public int samples(){return samples;}
        public long firstSequence(){return firstSequence;}
        public long lastSequence(){return lastSequence;}
        public long firstReceivedNs(){return firstReceivedNs;}
        public long lastReceivedNs(){return lastReceivedNs;}
        public double maxAnchorAngleDegrees(){return maxAnchorAngleDegrees;}
        public double maxBaselineSpan(){return maxBaselineSpan;}
    }
    public static final class Update {
        private final Status status;private final Reason reason;private final String detail;
        private final int samples,resets;private final long stableMillis,remainingMillis;private final Result result;
        private Update(Status status,Reason reason,String detail,int samples,int resets,long stableMillis,long remainingMillis,Result result){
            this.status=status;this.reason=reason;this.detail=detail;this.samples=samples;this.resets=resets;
            this.stableMillis=stableMillis;this.remainingMillis=remainingMillis;this.result=result;
        }
        public Status status(){return status;}
        public Reason reason(){return reason;}
        public String detail(){return detail;}
        public int samples(){return samples;}
        public int resets(){return resets;}
        public long stableMillis(){return stableMillis;}
        public long remainingMillis(){return remainingMillis;}
        public Result result(){return result;}
    }

    private final boolean collectPersonalBaseline;
    private Session session;
    private Status status=Status.IDLE;
    private Reason reason=Reason.NONE;
    private String detail="";
    private long startNs,lastNowNs,lastSeenSequence,lastSeenReceivedNs,lastSeenCompletedNs;
    private long firstSequence,lastSampleSequence,firstReceivedNs,lastSampleReceivedNs,lastGoodReceivedNs;
    private int samples,resets;
    private final double[] sums=new double[7],minima=new double[7],maxima=new double[7];
    private double maxAnchorAngleDegrees,maxBaselineSpan;
    private RotationMean rotations=new RotationMean();
    private Result result;

    public NeutralCalibrationCollector(){this(false);}
    public NeutralCalibrationCollector(boolean collectPersonalBaseline){this.collectPersonalBaseline=collectPersonalBaseline;}
    public synchronized Session begin(long nowNs){
        if(nowNs<0)throw new IllegalArgumentException("Monotonic start time must be nonnegative");
        session=new Session();status=Status.COLLECTING;reason=Reason.NONE;detail="";result=null;resets=0;
        startNs=lastNowNs=nowNs;lastSeenSequence=lastSeenReceivedNs=lastSeenCompletedNs=-1;clearWindow();return session;
    }
    public synchronized Update offer(Session owner,FaceFrame frame,LongSupplier clock){
        if(!owns(owner))return stale();
        if(clock==null)throw new IllegalArgumentException("Monotonic clock is required");
        return offer(owner,frame,clock.getAsLong());
    }
    /** Frame and now must share the same monotonic clock. Invalid observations return diagnostics, not a partial baseline. */
    public synchronized Update offer(Session owner,FaceFrame frame,long nowNs){
        if(!owns(owner))return stale();
        if(status!=Status.COLLECTING)return snapshot();
        if(!advanceTime(nowNs))return snapshot();
        if(frame==null){reset(Reason.INVALID_FRAME,"No raw frame");return snapshot();}
        if(frame.receivedNs()<startNs){reset(Reason.BEFORE_COLLECTION,"Frame was received before explicit collection");return snapshot();}
        if(frame.sequence()<=lastSeenSequence){
            if(!resetGapIfNeeded())setReason(Reason.DUPLICATE_OR_OLD_SEQUENCE,"Sequence already observed");return snapshot();
        }
        lastSeenSequence=frame.sequence();
        if(frame.receivedNs()>nowNs||frame.completedNs()>nowNs){reset(Reason.FUTURE_FRAME,"Frame timestamp is in the future");return snapshot();}
        if(frame.receivedNs()<lastSeenReceivedNs||frame.completedNs()<lastSeenCompletedNs){
            reset(Reason.NON_MONOTONIC_FRAME,"Receipt or completion moved backward on a new sequence");return snapshot();
        }
        lastSeenReceivedNs=frame.receivedNs();lastSeenCompletedNs=frame.completedNs();
        if(nowNs-frame.receivedNs()>MAX_FRAME_AGE_NS){reset(Reason.STALE_FRAME,"Raw frame exceeds freshness limit");return snapshot();}
        if(!frame.present()){reset(Reason.NO_FACE,"Face is absent");return snapshot();}
        float[] weights=frame.blendshapes();
        for(float value:weights)if(!Float.isFinite(value)||value<0||value>1){reset(Reason.INVALID_WEIGHTS,"Raw coefficients must be finite within [0,1]");return snapshot();}
        double[] quaternion;
        try{quaternion=quaternion(FaceControlMapper.rotationPose(frame.pose()));}
        catch(IllegalArgumentException invalid){reset(Reason.INVALID_POSE,invalid.getMessage());return snapshot();}
        Reason notRelaxed=relaxedCheck(weights);
        if(notRelaxed!=Reason.NONE){reset(notRelaxed,"Engineering neutral threshold not met; keep eyes open, mouth relaxed and gaze forward");return snapshot();}
        double[] values={weights[9],weights[10],weights[25],(double)weights[15]-weights[13],
                (double)weights[16]-weights[14],(double)weights[17]-weights[11],(double)weights[18]-weights[12]};
        setReason(Reason.NONE,"");
        if(samples>0&&frame.receivedNs()-lastGoodReceivedNs>MAX_FRAME_GAP_NS)reset(Reason.FRAME_GAP,"Gap breaks continuous collection");
        if(samples>0){
            double angle=rotations.angleFromFirst(quaternion);
            if(angle>MAX_ANCHOR_ANGLE_DEGREES+1e-5)reset(Reason.HEAD_MOVING,"Head moved beyond the anchor angle threshold");
            else maxAnchorAngleDegrees=Math.max(maxAnchorAngleDegrees,angle);
        }
        if(samples>0){
            for(int i=0;i<7;i++)if(Math.max(maxima[i],values[i])-Math.min(minima[i],values[i])>MAX_BASELINE_SPAN+1e-7){
                reset(Reason.COEFFICIENT_CHANGED,"Eye, mouth or gaze baseline is not stable");break;
            }
        }
        if(samples==0){firstSequence=frame.sequence();firstReceivedNs=frame.receivedNs();}
        lastGoodReceivedNs=frame.receivedNs();
        // Every unique valid frame contributes quality bounds, even if it is not a statistical sample.
        for(int i=0;i<7;i++){
            minima[i]=Math.min(minima[i],values[i]);maxima[i]=Math.max(maxima[i],values[i]);
            maxBaselineSpan=Math.max(maxBaselineSpan,maxima[i]-minima[i]);
        }
        if(samples>0&&frame.receivedNs()-lastSampleReceivedNs<SAMPLE_SPACING_NS)return snapshot();
        if(samples>=MAX_SAMPLES){reset(Reason.SAMPLE_LIMIT,"Bounded sample capacity reached");return snapshot();}
        rotations.add(quaternion);for(int i=0;i<7;i++)sums[i]+=values[i];samples++;
        lastSampleSequence=frame.sequence();lastSampleReceivedNs=frame.receivedNs();
        if(samples>=MIN_SAMPLES&&lastSampleReceivedNs-firstReceivedNs>=STABLE_WINDOW_NS){
            FaceControlCalibration.PersonalBaseline personal=collectPersonalBaseline?
                    new FaceControlCalibration.PersonalBaseline((float)(sums[0]/samples),(float)(sums[1]/samples),(float)(sums[2]/samples),
                            (float)(sums[3]/samples),(float)(sums[4]/samples),(float)(sums[5]/samples),(float)(sums[6]/samples)):null;
            result=new Result(rotations.pose(),personal,samples,firstSequence,lastSampleSequence,firstReceivedNs,lastSampleReceivedNs,maxAnchorAngleDegrees,maxBaselineSpan);
            status=Status.READY;setReason(Reason.NONE,"");
        }
        return snapshot();
    }
    public synchronized Update poll(Session owner,LongSupplier clock){
        if(!owns(owner))return stale();if(clock==null)throw new IllegalArgumentException("Monotonic clock is required");return poll(owner,clock.getAsLong());
    }
    public synchronized Update poll(Session owner,long nowNs){
        if(!owns(owner))return stale();
        if(status==Status.COLLECTING&&advanceTime(nowNs))resetGapIfNeeded();
        return snapshot();
    }
    public synchronized Update cancel(Session owner){
        if(!owns(owner))return stale();
        status=Status.CANCELLED;result=null;clearWindow();setReason(Reason.USER_CANCELLED,"Collection cancelled");return snapshot();
    }
    /** UI must check this at confirmation and cancel on exit/HOME. A Result alone is not authorization. */
    public synchronized boolean isCurrentReady(Session owner,Result expected){return owns(owner)&&status==Status.READY&&expected!=null&&expected==result;}
    private boolean owns(Session owner){return owner!=null&&owner==session;}
    private boolean advanceTime(long nowNs){
        if(nowNs<0||nowNs<lastNowNs){reset(Reason.NON_MONOTONIC_CLOCK,"Caller monotonic clock moved backward");return false;}
        lastNowNs=nowNs;
        if(nowNs-startNs>=TOTAL_TIMEOUT_NS){status=Status.TIMED_OUT;result=null;clearWindow();setReason(Reason.TIME_LIMIT,"Overall collection deadline reached");return false;}
        return true;
    }
    private boolean resetGapIfNeeded(){
        if(samples>0&&lastNowNs-lastGoodReceivedNs>MAX_FRAME_GAP_NS){reset(Reason.FRAME_GAP,"No fresh face observation within continuity limit");return true;}return false;
    }
    private void reset(Reason why,String message){if(samples>0&&resets<Integer.MAX_VALUE)resets++;clearWindow();setReason(why,message);}
    private void clearWindow(){
        samples=0;firstSequence=lastSampleSequence=firstReceivedNs=lastSampleReceivedNs=lastGoodReceivedNs=-1;
        maxAnchorAngleDegrees=maxBaselineSpan=0;rotations=new RotationMean();
        Arrays.fill(sums,0);Arrays.fill(minima,Double.POSITIVE_INFINITY);Arrays.fill(maxima,Double.NEGATIVE_INFINITY);
    }
    private void setReason(Reason value,String message){reason=value;detail=message==null?"":message.length()>180?message.substring(0,180):message;}
    private Update snapshot(){
        long stable=samples==0?0:(lastSampleReceivedNs-firstReceivedNs)/1_000_000L;
        long remaining=status==Status.COLLECTING?Math.max(0,TOTAL_TIMEOUT_NS-(lastNowNs-startNs))/1_000_000L:0;
        return new Update(status,reason,detail,samples,resets,stable,remaining,result);
    }
    private static Update stale(){return new Update(Status.STALE_SESSION,Reason.SESSION_MISMATCH,"Obsolete or missing collection owner",0,0,0,0,null);}
    private static Reason relaxedCheck(float[] w){
        if(w[9]>.25f||w[10]>.25f)return Reason.EYES_NOT_RELAXED;
        if(w[25]>.20f||w[32]>.30f||w[38]>.30f||w[44]>.30f||w[45]>.30f)return Reason.MOUTH_NOT_RELAXED;
        for(int i=11;i<=18;i++)if(w[i]>.5f)return Reason.GAZE_NOT_CENTERED;
        if(Math.abs(w[15]-w[13])>.35f||Math.abs(w[16]-w[14])>.35f||Math.abs(w[17]-w[11])>.35f||Math.abs(w[18]-w[12])>.35f)
            return Reason.GAZE_NOT_CENTERED;
        return Reason.NONE;
    }

    /** Small internal streaming statistic, independently exercised for quaternion sign ambiguity. */
    static final class RotationMean {
        private final double[] sum=new double[4];private double[] first;private int count;
        void add(double[] value){
            if(count>=MAX_SAMPLES)throw new IllegalArgumentException("Rotation sample capacity reached");
            double[] q=unit(value);if(first==null)first=q.clone();
            double sign=dot(first,q)<0?-1:1;for(int i=0;i<4;i++)sum[i]+=sign*q[i];count++;
        }
        double angleFromFirst(double[] q){return first==null?0:Math.toDegrees(2*Math.acos(Math.min(1,Math.abs(dot(first,q)))));}
        float[] pose(){
            if(count==0)throw new IllegalArgumentException("No rotation samples");
            double[] q=unit(sum);double w=q[0],x=q[1],y=q[2],z=q[3];
            return new float[]{(float)(1-2*(y*y+z*z)),(float)(2*(x*y+w*z)),(float)(2*(x*z-w*y)),0,
                (float)(2*(x*y-w*z)),(float)(1-2*(x*x+z*z)),(float)(2*(y*z+w*x)),0,
                (float)(2*(x*z+w*y)),(float)(2*(y*z-w*x)),(float)(1-2*(x*x+y*y)),0,0,0,0,1};
        }
        private static double dot(double[] a,double[] b){double sum=0;for(int i=0;i<4;i++)sum+=a[i]*b[i];return sum;}
        private static double[] unit(double[] source){
            if(source==null||source.length!=4)throw new IllegalArgumentException("Quaternion needs four finite elements");
            double[] copy=source.clone();double norm=0;for(double value:copy){if(!Double.isFinite(value))throw new IllegalArgumentException("Nonfinite quaternion");norm+=value*value;}
            norm=Math.sqrt(norm);if(!Double.isFinite(norm)||norm<1e-12)throw new IllegalArgumentException("Degenerate quaternion");
            for(int i=0;i<4;i++)copy[i]/=norm;return copy;
        }
    }
    private static double[] quaternion(float[] m){
        double w,x,y,z,trace=(double)m[0]+m[5]+m[10];
        if(trace>0){double s=Math.sqrt(trace+1)*2;w=.25*s;x=(m[6]-m[9])/s;y=(m[8]-m[2])/s;z=(m[1]-m[4])/s;}
        else if(m[0]>m[5]&&m[0]>m[10]){double s=Math.sqrt(1.0+m[0]-m[5]-m[10])*2;w=(m[6]-m[9])/s;x=.25*s;y=(m[4]+m[1])/s;z=(m[8]+m[2])/s;}
        else if(m[5]>m[10]){double s=Math.sqrt(1.0+m[5]-m[0]-m[10])*2;w=(m[8]-m[2])/s;x=(m[4]+m[1])/s;y=.25*s;z=(m[9]+m[6])/s;}
        else{double s=Math.sqrt(1.0+m[10]-m[0]-m[5])*2;w=(m[1]-m[4])/s;x=(m[8]+m[2])/s;y=(m[9]+m[6])/s;z=.25*s;}
        return RotationMean.unit(new double[]{w,x,y,z});
    }
}
