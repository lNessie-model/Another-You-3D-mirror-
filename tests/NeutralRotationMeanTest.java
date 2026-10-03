package com.mirror.bench;

/** Quaternion sign ambiguity and orthogonal output fixtures independent of frame gating. */
public final class NeutralRotationMeanTest {
    private static int checks;
    public static void main(String[] args){
        var mean=new NeutralCalibrationCollector.RotationMean();
        double half=Math.toRadians(37)/2;double[] q={Math.cos(half),0,Math.sin(half),0};
        mean.add(q);mean.add(new double[]{-q[0],0,-q[2],0});q[0]=999;
        near(NeutralCalibrationCollectorTest.rotation(0,37,0),mean.pose(),1e-6,"q and -q cannot cancel and input is owned");
        mean=new NeutralCalibrationCollector.RotationMean();mean.add(new double[]{1,0,0,0});
        half=Math.toRadians(4)/2;mean.add(new double[]{-Math.cos(half),-Math.sin(half),0,0});
        float[] actual=mean.pose();near(NeutralCalibrationCollectorTest.rotation(2,0,0),actual,1e-6,"sign-aligned mean is angular midpoint");
        for(int a=0;a<3;a++)for(int b=0;b<3;b++){double dot=0;for(int row=0;row<3;row++)dot+=(double)actual[a*4+row]*actual[b*4+row];check(Math.abs(dot-(a==b?1:0))<1e-6,"mean output remains orthogonal");}
        double determinant=actual[0]*((double)actual[5]*actual[10]-(double)actual[9]*actual[6])-actual[4]*((double)actual[1]*actual[10]-(double)actual[9]*actual[2])+actual[8]*((double)actual[1]*actual[6]-(double)actual[5]*actual[2]);
        check(Math.abs(determinant-1)<1e-6,"mean is proper rotation rather than elementwise matrix average");
        mean=new NeutralCalibrationCollector.RotationMean();
        double a=Math.toRadians(179)/2,b=Math.toRadians(-179)/2;mean.add(new double[]{Math.cos(a),0,0,Math.sin(a)});mean.add(new double[]{Math.cos(b),0,0,Math.sin(b)});
        near(NeutralCalibrationCollectorTest.rotation(0,0,180),mean.pose(),1e-6,"near-180 sign boundary does not average to zero degrees");
        final var bad=new NeutralCalibrationCollector.RotationMean();rejects(()->bad.add(new double[]{0,0,0,0}),"zero quaternion");rejects(()->bad.add(new double[]{Double.NaN,0,0,1}),"nonfinite quaternion");
        for(int i=0;i<NeutralCalibrationCollector.MAX_SAMPLES;i++)bad.add(new double[]{1,0,0,0});rejects(()->bad.add(new double[]{1,0,0,0}),"bounded rotation sample count");
        System.out.println("NeutralRotationMeanTest: "+checks+" assertions passed");
    }
    private static void near(float[] expected,float[] actual,double tolerance,String message){boolean okay=expected.length==actual.length;for(int i=0;okay&&i<expected.length;i++)okay=Math.abs((double)expected[i]-actual[i])<=tolerance;check(okay,message);}
    private static void rejects(Runnable action,String message){try{action.run();throw new AssertionError("Accepted "+message);}catch(IllegalArgumentException expected){checks++;}}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
