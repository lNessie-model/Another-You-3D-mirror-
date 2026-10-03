package com.mirror.bench;

import java.util.Arrays;
import java.util.Random;

/** Hand-derived rotations/directions, independent matrix reference, malformed inputs and ownership. */
public final class FaceControlMapperTest {
    private static int checks;
    public static void main(String[] args) {
        defaultsAndOwnership();nonCommutingNeutral();explicitPermutation();mirrorRotation();poseValidation();
        System.out.println("FaceControlMapperTest: "+checks+" assertions passed");
    }
    private static void defaultsAndOwnership(){
        float[] weights=new float[52];for(int i=0;i<52;i++)weights[i]=i/51f;weights[0]=-0.0f;
        float[] pose=identity();var defaults=FaceControlCalibration.defaults();
        var result=FaceControlMapper.mapActive(weights,pose,defaults);
        check(result.revision()==0,"default revision");sameBits(weights,result.blendshapes52(),"identity preserves every raw weight including negative zero");
        near(identity(),result.pose(),0,"identity rotation");
        weights[9]=.71f;pose[0]=12;
        check(result.blendshapes52()[9]!=.71f&&result.pose()[0]==1,"mapped output owns input arrays");
        float[] exposed=result.blendshapes52();exposed[9]=.9f;float[] exposedPose=result.pose();exposedPose[0]=5;
        check(result.blendshapes52()[9]!=.9f&&result.pose()[0]==1,"getters do not expose mapped storage");
        float[] reference=rotation(0,25,0);var configured=new FaceControlCalibration(7,true,reference,null);reference[0]=0;
        float[] referenceCopy=configured.neutralPose();referenceCopy[0]=0;
        check(configured.neutralPose()[0]>.9f,"configuration owns neutral input and getter arrays");
        var neutral=FaceControlMapper.neutral(configured);
        near(identity(),neutral.pose(),0,"non-drive neutral bypasses nonidentity R0 and mirror");
        near(new float[52],neutral.blendshapes52(),0,"non-drive expression neutral");check(neutral.revision()==7,"neutral reports the same complete config revision");
        rejects(()->FaceControlMapper.mapActive(null,identity(),defaults),"missing weights");
        rejects(()->FaceControlMapper.mapActive(new float[51],identity(),defaults),"missing channel");
        rejects(()->FaceControlMapper.mapActive(new float[53],identity(),defaults),"extra channel");
        for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-.01f,1.01f}){
            float[] bad=new float[52];bad[51]=invalid;rejects(()->FaceControlMapper.mapActive(bad,identity(),defaults),"invalid weight "+invalid);
        }
        rejects(()->new FaceControlCalibration(-1,false,null,null),"negative revision");
        rejects(()->FaceControlMapper.mapActive(new float[52],identity(),null),"missing atomic config");
    }
    private static void nonCommutingNeutral(){
        // Exact quarter turns: R0=Rx(90), Delta=Ry(90). Wrong Rraw*R0^T gives Rz(90).
        float[] base={1,0,0,0, 0,0,1,0, 0,-1,0,0, 0,0,0,1};
        float[] delta={0,0,-1,0, 0,1,0,0, 1,0,0,0, 0,0,0,1};
        float[] raw=product(base,delta);var calibration=new FaceControlCalibration(1,false,base,null);
        near(delta,FaceControlMapper.mapActive(new float[52],raw,calibration).pose(),1e-6,"R0 transpose is multiplied on the left");
        check(maxDifference(product(raw,transpose(base)),delta)>0.9,"fixture detects the reversed product");
        float[] r0=rotation(-8,17,11),d=rotation(12,-23,7);calibration=new FaceControlCalibration(2,false,r0,null);
        near(d,FaceControlMapper.mapActive(new float[52],product(r0,d),calibration).pose(),2e-6,"compound neutral local rotation");
        near(identity(),FaceControlMapper.mapActive(new float[52],r0,calibration).pose(),2e-6,"neutral sample maps to identity");
        Random random=new Random(301);
        for(int n=0;n<100;n++){
            r0=rotation(random.nextDouble()*100-50,random.nextDouble()*100-50,random.nextDouble()*100-50);
            d=rotation(random.nextDouble()*100-50,random.nextDouble()*100-50,random.nextDouble()*100-50);
            calibration=new FaceControlCalibration(n,false,r0,null);
            near(d,FaceControlMapper.mapActive(new float[52],product(r0,d),calibration).pose(),2e-6,"independent compound rotation "+n);
        }
    }
    private static void explicitPermutation(){
        String[][] pairs={{"browDownLeft","browDownRight"},{"browOuterUpLeft","browOuterUpRight"},
            {"cheekSquintLeft","cheekSquintRight"},{"eyeBlinkLeft","eyeBlinkRight"},{"eyeLookDownLeft","eyeLookDownRight"},
            {"eyeLookInLeft","eyeLookInRight"},{"eyeLookOutLeft","eyeLookOutRight"},{"eyeLookUpLeft","eyeLookUpRight"},
            {"eyeSquintLeft","eyeSquintRight"},{"eyeWideLeft","eyeWideRight"},{"jawLeft","jawRight"},
            {"mouthDimpleLeft","mouthDimpleRight"},{"mouthFrownLeft","mouthFrownRight"},{"mouthLeft","mouthRight"},
            {"mouthLowerDownLeft","mouthLowerDownRight"},{"mouthPressLeft","mouthPressRight"},{"mouthSmileLeft","mouthSmileRight"},
            {"mouthStretchLeft","mouthStretchRight"},{"mouthUpperUpLeft","mouthUpperUpRight"},{"noseSneerLeft","noseSneerRight"}};
        int[] expected=new int[52];for(int i=0;i<52;i++)expected[i]=i;
        for(String[] pair:pairs){int a=BlendshapeSchema.indexOf(pair[0]),b=BlendshapeSchema.indexOf(pair[1]);expected[a]=b;expected[b]=a;}
        int[] actual=FaceControlMapper.mirrorPermutation();check(Arrays.equals(expected,actual),"all 20 semantic pairs and 12 fixed points match explicit names");
        boolean[] destinations=new boolean[52];int fixed=0;
        var config=new FaceControlCalibration(3,true,null,null);
        for(int source=0;source<52;source++){
            check(!destinations[actual[source]],"bijection destination "+source);destinations[actual[source]]=true;
            check(actual[actual[source]]==source,"permutation is involution "+source);if(actual[source]==source)fixed++;
            float[] oneHot=new float[52];oneHot[source]=1;
            float[] out=FaceControlMapper.mapActive(oneHot,identity(),config).blendshapes52();
            check(out[expected[source]]==1&&sum(out)==1,"one-hot preserves full channel "+source);
            sameBits(oneHot,FaceControlMapper.mapActive(out,identity(),config).blendshapes52(),"twice mirrored "+source);
        }
        check(fixed==12,"exactly 12 midline inputs remain fixed");actual[0]=20;
        check(FaceControlMapper.mirrorPermutation()[0]==0,"permutation getter owns copy");
        float[] eyes=new float[52];eyes[15]=1;eyes[14]=.4f;eyes[17]=.3f;eyes[12]=.8f;
        float[] mirrored=FaceControlMapper.mapActive(eyes,identity(),config).blendshapes52();
        check(mirrored[16]==1&&mirrored[13]==.4f&&mirrored[18]==.3f&&mirrored[11]==.8f,"mirror swaps eyes, preserves In/Out and Up/Down names");
        check((mirrored[15]-mirrored[13])==-(eyes[14]-eyes[16])&&
            (mirrored[14]-mirrored[16])==-(eyes[15]-eyes[13]),"joint gaze yaw matches reflected opposite eye");
    }
    private static void mirrorRotation(){
        var config=new FaceControlCalibration(4,true,null,null);
        for(float[] pose:new float[][]{rotation(20,0,0),rotation(0,30,0),rotation(0,0,40),rotation(12,-23,7)}){
            float[] expected=pose.clone();for(int row=0;row<3;row++)for(int col=0;col<3;col++)if((row==0)!=(col==0))expected[col*4+row]=-expected[col*4+row];
            float[] actual=FaceControlMapper.mapActive(new float[52],pose,config).pose();near(expected,actual,1e-6,"SRS hand sign map");
            near(pose,FaceControlMapper.mapActive(new float[52],actual,config).pose(),1e-6,"mirror rotation involution");
            check(Math.abs(determinant(actual)-1)<1e-6,"reflection conjugation retains proper rotation");
        }
        near(rotation(20,0,0),FaceControlMapper.mapActive(new float[52],rotation(20,0,0),config).pose(),1e-6,"mirror retains pitch");
        near(rotation(0,-30,0),FaceControlMapper.mapActive(new float[52],rotation(0,30,0),config).pose(),1e-6,"mirror negates yaw");
        near(rotation(0,0,-40),FaceControlMapper.mapActive(new float[52],rotation(0,0,40),config).pose(),1e-6,"mirror negates roll");
    }
    private static void poseValidation(){
        var config=FaceControlCalibration.defaults();
        float[] r=rotation(12,-23,7),scaled=r.clone();for(int c=0;c<3;c++)for(int row=0;row<3;row++)scaled[c*4+row]*=2.5f;
        scaled[12]=23;scaled[13]=-7;scaled[14]=180;
        sameBits(scaled,FaceControlMapper.mapActive(new float[52],scaled,config).pose(),"identity mapping preserves legacy pose including translation and scale");
        near(r,FaceControlMapper.rotationPose(scaled),1e-6,"collector rotation removes positive scale and translation");
        float[] drift=identity();drift[4]=.04f;drift[3]=.0005f;drift[15]=1.0005f;
        sameBits(drift,FaceControlMapper.mapActive(new float[52],drift,config).pose(),"legacy accepted affine/numerical drift is not newly rejected or silently changed by defaults");
        float[] repaired=FaceControlMapper.mapActive(new float[52],drift,new FaceControlCalibration(1,true,null,null)).pose();
        near(identity(),repaired,1e-6,"actual correction repairs accepted axis drift to a proper rotation");
        for(float scale:new float[]{.000002f,100000f}){float[] valid=identity();valid[0]=valid[5]=valid[10]=scale;near(identity(),FaceControlMapper.rotationPose(valid),0,"renderer-compatible positive scale "+scale);}
        rejects(()->FaceControlMapper.mapActive(new float[52],null,config),"missing pose");
        rejects(()->FaceControlMapper.mapActive(new float[52],new float[15],config),"pose shape");
        for(int index=0;index<16;index++){
            float[] invalid=identity();invalid[index]=Float.NaN;rejectsPose(invalid,"NaN at "+index);
            invalid=identity();invalid[index]=Float.POSITIVE_INFINITY;rejectsPose(invalid,"infinity at "+index);
        }
        for(float scale:new float[]{0,-1,1e-8f,1e8f}){float[] bad=identity();bad[0]=bad[5]=bad[10]=scale;rejectsPose(bad,"invalid scale "+scale);}
        float[] bad=identity();bad[0]=2;
        near(identity(),FaceControlMapper.rotationPose(bad),0,"positive per-column scale keeps renderer compatibility");
        bad=identity();bad[0]=-1;rejectsPose(bad,"reflected basis");
        bad=identity();bad[4]=.1f;rejectsPose(bad,"excessively sheared basis");
        bad=identity();bad[4]=1;bad[5]=0;rejectsPose(bad,"parallel basis");
        float[] twoSigns=identity();twoSigns[0]=-1;twoSigns[5]=-1;
        near(twoSigns,FaceControlMapper.rotationPose(twoSigns),0,"two basis sign changes are a proper 180 degree rotation, not reflection");
        for(int index:new int[]{3,7,11,15}){bad=identity();bad[index]+=.01f;rejectsPose(bad,"nonaffine bottom row");}
        final float[] corrupt=bad;rejects(()->new FaceControlCalibration(0,false,corrupt,null),"invalid reference pose");
    }
    private static void rejectsPose(float[] pose,String name){rejects(()->FaceControlMapper.mapActive(new float[52],pose,FaceControlCalibration.defaults()),name);}
    private static float sum(float[] values){float result=0;for(float f:values)result+=f;return result;}
    static float[] identity(){return new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};}
    static float[] rotation(double x,double y,double z){
        double cx=Math.cos(Math.toRadians(x)),sx=Math.sin(Math.toRadians(x)),cy=Math.cos(Math.toRadians(y)),sy=Math.sin(Math.toRadians(y)),cz=Math.cos(Math.toRadians(z)),sz=Math.sin(Math.toRadians(z));
        // Independent explicit Rz Ry Rx entries, rather than the production rotation extractor.
        return new float[]{(float)(cz*cy),(float)(sz*cy),(float)-sy,0,
            (float)(cz*sy*sx-sz*cx),(float)(sz*sy*sx+cz*cx),(float)(cy*sx),0,
            (float)(cz*sy*cx+sz*sx),(float)(sz*sy*cx-cz*sx),(float)(cy*cx),0,0,0,0,1};
    }
    static float[] product(float[] a,float[] b){float[] out=new float[16];for(int r=0;r<4;r++)for(int c=0;c<4;c++){double v=0;for(int k=0;k<4;k++)v+=(double)a[k*4+r]*b[c*4+k];out[c*4+r]=(float)v;}return out;}
    static float[] transpose(float[] m){float[] out=new float[16];for(int r=0;r<4;r++)for(int c=0;c<4;c++)out[c*4+r]=m[r*4+c];return out;}
    static double determinant(float[] a){return a[0]*((double)a[5]*a[10]-(double)a[9]*a[6])-a[4]*((double)a[1]*a[10]-(double)a[9]*a[2])+a[8]*((double)a[1]*a[6]-(double)a[5]*a[2]);}
    static double maxDifference(float[] a,float[] b){double max=0;for(int i=0;i<a.length;i++)max=Math.max(max,Math.abs((double)a[i]-b[i]));return max;}
    static void near(float[] expected,float[] actual,double tolerance,String name){check(expected.length==actual.length&&maxDifference(expected,actual)<=tolerance,name);}
    static void sameBits(float[] expected,float[] actual,String name){boolean same=expected.length==actual.length;for(int i=0;same&&i<expected.length;i++)same=Float.floatToRawIntBits(expected[i])==Float.floatToRawIntBits(actual[i]);check(same,name);}
    static void check(boolean condition,String name){checks++;if(!condition)throw new AssertionError(name);}
    static void rejects(Runnable action,String name){try{action.run();throw new AssertionError("Accepted "+name);}catch(IllegalArgumentException expected){checks++;}}
}
