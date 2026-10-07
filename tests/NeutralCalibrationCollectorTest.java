package com.mirror.bench;

/** Real immutable FaceFrames and controlled monotonic time; no sleeps or Android mocks. */
public final class NeutralCalibrationCollectorTest {
    private static final long MS=1_000_000L,START=1_000*MS;
    private static int checks;
    public static void main(String[] args){
        stableCollectionAndOwnership();independentSamplesAndBound();invalidAndUnrelaxedFrames();
        gapsMotionAndTimestampOrder();cancelTimeoutAndSessions();timestampEdgesAndClockOwnership();
        subSampleQualityAndRotationBranches();
        personalBrowNeutral();
        System.out.println("NeutralCalibrationCollectorTest: "+checks+" assertions passed");
    }
    private static void stableCollectionAndOwnership(){
        var c=new NeutralCalibrationCollector(true);var session=c.begin(START);
        float[] raw=relaxed(),pose=rotation(-8,17,11);var first=frame(0,START,raw,pose);
        c.offer(session,first,START+20*MS);
        for(int i=1;i<40;i++)check(c.offer(session,frame(i,START+i*50*MS,raw,pose),START+i*50*MS+20*MS).status()==NeutralCalibrationCollector.Status.COLLECTING,"not ready before full two seconds");
        var update=c.offer(session,frame(40,START+2000*MS,raw,pose),START+2020*MS);
        check(update.status()==NeutralCalibrationCollector.Status.READY&&update.samples()==41,"41 independent observations complete stable window");
        var result=update.result();check(result!=null&&c.isCurrentReady(session,result),"ready result requires matching live collector owner");
        check(result.firstSequence()==0&&result.lastSequence()==40&&result.firstReceivedNs()==START&&result.lastReceivedNs()==START+2000*MS,"result identifies raw source interval");
        near(pose,result.neutralPose(),1e-6,"constant noncommuting Euler pose recovered from quaternion mean");
        var baseline=result.personalBaseline();
        close(.1,baseline.blinkLeft(),1e-7,"left blink mean");close(.12,baseline.blinkRight(),1e-7,"right blink mean");close(.05,baseline.jawOpen(),1e-7,"jaw mean");
        close(.1,baseline.leftOut(),1e-7,"left outward mean");close(-.1,baseline.rightOut(),1e-7,"right outward mean");
        close(.05,baseline.leftUp(),1e-7,"left upward mean");close(-.08,baseline.rightUp(),1e-7,"right upward mean");
        float[] exposed=result.neutralPose();exposed[0]=99;raw[9]=1;pose[0]=99;
        check(result.neutralPose()[0]<1&&result.personalBaseline().blinkLeft()<.2f,"result arrays and baseline own immutable storage");
        check(first.blendshapes()[9]==.1f&&first.pose()[0]<1,"original inference result not modified");
        var after=c.offer(session,FaceFrame.absent(41,START+2050*MS,START+2070*MS),START+2070*MS);
        check(after.result()==result&&after.status()==NeutralCalibrationCollector.Status.READY,"READY is a frozen draft; later face loss does not silently rewrite it");
        check(c.poll(session,START+100000*MS).result()==result,"collection deadline does not expire a completed draft");
        c.cancel(session);check(!c.isCurrentReady(session,result)&&c.poll(session,START+100001*MS).result()==null,"explicit cancel revokes completed result for confirmation");
        var poseOnly=new NeutralCalibrationCollector(false);var owner=poseOnly.begin(START);
        var ready=feed(poseOnly,owner,START,0,41,50,relaxed(),rotation(0,0,0));
        check(ready.result().personalBaseline()==null,"personal correction remains explicit opt-in");
    }
    private static void independentSamplesAndBound(){
        var c=new NeutralCalibrationCollector(false);var owner=c.begin(START);var raw=frame(1,START,relaxed(),rotation(0,0,0));
        c.offer(owner,raw,START+20*MS);
        for(int i=21;i<=200;i++)check(c.offer(owner,raw,START+i*MS).samples()==1,"same FaceFrame cannot accumulate samples");
        check(c.poll(owner,START+251*MS).samples()==0,"poll resets continuous window after input gap");
        c=new NeutralCalibrationCollector(false);owner=c.begin(START);
        var sparse=feed(c,owner,START,0,9,250,relaxed(),rotation(0,0,0));
        check(sparse.status()==NeutralCalibrationCollector.Status.COLLECTING&&sparse.samples()==9,"duration alone cannot replace independent sample count");
        var dense=new NeutralCalibrationCollector(false);var token=dense.begin(START);
        NeutralCalibrationCollector.Update result=null;
        for(int i=0;i<=2000;i++){
            result=dense.offer(token,frame(i,START+i*MS,relaxed(),rotation(0,0,0)),START+i*MS+20*MS);
            check(result.samples()<=NeutralCalibrationCollector.MAX_SAMPLES,"subsampled statistics stay bounded");
        }
        check(result.status()==NeutralCalibrationCollector.Status.READY&&result.samples()==41,"all new frames checked but at most one per 50ms counted");
    }
    private static void invalidAndUnrelaxedFrames(){
        expectReset(FaceFrame.absent(100,START+500*MS,START+520*MS),START+520*MS,NeutralCalibrationCollector.Reason.NO_FACE);
        float[] bad=relaxed();bad[51]=-.01f;expectReset(frame(100,START+500*MS,bad,rotation(0,0,0)),START+520*MS,NeutralCalibrationCollector.Reason.INVALID_WEIGHTS);
        float[] pose=rotation(0,0,0);pose[0]=-1;expectReset(frame(100,START+500*MS,relaxed(),pose),START+520*MS,NeutralCalibrationCollector.Reason.INVALID_POSE);
        pose=rotation(0,0,0);pose[4]=.2f;expectReset(frame(100,START+500*MS,relaxed(),pose),START+520*MS,NeutralCalibrationCollector.Reason.INVALID_POSE);
        for(int index:new int[]{9,10}){bad=relaxed();bad[index]=.26f;expectReset(frame(100,START+500*MS,bad,rotation(0,0,0)),START+520*MS,NeutralCalibrationCollector.Reason.EYES_NOT_RELAXED);}
        for(int index:new int[]{25,32,38,44,45}){bad=relaxed();bad[index]=.31f;expectReset(frame(100,START+500*MS,bad,rotation(0,0,0)),START+520*MS,NeutralCalibrationCollector.Reason.MOUTH_NOT_RELAXED);}
        bad=relaxed();bad[15]=.5f;expectReset(frame(100,START+500*MS,bad,rotation(0,0,0)),START+520*MS,NeutralCalibrationCollector.Reason.GAZE_NOT_CENTERED);
        bad=relaxed();bad[13]=bad[15]=.6f;expectReset(frame(100,START+500*MS,bad,rotation(0,0,0)),START+520*MS,NeutralCalibrationCollector.Reason.GAZE_NOT_CENTERED);
        expectReset(frame(100,START+500*MS,relaxed(),rotation(0,0,0)),START+751*MS,NeutralCalibrationCollector.Reason.STALE_FRAME);
        expectReset(frame(100,START+500*MS,relaxed(),rotation(0,0,0)),START+519*MS,NeutralCalibrationCollector.Reason.FUTURE_FRAME);
        var c=new NeutralCalibrationCollector(false);var owner=c.begin(START);
        check(c.offer(owner,null,START).reason()==NeutralCalibrationCollector.Reason.INVALID_FRAME,"null input diagnosed without throwing to caller");
    }
    private static void gapsMotionAndTimestampOrder(){
        var c=new NeutralCalibrationCollector(true);var owner=c.begin(START);
        feed(c,owner,START,0,10,50,relaxed(),rotation(0,0,0));
        var gap=c.offer(owner,frame(11,START+751*MS,relaxed(),rotation(0,0,0)),START+771*MS);
        check(gap.reason()==NeutralCalibrationCollector.Reason.FRAME_GAP&&gap.samples()==1,"large gap begins a new window rather than completing previous one");
        var moved=c.offer(owner,frame(12,START+801*MS,relaxed(),rotation(0,5,0)),START+821*MS);
        check(moved.reason()==NeutralCalibrationCollector.Reason.HEAD_MOVING&&moved.samples()==1,"head motion discards old window and anchors current valid pose");
        float[] changed=relaxed();changed[25]=.19f;
        var changedUpdate=c.offer(owner,frame(13,START+851*MS,changed,rotation(0,5,0)),START+871*MS);
        check(changedUpdate.reason()==NeutralCalibrationCollector.Reason.COEFFICIENT_CHANGED&&changedUpdate.samples()==1,"within-rest but unstable bias resets accumulation");
        var regressed=c.offer(owner,newFrame(14,START+850*MS,START+890*MS,changed,rotation(0,5,0)),START+900*MS);
        check(regressed.reason()==NeutralCalibrationCollector.Reason.NON_MONOTONIC_FRAME&&regressed.samples()==0,"new sequence with old receipt is rejected");
        c.offer(owner,newFrame(15,START+901*MS,START+960*MS,changed,rotation(0,5,0)),START+980*MS);
        var completeBack=c.offer(owner,newFrame(16,START+920*MS,START+950*MS,changed,rotation(0,5,0)),START+990*MS);
        check(completeBack.reason()==NeutralCalibrationCollector.Reason.NON_MONOTONIC_FRAME&&completeBack.samples()==0,"new sequence with old completion is rejected");
        var backwardsClock=c.poll(owner,START+900*MS);
        check(backwardsClock.reason()==NeutralCalibrationCollector.Reason.NON_MONOTONIC_CLOCK&&backwardsClock.samples()==0,"backward caller time cannot create a fresh window");
        var before=new NeutralCalibrationCollector(false);var token=before.begin(START);
        var old=before.offer(token,frame(1,START-50*MS,relaxed(),rotation(0,0,0)),START);
        check(old.reason()==NeutralCalibrationCollector.Reason.BEFORE_COLLECTION&&old.samples()==0,"in-flight pre-click observation cannot seed calibration");
    }
    private static void cancelTimeoutAndSessions(){
        var c=new NeutralCalibrationCollector(true);var first=c.begin(START);
        c.offer(first,frame(0,START,relaxed(),rotation(0,0,0)),START+20*MS);c.cancel(first);
        check(c.offer(first,frame(1,START+50*MS,relaxed(),rotation(0,0,0)),START+70*MS).status()==NeutralCalibrationCollector.Status.CANCELLED,"cancelled producer cannot resume collection");
        var second=c.begin(START+100*MS);
        check(c.poll(first,Long.MAX_VALUE).status()==NeutralCalibrationCollector.Status.STALE_SESSION,"old-session poll cannot timeout new owner");
        c.cancel(first);
        check(c.offer(second,frame(2,START+100*MS,relaxed(),rotation(0,0,0)),START+120*MS).samples()==1,"old cancel cannot cancel new owner");
        check(c.offer(first,frame(500,START+200*MS,relaxed(),rotation(0,0,0)),START+220*MS).status()==NeutralCalibrationCollector.Status.STALE_SESSION,"late old-session callback rejected");
        check(c.poll(second,START+100*MS+NeutralCalibrationCollector.TOTAL_TIMEOUT_NS-1).status()==NeutralCalibrationCollector.Status.COLLECTING,"deadline has precise before-boundary behavior");
        check(c.poll(second,START+100*MS+NeutralCalibrationCollector.TOTAL_TIMEOUT_NS).status()==NeutralCalibrationCollector.Status.TIMED_OUT,"total deadline is not extended by resets");
        var late=c.offer(second,frame(600,START+20200*MS,relaxed(),rotation(0,0,0)),START+20220*MS);
        check(late.status()==NeutralCalibrationCollector.Status.TIMED_OUT&&late.result()==null,"no late success after timeout");
        var third=c.begin(START+21000*MS);
        var ready=feed(c,third,START+21000*MS,0,41,50,relaxed(),rotation(0,0,0)).result();
        var fourth=c.begin(START+25000*MS);
        check(!c.isCurrentReady(third,ready)&&!c.isCurrentReady(fourth,ready),"new begin invalidates old ready result identity");
    }
    private static void timestampEdgesAndClockOwnership(){
        var c=new NeutralCalibrationCollector();var old=c.begin(START);var owner=c.begin(START);
        check(c.poll(old,()->{throw new AssertionError("Obsolete callback sampled clock");}).status()==NeutralCalibrationCollector.Status.STALE_SESSION,"obsolete poll does not even sample current-session clock");
        check(c.offer(old,frame(99,START,relaxed(),rotation(0,0,0)),()->{throw new AssertionError("Obsolete producer sampled clock");}).status()==NeutralCalibrationCollector.Status.STALE_SESSION,"obsolete producer cannot influence time");
        check(c.offer(owner,frame(0,START,relaxed(),rotation(0,0,0)),()->START+250*MS).samples()==1,"freshness accepts exact inclusive boundary");
        for(int i=1;i<30;i++)check(c.offer(owner,frame(i,START,relaxed(),rotation(0,0,0)),START+250*MS).samples()==1,"new sequence with equal timestamps cannot manufacture elapsed samples");
        var future=frame(30,START+500*MS,relaxed(),rotation(0,0,0));
        check(c.offer(owner,future,START+500*MS).reason()==NeutralCalibrationCollector.Reason.FUTURE_FRAME,"completed timestamp cannot lie in future");
        var retry=c.offer(owner,future,START+520*MS);
        check(retry.reason()==NeutralCalibrationCollector.Reason.DUPLICATE_OR_OLD_SEQUENCE&&retry.samples()==0,"rejected future sequence cannot be retried later as new input");
        check(c.offer(owner,frame(31,START+550*MS,relaxed(),rotation(0,0,0)),START+570*MS).samples()==1,"next genuinely new result can restart");
        long time=START+600*MS;
        while(time<START+NeutralCalibrationCollector.TOTAL_TIMEOUT_NS){
            c.offer(owner,FaceFrame.absent(time, time, time),time);time+=200*MS;
        }
        check(c.poll(owner,START+NeutralCalibrationCollector.TOTAL_TIMEOUT_NS).status()==NeutralCalibrationCollector.Status.TIMED_OUT,"repeated invalid observations never extend overall deadline");
    }
    private static void subSampleQualityAndRotationBranches(){
        var c=new NeutralCalibrationCollector();var owner=c.begin(START);
        c.offer(owner,frame(0,START,relaxed(),rotation(0,0,0)),START+20*MS);
        float[] closed=relaxed();closed[9]=.9f;
        var update=c.offer(owner,frame(1,START+10*MS,closed,rotation(0,0,0)),START+30*MS);
        check(update.reason()==NeutralCalibrationCollector.Reason.EYES_NOT_RELAXED&&update.samples()==0,"blink between statistical sample instants still breaks neutral continuity");
        for(int axis=0;axis<3;axis++){
            c=new NeutralCalibrationCollector();owner=c.begin(START);
            for(int i=0;i<=40;i++){
                double angle=i==0?180:(i%2==0?-179:179);
                float[] pose=rotation(axis==0?angle:0,axis==1?angle:0,axis==2?angle:0);
                update=c.offer(owner,frame(i,START+i*50*MS,relaxed(),pose),START+i*50*MS+20*MS);
            }
            check(update.status()==NeutralCalibrationCollector.Status.READY,"near-180 converter branch keeps a stable full collection on axis "+axis);
            near(rotation(axis==0?180:0,axis==1?180:0,axis==2?180:0),update.result().neutralPose(),1e-6,"sign boundary mean preserves 180 degree rotation on axis "+axis);
        }
        c=new NeutralCalibrationCollector();owner=c.begin(START);float[] reference=rotation(-8,17,11);
        for(int i=0;i<=40;i++){
            float[] delta=rotation(i==0?0:(i%2==0?-1:1),0,0);
            update=c.offer(owner,frame(i,START+i*50*MS,relaxed(),multiply(reference,delta)),START+i*50*MS+20*MS);
        }
        check(update.status()==NeutralCalibrationCollector.Status.READY,"small local motion around compound pose remains stable");
        near(reference,update.result().neutralPose(),1e-6,"noncommuting compound rotations average in rotation space");
    }
    private static float[] multiply(float[] a,float[] b){float[] out=new float[16];for(int col=0;col<4;col++)for(int row=0;row<4;row++){double value=0;for(int k=0;k<4;k++)value+=(double)a[k*4+row]*b[col*4+k];out[col*4+row]=(float)value;}return out;}
    private static void personalBrowNeutral(){
        var collector=new NeutralCalibrationCollector(true);var owner=collector.begin(START);
        float[] resting=relaxed();resting[1]=.22f;resting[2]=.31f;
        var ready=feed(collector,owner,START,0,41,50,resting,rotation(0,0,0));
        check(ready.status()==NeutralCalibrationCollector.Status.READY,"explicit stable resting-brow sample is ready");
        var calibration=new FaceControlCalibration(52,false,ready.result().neutralPose(),ready.result().personalBaseline());
        float[] mapped=FaceControlMapper.mapActive(resting,rotation(0,0,0),calibration).blendshapes52();
        near(new float[]{0,0},new float[]{mapped[1],mapped[2]},1e-7,"sampled resting brow bias must not continue pressing either brow down");
        close(.22,ready.result().personalBaseline().browDownLeft(),1e-7,"left brow uses the same complete stable source interval");
        close(.31,ready.result().personalBaseline().browDownRight(),1e-7,"right brow has its own stable mean");
        float[] frown=resting.clone();frown[1]=frown[2]=1;
        mapped=FaceControlMapper.mapActive(frown,rotation(0,0,0),calibration).blendshapes52();
        check(mapped[1]==1&&mapped[2]==1,"neutral collection does not suppress intentional frown endpoints");
        var firstResult=ready.result();collector.cancel(owner);
        check(!collector.isCurrentReady(owner,firstResult),"cancel revokes brow correction draft with the other baseline channels");
        var nextOwner=collector.begin(START+3000*MS);float[] other=relaxed();other[1]=.11f;other[2]=.17f;
        check(collector.offer(owner,frame(999,START+3000*MS,resting,rotation(0,0,0)),START+3020*MS).status()==NeutralCalibrationCollector.Status.STALE_SESSION,"obsolete owner cannot inject former person's brow observations");
        ready=feed(collector,nextOwner,START+3000*MS,0,41,50,other,rotation(0,0,0));
        close(.11,ready.result().personalBaseline().browDownLeft(),1e-7,"new session has no old brow mean");
        close(.17,ready.result().personalBaseline().browDownRight(),1e-7,"new session has no old asymmetric brow mean");
        for(int side:new int[]{1,2}){
            collector=new NeutralCalibrationCollector(true);owner=collector.begin(START);
            collector.offer(owner,frame(0,START,resting,rotation(0,0,0)),START+20*MS);
            float[] moved=resting.clone();moved[side]+=.09f;
            var update=collector.offer(owner,frame(1,START+10*MS,moved,rotation(0,0,0)),START+30*MS);
            check(update.reason()==NeutralCalibrationCollector.Reason.COEFFICIENT_CHANGED&&update.samples()==1&&update.result()==null,"brow change between statistical samples breaks the full personal window on side "+side);
            ready=feed(collector,owner,START+60*MS,2,40,50,moved,rotation(0,0,0));
            check(ready.status()==NeutralCalibrationCollector.Status.READY&&ready.result().firstSequence()==1&&ready.result().lastSequence()==41,"only the new continuous resting-brow interval produces READY");
            close(moved[side],side==1?ready.result().personalBaseline().browDownLeft():ready.result().personalBaseline().browDownRight(),1e-7,"discarded brow observations do not contaminate new baseline");
            float[] unsafe=moved.clone();unsafe[side]=.50001f;
            collector=new NeutralCalibrationCollector(true);owner=collector.begin(START);
            var rejected=collector.offer(owner,frame(0,START,unsafe,rotation(0,0,0)),START+20*MS);
            check(rejected.reason()==NeutralCalibrationCollector.Reason.BROWS_NOT_RELAXED&&rejected.samples()==0&&rejected.result()==null,"gain-unsafe brow neutral is refused on side "+side);
        }
        collector=new NeutralCalibrationCollector(false);owner=collector.begin(START);
        for(int i=0;i<=40;i++){
            float[] varied=relaxed();varied[1]=i%2;varied[2]=1-varied[1];
            ready=collector.offer(owner,frame(i,START+i*50*MS,varied,rotation(0,0,0)),START+i*50*MS+20*MS);
        }
        check(ready.status()==NeutralCalibrationCollector.Status.READY&&ready.result().personalBaseline()==null,"head-only collection keeps prior acceptance and never learns brows automatically");
    }
    private static void expectReset(FaceFrame frame,long now,NeutralCalibrationCollector.Reason reason){
        var c=new NeutralCalibrationCollector(true);var token=c.begin(START);feed(c,token,START,0,10,50,relaxed(),rotation(0,0,0));
        var out=c.offer(token,frame,now);check(out.status()==NeutralCalibrationCollector.Status.COLLECTING&&out.samples()==0&&out.reason()==reason,"rejected frame resets: "+reason+" actual="+out.reason());
    }
    private static NeutralCalibrationCollector.Update feed(NeutralCalibrationCollector c,NeutralCalibrationCollector.Session token,long start,long seq,int count,long stepMs,float[] weights,float[] pose){NeutralCalibrationCollector.Update result=null;for(int i=0;i<count;i++)result=c.offer(token,frame(seq+i,start+i*stepMs*MS,weights,pose),start+i*stepMs*MS+20*MS);return result;}
    private static FaceFrame frame(long seq,long received,float[] weights,float[] pose){return newFrame(seq,received,received+20*MS,weights,pose);}
    private static FaceFrame newFrame(long seq,long received,long completed,float[] weights,float[] pose){return FaceFrame.present(seq,received,completed,weights,pose);}
    private static float[] relaxed(){float[] w=new float[52];w[9]=.1f;w[10]=.12f;w[25]=.05f;w[15]=.1f;w[14]=.1f;w[17]=.05f;w[12]=.08f;return w;}
    static float[] rotation(double x,double y,double z){double cx=Math.cos(Math.toRadians(x)),sx=Math.sin(Math.toRadians(x)),cy=Math.cos(Math.toRadians(y)),sy=Math.sin(Math.toRadians(y)),cz=Math.cos(Math.toRadians(z)),sz=Math.sin(Math.toRadians(z));return new float[]{(float)(cz*cy),(float)(sz*cy),(float)-sy,0,(float)(cz*sy*sx-sz*cx),(float)(sz*sy*sx+cz*cx),(float)(cy*sx),0,(float)(cz*sy*cx+sz*sx),(float)(sz*sy*cx-cz*sx),(float)(cy*cx),0,0,0,0,1};}
    static void near(float[] expected,float[] actual,double tolerance,String message){boolean okay=expected.length==actual.length;for(int i=0;okay&&i<expected.length;i++)okay=Math.abs((double)expected[i]-actual[i])<=tolerance;check(okay,message);}
    private static void close(double expected,double actual,double tolerance,String message){check(Math.abs(expected-actual)<=tolerance,message);}
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
