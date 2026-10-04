package com.mirror.bench;

import java.util.Map;

/** Physical projection requirements, independent of a UI slider or GL context. */
public final class SceneViewSettingsTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static void near(double actual,double expected,String why){check(Math.abs(actual-expected)<2e-5,why);}
    private static void rejects(Runnable task){try{task.run();throw new AssertionError("invalid scene accepted");}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] args){
        var original=SceneViewSettings.DEFAULT;
        check(original.isIdentityTransform(),"neutral model transform");
        check(original.cameraSpan==.4f&&original.zeroPlane==0,"original optical camera geometry");
        for(int count:new int[]{1,16,20,32})for(int index=0;index<count;index++){
            float eye=original.eyeAt(index,count);
            float reference=count==1?0:(index/(float)(count-1)-.5f)*.4f;
            check(Float.floatToRawIntBits(eye)==Float.floatToRawIntBits(reference),"default eye expression retained");
        }
        // A point on the selected convergence plane has identical horizontal position in every view.
        // A point in front of or behind it has disparity of opposite sign; zero spacing is monoscopic.
        for(float span:new float[]{0,.2f,.4f,1.2f})for(float zero:new float[]{-1.5f,0,.8f,1.5f}){
            var value=original.withValue(7,span).withValue(8,zero);
            for(int count:new int[]{16,20}){
                for(int index=0;index<count;index++){
                    double eye=value.eyeAt(index,count),shift=value.frustumShift((float)eye);
                    double z=zero,x=.2,near=.1,half=.052,aspect=.625;
                    double ndc=((x-eye)*near/(3-z)-shift)/(half*aspect);
                    near(ndc,x*near/((3-zero)*half*aspect),"zero plane has zero parallax");
                }
                double left=value.eyeAt(0,count),right=value.eyeAt(count-1,count);
                for(float z:new float[]{zero-.3f,zero+.3f}){
                    double difference=((.2-right)*.1/(3-z)-value.frustumShift((float)right))
                            -((.2-left)*.1/(3-z)-value.frustumShift((float)left));
                    if(span==0)near(difference,0,"zero spacing is mono");
                    else check(z>zero?difference<0:difference>0,"depth disparity changes sign at convergence");
                }
            }
        }
        var edited=original.withValue(0,1.65f).withValue(1,.25f).withValue(2,-.2f)
            .withValue(3,.5f).withValue(4,20).withValue(5,-30).withValue(6,45)
            .withValue(7,.65f).withValue(8,.4f).withBackground(4);
        var decoded=SceneViewSettings.fromMap(edited.toMap());
        for(int i=0;i<9;i++)check(Float.floatToRawIntBits(decoded.value(i))==Float.floatToRawIntBits(edited.value(i)),"numeric roundtrip");
        check(decoded.background==4&&!edited.isIdentityTransform(),"background and transform retained");
        check(original.scale==1&&original.offsetX==0&&original.background==0,"draft never mutates original");
        float[] matrix=new float[16];
        original.withValue(0,2).withValue(1,.5f).withValue(2,-.5f).withValue(3,.4f).copyUserTransform(.625f,matrix);
        near(matrix[0],2,"scale in actual transform");near(matrix[5],2,"uniform scale");
        near(matrix[12],.4875,"horizontal offset follows screen aspect");near(matrix[13],-.78,"vertical offset");near(matrix[14],.4,"positive depth moves toward viewer");
        original.withValue(5,90).copyUserTransform(.625f,matrix);
        near(matrix[0],0,"90 degree yaw sends X out of screen plane");near(matrix[2],-1,"yaw handedness");near(matrix[8],1,"front rotates toward right");
        // Rotation remains orthonormal even when all three user axes are combined.
        edited.copyUserTransform(.625f,matrix);
        for(int a=0;a<3;a++)for(int b=0;b<3;b++){
            double dot=0;for(int r=0;r<3;r++)dot+=matrix[a*4+r]*matrix[b*4+r];
            near(dot,a==b?edited.scale*edited.scale:0,"rotation preserves shape and uniform scale");
        }
        for(int index=0;index<9;index++){
            final int field=index;rejects(()->original.withValue(field,Float.NaN));rejects(()->original.withValue(field,Float.POSITIVE_INFINITY));
        }
        rejects(()->original.withValue(0,0));rejects(()->original.withValue(7,-.1f));rejects(()->original.withValue(8,3));
        for(int background=0;background<12;background++)check(SceneViewSettings.fromMap(original.withBackground(background).toMap()).background==background,"all procedural and image choices persist");
        rejects(()->original.withBackground(-1));rejects(()->original.withBackground(12));
        rejects(()->original.eyeAt(16,16));rejects(()->original.eyeAt(0,0));
        rejects(()->SceneViewSettings.fromMap(Map.of("schema_version",99)));
        check(SceneViewSettings.fromMap(Map.of()).isIdentityTransform(),"empty installation reads defaults without persistence");
        System.out.println("SceneViewSettingsTest: "+checks+" checks passed; physical projection and immutable settings");
    }
}
