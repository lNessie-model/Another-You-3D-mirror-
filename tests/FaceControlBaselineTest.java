package com.mirror.bench;

import java.util.concurrent.atomic.AtomicReference;

/** Session-only baseline endpoints, independent anatomical directions and atomic snapshot ownership. */
public final class FaceControlBaselineTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        unipolarEndpoints();signedGaze();baselineBeforeMirror();invalidBaselines();atomicOwnership();browBaselines();
        System.out.println("FaceControlBaselineTest: "+checks+" assertions passed");
    }
    private static void unipolarEndpoints(){
        var b=baseline(.2f,.4f,.1f,0,0,0,0);var c=new FaceControlCalibration(12,false,null,b);
        float[] raw=new float[52];for(int i=0;i<52;i++)raw[i]=.13f;raw[9]=.6f;raw[10]=.7f;raw[25]=.55f;
        float[] out=FaceControlMapper.mapActive(raw,id(),c).blendshapes52();
        near(.5,out[9],1e-7,"left blink midpoint with .2 baseline");near(.5,out[10],1e-7,"right blink independent .4 baseline");
        near(.5,out[25],1e-7,"jaw midpoint with .1 baseline");
        for(int i=0;i<52;i++)if(i!=9&&i!=10&&i!=25)check(bits(raw[i])==bits(out[i]),"baseline does not alter unrelated source "+i);
        raw[9]=.2f;raw[10]=.4f;raw[25]=.1f;
        out=FaceControlMapper.mapActive(raw,id(),c).blendshapes52();check(out[9]==0&&out[10]==0&&out[25]==0,"sampled unipolar neutral maps to zero");
        raw[9]=raw[10]=raw[25]=0;out=FaceControlMapper.mapActive(raw,id(),c).blendshapes52();check(out[9]==0&&out[10]==0&&out[25]==0,"below baseline clamps to neutral");
        raw[9]=raw[10]=raw[25]=raw[27]=1;
        out=FaceControlMapper.mapActive(raw,id(),c).blendshapes52();check(out[9]==1&&out[10]==1&&out[25]==1,"full blink and jaw remain reachable");
        check(out[27]==1&&out[25]*out[27]==1,"jawOpen and mouthClose corrective coexist, jaw is not cleared");
        var zero=new FaceControlCalibration(13,false,null,baseline(0,0,0,0,0,0,0));
        raw[0]=-0.0f;raw[11]=.3f;raw[17]=.7f;raw[13]=.8f;raw[15]=.9f;
        float[] pose=FaceControlMapperTest.rotation(-8,17,11);
        var exact=FaceControlMapper.mapActive(raw,pose,zero);
        check(equalBits(raw,exact.blendshapes52())&&equalBits(pose,exact.pose()),"all-zero optional baseline is bitwise identity including coactive gaze");
    }
    private static void signedGaze(){
        // Positive direction is Out or Up for each anatomical eye, regardless of world yaw sign.
        for(int[] pair:new int[][]{{15,13},{16,14},{17,11},{18,12}}){
            for(float b:new float[]{-.5f,-.2f,.2f,.5f}){
                float[] refs={0,0,0,0};int slot=pair[0]==15?0:pair[0]==16?1:pair[0]==17?2:3;refs[slot]=b;
                var c=new FaceControlCalibration(1,false,null,baseline(0,0,0,refs[0],refs[1],refs[2],refs[3]));
                for(int sign:new int[]{-1,1}){
                    float[] raw=new float[52];raw[sign>0?pair[0]:pair[1]]=1;
                    float[] out=FaceControlMapper.mapActive(raw,id(),c).blendshapes52();
                    check(out[pair[0]]-out[pair[1]]==sign,"both gaze endpoints retained "+pair[0]+"/"+b+"/"+sign);
                }
                float[] raw=new float[52];raw[b>0?pair[0]:pair[1]]=Math.abs(b);
                float[] out=FaceControlMapper.mapActive(raw,id(),c).blendshapes52();
                near(0,out[pair[0]]-out[pair[1]],1e-7,"per-eye signed neutral "+pair[0]);
                for(int step=0;step<=100;step++){
                    double d=-1+step*.02;raw=new float[52];raw[pair[0]]=(float)Math.max(d,0);raw[pair[1]]=(float)Math.max(-d,0);
                    out=FaceControlMapper.mapActive(raw,id(),c).blendshapes52();
                    double expected=(d-b)/(d>=b?1-b:1+b);
                    near(expected,(double)out[pair[0]]-out[pair[1]],2e-7,"signed endpoint-preserving response");
                    float p=out[pair[0]],n=out[pair[1]];
                    check(Float.isFinite(p)&&p>=0&&p<=1&&Float.isFinite(n)&&n>=0&&n<=1,"bounded finite mapped pair");
                }
            }
        }
        var c=new FaceControlCalibration(1,false,null,baseline(0,0,0,.5f,0,0,0));
        float[] raw=new float[52];raw[15]=.9f;raw[13]=.8f;
        float[] out=FaceControlMapper.mapActive(raw,id(),c).blendshapes52();
        near(11.0/15,out[15],1e-7,"high common component clipped only to fit endpoint");near(1,out[13],0,"reconstruction does not exceed one");
        raw[15]=.75f;raw[13]=.25f;out=FaceControlMapper.mapActive(raw,id(),c).blendshapes52();
        check(out[15]==.25f&&out[13]==.25f,"neutralizes direction without erasing shared morph activation");
    }
    private static void baselineBeforeMirror(){
        var b=baseline(.1f,.3f,.2f,.25f,-.5f,.1f,-.2f);
        float[] raw=new float[52];raw[9]=.1f;raw[10]=.3f;raw[25]=.2f;
        raw[15]=.25f;raw[14]=.5f;raw[17]=.1f;raw[12]=.2f;
        var c=new FaceControlCalibration(55,true,null,b);
        float[] neutral=FaceControlMapper.mapActive(raw,id(),c).blendshapes52();
        for(int index:new int[]{9,10,25,11,12,13,14,15,16,17,18})near(0,neutral[index],1e-7,"asymmetric baselines removed before eye exchange");
        raw[9]=1;raw[15]=1;raw[13]=0;
        float[] out=FaceControlMapper.mapActive(raw,id(),c).blendshapes52();
        check(out[10]==1&&out[9]==0&&out[16]==1&&out[14]==0,"left full blink/out maps to right full blink/out");
        float[] r0=FaceControlMapperTest.rotation(-8,17,11),delta=FaceControlMapperTest.rotation(12,-23,7);
        c=new FaceControlCalibration(56,true,r0,b);
        float[] reflected=delta.clone();for(int row=0;row<3;row++)for(int col=0;col<3;col++)if((row==0)!=(col==0))reflected[col*4+row]=-reflected[col*4+row];
        var mapped=FaceControlMapper.mapActive(raw,FaceControlMapperTest.product(r0,delta),c);
        check(FaceControlMapperTest.maxDifference(reflected,mapped.pose())<2e-6,"head reference applies before reflection on compound rotation");
        check(mapped.revision()==56&&mapped.blendshapes52()[10]==1,"same revision drives both pose and expression");
        check(equalBits(id(),FaceControlMapper.neutral(c).pose()),"failed/no-face output never calibrates synthetic identity");
    }
    private static void invalidBaselines(){
        for(int index=0;index<7;index++)for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,1,-1,.50001f,-.50001f}){
            float[] a=new float[7];a[index]=invalid;
            rejects(()->baseline(a[0],a[1],a[2],a[3],a[4],a[5],a[6]),"invalid/near-singular baseline "+index);
        }
        for(int index=0;index<3;index++){float[] a=new float[7];a[index]=-.001f;rejects(()->baseline(a[0],a[1],a[2],0,0,0,0),"negative unipolar baseline");}
        var b=baseline(.5f,.5f,.5f,-.5f,.5f,-.5f,.5f);
        check(b.blinkLeft()==.5f&&b.blinkRight()==.5f&&b.jawOpen()==.5f&&b.leftOut()==-.5f&&b.rightOut()==.5f&&b.leftUp()==-.5f&&b.rightUp()==.5f,"finite gain-bound endpoints accepted without hidden normalization");
    }
    private static void atomicOwnership()throws Exception {
        float[] raw=new float[52];raw[9]=.75f;float[] pose=FaceControlMapperTest.rotation(0,30,0);
        var a=new FaceControlCalibration(100,false,null,null);var b=new FaceControlCalibration(200,true,pose,null);
        AtomicReference<FaceControlCalibration> current=new AtomicReference<>(a);
        Thread swapper=new Thread(()->{for(int i=0;i<10000;i++)current.set((i&1)==0?a:b);});swapper.start();
        for(int i=0;i<1000;i++){
            // Integration contract: acquire the configuration exactly once for this entire result.
            FaceControlCalibration snapshot=current.get();var mapped=FaceControlMapper.mapActive(raw,pose,snapshot);
            if(mapped.revision()==100)check(mapped.blendshapes52()[9]==.75f&&equalBits(pose,mapped.pose()),"atomic original revision");
            else check(mapped.revision()==200&&mapped.blendshapes52()[10]==.75f&&FaceControlMapperTest.maxDifference(id(),mapped.pose())<1e-6,"atomic mirrored calibrated revision");
        }
        swapper.join(5000);check(!swapper.isAlive(),"bounded host publisher completes");
        float[] before=raw.clone(),invalidPose=pose.clone();invalidPose[10]=Float.NaN;
        var previous=FaceControlMapper.mapActive(raw,pose,a);
        rejects(()->FaceControlMapper.mapActive(raw,invalidPose,b),"pose failure after valid weights");
        check(equalBits(raw,before)&&equalBits(previous.blendshapes52(),before)&&equalBits(previous.pose(),pose),"failed transaction mutates neither caller nor earlier output");
    }
    private static void browBaselines(){
        var legacy=baseline(.1f,.2f,.05f,.1f,-.1f,.05f,-.08f);
        check(legacy.browDownLeft()==0&&legacy.browDownRight()==0,"seven-value baseline never enables brow correction");
        var extended=new FaceControlCalibration.PersonalBaseline(.1f,.2f,.05f,.1f,-.1f,.05f,-.08f,.2f,.4f);
        float[] raw=new float[52];for(int i=0;i<52;i++)raw[i]=i/51f;raw[0]=-0.0f;raw[1]=.6f;raw[2]=.7f;
        var oldConfig=new FaceControlCalibration(51,false,null,legacy);
        var newConfig=new FaceControlCalibration(52,false,null,extended);
        float[] old=FaceControlMapper.mapActive(raw,id(),oldConfig).blendshapes52();
        float[] out=FaceControlMapper.mapActive(raw,id(),newConfig).blendshapes52();
        near(.5,out[1],1e-7,"left brow halfway from its own resting value to frown endpoint");
        near(.5,out[2],1e-7,"right brow uses separate anatomical resting value");
        for(int i=0;i<52;i++)if(i!=1&&i!=2)check(bits(old[i])==bits(out[i]),"brow calibration preserves all other eye, mouth and brow-up channels "+i);
        raw[1]=.2f;raw[2]=.4f;out=FaceControlMapper.mapActive(raw,id(),newConfig).blendshapes52();
        check(out[1]==0&&out[2]==0,"both sampled resting brows become neutral");
        raw[1]=.1f;raw[2]=0;out=FaceControlMapper.mapActive(raw,id(),newConfig).blendshapes52();
        check(out[1]==0&&out[2]==0,"values below sampled rest do not produce negative brow deformation");
        float previousLeft=-1,previousRight=-1;
        for(int step=0;step<=100;step++){
            raw[1]=raw[2]=step/100f;out=FaceControlMapper.mapActive(raw,id(),newConfig).blendshapes52();
            check(out[1]>=previousLeft&&out[2]>=previousRight&&out[1]>=0&&out[2]>=0&&out[1]<=1&&out[2]<=1,"brow response remains bounded and monotonic "+step);
            previousLeft=out[1];previousRight=out[2];
        }
        check(previousLeft==1&&previousRight==1,"deliberate full frown remains exactly reachable on both sides");
        raw[1]=.6f;raw[2]=.4f;
        out=FaceControlMapper.mapActive(raw,id(),new FaceControlCalibration(52,true,null,extended)).blendshapes52();
        near(.5,out[2],1e-7,"anatomical left rest is removed before left-to-right mirror");near(0,out[1],0,"right rest remains neutral after mirror");
        check(equalBits(new float[52],FaceControlMapper.neutral(newConfig).blendshapes52()),"no-face neutral never receives brow bias");
        var zeroBrows=new FaceControlCalibration.PersonalBaseline(.1f,.2f,.05f,.1f,-.1f,.05f,-.08f,0,0);
        check(equalBits(FaceControlMapper.mapActive(raw,id(),oldConfig).blendshapes52(),FaceControlMapper.mapActive(raw,id(),new FaceControlCalibration(52,false,null,zeroBrows)).blendshapes52()),"legacy and explicit zero-brow baseline have identical complete outputs");
        for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-.001f,.50001f,1}){
            rejects(()->new FaceControlCalibration.PersonalBaseline(0,0,0,0,0,0,0,invalid,0),"unsafe left brow baseline");
            rejects(()->new FaceControlCalibration.PersonalBaseline(0,0,0,0,0,0,0,0,invalid),"unsafe right brow baseline");
        }
        var bound=new FaceControlCalibration.PersonalBaseline(0,0,0,0,0,0,0,.5f,.5f);
        check(bound.browDownLeft()==.5f&&bound.browDownRight()==.5f,"engineering gain cap accepts its exact finite boundary");
    }
    private static FaceControlCalibration.PersonalBaseline baseline(float bl,float br,float jaw,float lo,float ro,float lu,float ru){return new FaceControlCalibration.PersonalBaseline(bl,br,jaw,lo,ro,lu,ru);}
    private static float[] id(){return FaceControlMapperTest.identity();}
    private static int bits(float value){return Float.floatToRawIntBits(value);}
    private static boolean equalBits(float[] a,float[] b){if(a.length!=b.length)return false;for(int i=0;i<a.length;i++)if(bits(a[i])!=bits(b[i]))return false;return true;}
    private static void near(double expected,double actual,double tolerance,String name){check(Math.abs(expected-actual)<=tolerance,name+" expected="+expected+" actual="+actual);}
    private static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    private static void rejects(Runnable action,String name){try{action.run();throw new AssertionError("Accepted "+name);}catch(IllegalArgumentException expected){checks++;}}
}
