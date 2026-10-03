package com.mirror.bench;

/** Compare accepted real-pose math to the freshly compiled production renderer, not another oracle copy. */
public final class CameraPreviewPoseParityTest {
    public static void main(String[] args)throws Exception{
        Class<?> nested=Class.forName("com.mirror.bench.InterlaceRenderer$InteractiveFace");
        var rotation=nested.getDeclaredMethod("readRotation",float[].class,float[].class);rotation.setAccessible(true);
        java.util.Random random=new java.util.Random(91351);int comparisons=0;
        for(int sample=0;sample<1000;sample++){
            double x=(random.nextDouble()-.5)*Math.PI*2,y=(random.nextDouble()-.5)*Math.PI,z=(random.nextDouble()-.5)*Math.PI*2;
            double cx=Math.cos(x),sx=Math.sin(x),cy=Math.cos(y),sy=Math.sin(y),cz=Math.cos(z),sz=Math.sin(z);
            float[] pose={(float)(cz*cy),(float)(sz*cy),(float)-sy,0,(float)(cz*sy*sx-sz*cx),(float)(sz*sy*sx+cz*cx),(float)(cy*sx),0,(float)(cz*sy*cx+sz*sx),(float)(sz*sy*cx-cz*sx),(float)(cy*cx),0,1,2,3,1};
            for(int col=0;col<3;col++){float scale=.1f+random.nextFloat()*5;for(int row=0;row<3;row++)pose[col*4+row]*=scale;}
            float[] expected=new float[3];rotation.invoke(null,pose,expected);
            float[] actual=CameraPreviewPose.readRotation(pose);
            for(int i=0;i<3;i++){comparisons++;if(Float.floatToRawIntBits(expected[i])!=Float.floatToRawIntBits(actual[i]))throw new AssertionError("Pose parity "+sample+" axis "+i);}
        }
        System.out.println("CameraPreviewPoseParityTest: "+comparisons+" bitwise accepted-angle comparisons passed");
    }
}
