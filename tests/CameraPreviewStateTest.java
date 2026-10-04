package com.mirror.bench;

public final class CameraPreviewStateTest {
    private static int checks;
    public static void main(String[] args) {
        CameraPreviewSession gate=new CameraPreviewSession();
        var first=gate.begin(0);
        check(gate.accepts(first),"loading owner");
        gate.poll(29_999_999_999L);check(gate.accepts(first),"before load deadline");
        gate.poll(30_000_000_000L);check(!gate.accepts(first)&&gate.state()==CameraPreviewSession.State.ERROR,"load deadline revokes owner");
        check(!gate.loaded(first,30_000_000_001L),"late load rejected");
        var second=gate.begin(31_000_000_000L);
        check(!gate.loaded(first,31_000_000_000L),"old generation cannot publish");
        check(gate.loaded(second,32_000_000_000L),"current load");
        gate.poll(52_000_000_000L);check(gate.state()==CameraPreviewSession.State.ERROR,"GL deadline");
        var third=gate.begin(53_000_000_000L);gate.loaded(third,54_000_000_000L);
        check(gate.rendered(third,55_000_000_000L),"real submitted frame ready");
        gate.contextRecreated(third,55_100_000_000L);check(gate.state()==CameraPreviewSession.State.WAITING_FOR_GL,"context recreation needs a new successful frame");
        check(gate.rendered(third,55_200_000_000L),"new context ready");
        gate.pause();check(!gate.accepts(third)&&!gate.rendered(third,56_000_000_000L),"pause revokes late GL");
        var fourth=gate.begin(57_000_000_000L);gate.fail(third,"old failure");check(gate.accepts(fourth),"old error cannot poison new owner");
        gate.close();check(!gate.accepts(fourth),"close revokes");
        rejects(()->gate.begin(58_000_000_000L),"closed cannot resume");

        CameraPreviewPose pose=new CameraPreviewPose();float[] weights=new float[52],matrix=rotation(12,-23,7);
        weights[9]=.8f;weights[10]=.2f;weights[25]=.6f;
        pose.offer(weights,matrix,true,0,0);weights[9]=0;matrix[0]=0;
        float[] actual=new float[52],angles=new float[3];pose.sample(100_000_000L,actual,angles);
        near(actual[9],.8,1e-7,"left independent and copied");near(actual[10],.2,1e-7,"right independent");
        double alpha=1-Math.exp(-(1d/30)/.10);
        near(angles[0],12*alpha,1e-5,"actual pitch extracted");near(angles[1],-23*alpha,1e-5,"actual yaw extracted");near(angles[2],7*alpha,1e-5,"actual roll extracted");
        pose.offer(new float[52],FaceFrame.identity(),false,200_000_000L,200_000_000L);
        pose.sample(300_000_000L,actual,angles);check(actual[9]>.0&&actual[9]<.8,"loss fades instead of frozen expression");
        pose.sample(3_000_000_000L,actual,angles);check(actual[9]<.0001,"loss settles");
        weights[25]=1;pose.offer(weights,rotation(80,-85,70),true,3_000_000_000L,3_000_000_000L);
        pose.sample(3_100_000_000L,actual,angles);check(angles[0]<=45&&angles[1]>=-65&&angles[2]<=40,"same runtime angular caps");
        // Continuously reposting an obsolete snapshot must not refresh its face receipt age.
        pose.offer(weights,rotation(12,5,3),true,3_000_000_000L,5_000_000_000L);
        pose.sample(5_000_000_000L,actual,angles);check(actual[25]<.001,"old snapshot cannot drive");
        float[] bad=FaceFrame.identity();bad[0]=-1;
        rejects(()->pose.offer(new float[52],bad,true,6_000_000_000L,6_000_000_000L),"reflection rejected");
        int[] viewport=CameraPreviewPose.viewport(1000,800);check(viewport[2]==500&&viewport[3]==800&&viewport[0]==250,"10:16 letterbox landscape box");
        viewport=CameraPreviewPose.viewport(400,900);check(viewport[2]==400&&viewport[3]==640&&viewport[1]==130,"10:16 letterbox tall box");
        System.out.println("CameraPreviewStateTest: "+checks+" checks passed");
    }
    private static float[] rotation(double x,double y,double z){double cx=Math.cos(Math.toRadians(x)),sx=Math.sin(Math.toRadians(x)),cy=Math.cos(Math.toRadians(y)),sy=Math.sin(Math.toRadians(y)),cz=Math.cos(Math.toRadians(z)),sz=Math.sin(Math.toRadians(z));return new float[]{(float)(cz*cy),(float)(sz*cy),(float)-sy,0,(float)(cz*sy*sx-sz*cx),(float)(sz*sy*sx+cz*cx),(float)(cy*sx),0,(float)(cz*sy*cx+sz*sx),(float)(sz*sy*cx-cz*sx),(float)(cy*cx),0,0,0,0,1};}
    private static void near(double a,double b,double tolerance,String message){check(Math.abs(a-b)<=tolerance,message);}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable action,String message){try{action.run();throw new AssertionError(message);}catch(IllegalArgumentException|IllegalStateException expected){checks++;}}
}
