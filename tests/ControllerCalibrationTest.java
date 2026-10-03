package com.mirror.bench;

public final class ControllerCalibrationTest {
    private static int checks;private static final long MS=1_000_000L;
    public static void main(String[] args){
        defaultEquivalence();mappedAndCacheInvalidation();invalidFramesAndRecovery();nonDriveNeverRecalibrates();
        System.out.println("ControllerCalibrationTest: "+checks+" checks passed");
    }
    private static void defaultEquivalence(){
        var a=new InteractionController();var b=new InteractionController();b.setCalibration(FaceControlCalibration.defaults());
        float[] legacySmoothed=new float[52];
        float legacyAlpha=(float)-Math.expm1(-100_000_000.0/70_000_000.0);
        for(int i=0;i<15;i++){
            float[] w=new float[52];for(int n=0;n<52;n++)w[n]=(i+n)%11/10f;
            float[] pose=rotation(4,15,-7);pose[12]=3;for(int j=0;j<3;j++)pose[j]*=2;
            FaceFrame f=FaceFrame.present(i,i*100*MS,i*100*MS,w,pose);a.accept(f);b.accept(f);
            var x=a.sample(i*100*MS);var y=b.sample(i*100*MS);
            bits(x.blendshapes52(),y.blendshapes52(),"default mapped 52 keeps exact existing arithmetic");bits(x.pose(),y.pose(),"default raw pose bits preserved");
            // Frozen valid-input behavior of the pre-calibration controller: acquire at 200 ms,
            // then raw per-channel targets with its existing 70 ms exponential interpolation.
            if(i>=2)for(int n=0;n<52;n++){legacySmoothed[n]+=(w[n]-legacySmoothed[n])*legacyAlpha;if(Math.abs(legacySmoothed[n])<.00001f)legacySmoothed[n]=0;}
            bits(legacySmoothed,x.blendshapes52(),"independent legacy trajectory stays bitwise unchanged");
            bits(i>=2?pose:FaceFrame.identity(),x.pose(),"legacy active raw pose / inactive identity contract");
        }
    }
    private static void mappedAndCacheInvalidation(){
        var c=new InteractionController();float[] ref=rotation(-8,17,11),delta=rotation(12,-23,7);
        float[] raw=new float[52];raw[9]=.1f;raw[10]=.8f;raw[25]=.3f;
        c.setCalibration(new FaceControlCalibration(5,false,ref,null));
        FaceFrame current=active(c,raw,multiply(ref,delta));
        var initial=c.sample(350*MS);near(delta,initial.pose(),1e-6,"controller uses R0 transpose times raw, not wrong order");
        check(initial.calibrationRevision()==5&&c.calibration().revision()==5,"configuration identity visible");
        var other=new FaceControlCalibration(5,true,ref,null);c.setCalibration(other);
        var switched=c.sample(450*MS);float[] expected=FaceControlMapper.mapActive(raw,current.pose(),other).pose();
        near(expected,switched.pose(),1e-6,"same revision different immutable object invalidates map cache");
        check(switched.blendshapes52()[9]>initial.blendshapes52()[9],"mirror changes target on unchanged source frame");
        float before=switched.blendshapes52()[9];c.setCalibration(other);var again=c.sample(480*MS);
        check(again.blendshapes52()[9]>before,"repeating setter keeps per-tick smoothing instead of resetting weights");
        c.reset();check(c.calibration()==other&&c.sample(0).calibrationRevision()==5,"input reset preserves explicitly installed immutable configuration");
        rejects(()->c.setCalibration(null),"null config");
    }
    private static void invalidFramesAndRecovery(){
        var c=new InteractionController();float[] raw=new float[52];raw[25]=1;active(c,raw,FaceFrame.identity());
        float[] bad=FaceFrame.identity();bad[0]=-1;
        c.accept(FaceFrame.present(2,350*MS,350*MS,raw,bad));var rejected=c.sample(350*MS);
        check(rejected.state()==InteractionController.State.GRACE&&!rejected.facePresent(),"bad pose becomes lost-face fade, never UI exception or active face");
        check(rejected.resultAgeMs()==50,"rejected result cannot refresh last accepted raw face age");
        check(!rejected.calibrationError().isEmpty()&&rejected.calibrationRejectedFrames()==1,"rejection is bounded and visible");
        near(FaceFrame.identity(),rejected.pose(),0,"bad pose cannot reach renderer");
        c.sample(400*MS);check(c.sample(450*MS).calibrationRejectedFrames()==1,"same invalid observation not counted every tick");
        c.accept(FaceFrame.present(3,500*MS,500*MS,raw,FaceFrame.identity()));var recovered=c.sample(500*MS);
        check(recovered.facePresent()&&recovered.state()==InteractionController.State.INTERACTIVE&&recovered.calibrationError().isEmpty(),"next valid observation recovers in grace");
        check(recovered.calibrationRejectedFrames()==1,"recovery preserves rejection count");
        bad=FaceFrame.identity();bad[4]=.2f;c.accept(FaceFrame.present(4,550*MS,550*MS,raw,bad));c.sample(550*MS);
        c.accept(FaceFrame.present(5,540*MS,560*MS,raw,FaceFrame.identity()));
        check(!c.sample(560*MS).facePresent(),"new sequence cannot bypass rejected observation timestamp high-water mark");
        float[] outOfRange=raw.clone();outOfRange[51]=1.01f;c.accept(FaceFrame.present(6,600*MS,600*MS,outOfRange,FaceFrame.identity()));
        check(c.sample(600*MS).calibrationRejectedFrames()==3,"out-of-range raw52 rejected atomically before drive");
        c.reset();check(c.sample(0).calibrationRejectedFrames()==0&&c.sample(0).calibrationError().isEmpty(),"new input session clears diagnostic state");
        c.accept(FaceFrame.present(0,0,0,raw,bad));var acquiring=c.sample(0);
        check(!acquiring.facePresent()&&acquiring.state()==InteractionController.State.WAITING,"invalid first pose cannot acquire face");
    }
    private static void nonDriveNeverRecalibrates(){
        var c=new InteractionController();var baseline=new FaceControlCalibration.PersonalBaseline(.1f,.2f,.1f,.2f,-.1f,.1f,.1f);
        c.setCalibration(new FaceControlCalibration(7,true,rotation(-8,17,11),baseline));
        var waiting=c.sample(0);near(FaceFrame.identity(),waiting.pose(),0,"WAITING identity is already mapped neutral");
        float[] raw=new float[52];raw[25]=.5f;active(c,raw,rotation(-8,17,11));
        c.accept(FaceFrame.absent(2,400*MS,400*MS));var grace=c.sample(400*MS);
        near(FaceFrame.identity(),grace.pose(),0,"GRACE identity not rotated by neutral reference");
        c.setError();var error=c.sample(500*MS);near(FaceFrame.identity(),error.pose(),0,"ERROR never recalibrates synthetic neutral");
        check(error.calibrationRevision()==7,"non-drive snapshot retains configuration identity");
    }
    private static FaceFrame active(InteractionController c,float[] w,float[] p){var first=FaceFrame.present(0,0,0,w,p);c.accept(first);c.sample(0);var next=FaceFrame.present(1,300*MS,300*MS,w,p);c.accept(next);c.sample(300*MS);return next;}
    private static float[] rotation(double x,double y,double z){double cx=Math.cos(Math.toRadians(x)),sx=Math.sin(Math.toRadians(x)),cy=Math.cos(Math.toRadians(y)),sy=Math.sin(Math.toRadians(y)),cz=Math.cos(Math.toRadians(z)),sz=Math.sin(Math.toRadians(z));return new float[]{(float)(cz*cy),(float)(sz*cy),(float)-sy,0,(float)(cz*sy*sx-sz*cx),(float)(sz*sy*sx+cz*cx),(float)(cy*sx),0,(float)(cz*sy*cx+sz*sx),(float)(sz*sy*cx-cz*sx),(float)(cy*cx),0,0,0,0,1};}
    private static float[] multiply(float[] a,float[] b){float[] out=new float[16];for(int col=0;col<4;col++)for(int row=0;row<4;row++)for(int k=0;k<4;k++)out[col*4+row]+=a[k*4+row]*b[col*4+k];return out;}
    private static void bits(float[] a,float[] b,String m){check(a.length==b.length,m);for(int i=0;i<a.length;i++)check(Float.floatToRawIntBits(a[i])==Float.floatToRawIntBits(b[i]),m+" at "+i);}
    private static void near(float[] a,float[] b,double tolerance,String m){for(int i=0;i<a.length;i++)check(Math.abs((double)a[i]-b[i])<=tolerance,m+" at "+i);}
    private static void rejects(Runnable r,String m){try{r.run();throw new AssertionError(m);}catch(IllegalArgumentException expected){checks++;}}
    private static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
}
