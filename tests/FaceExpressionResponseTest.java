package com.mirror.bench;

/** User-observed brow conflict and incomplete eye closure; exercise production mapping. */
public final class FaceExpressionResponseTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static float[] mapped(float[] input,boolean mirror,float gain){
        float[] output=new float[52];FacePlayback.apply(input,new float[3],mirror,gain,output,new float[3]);return output;
    }
    public static void main(String[] args){
        float[] input=new float[52];input[1]=input[2]=.08f;
        float[] out=mapped(input,false,2.5f);
        check(out[1]==0&&out[2]==0,"weak resting brow-down evidence must not create a perpetual frown");
        input[1]=input[2]=.7f;input[3]=.25f;out=mapped(input,false,2.5f);
        check(out[1]<.3f&&out[2]<.3f&&out[3]>.45f,"inner-brow lift must suppress conflicting brow-down channels");
        input[3]=1;out=mapped(input,false,2.5f);check(out[1]==0&&out[2]==0&&out[3]==1,"full lift wins an opposing brow signal");
        input=new float[52];input[9]=input[10]=.72f;input[21]=input[22]=.45f;out=mapped(input,false,2.5f);
        check(out[9]==1&&out[10]==1,"strong observed closure must reach the authored closed-eye endpoint");
        check(out[21]>.6f&&out[22]>.6f&&out[21]<1,"widening is more readable without prematurely reaching its endpoint");
        input[9]=.2f;out=mapped(input,true,4);check(out[10]<.18f,"small blinks must not close the eye too early");
        input[15]=.6f;input[19]=.4f;input[27]=.3f;out=mapped(input,true,4);
        check(out[16]==.6f&&out[20]==.4f&&out[27]==.3f,"gaze, squint and mouth-close remain independent of expression gain");
        for(int channel:new int[]{9,10,21,22}){
            float previous=-1;
            for(int step=0;step<=100;step++){
                input=new float[52];input[channel]=step/100f;out=mapped(input,false,2.5f);
                check(out[channel]>=previous&&out[channel]>=0&&out[channel]<=1,"monotone bounded eye response");previous=out[channel];
            }
        }
        int[] reflection=FaceControlMapper.mirrorPermutation();input=new float[52];float[] reflected=new float[52];
        for(int i=0;i<52;i++)input[i]=((i*7)%31)/30f;
        for(int i=0;i<52;i++)reflected[i]=input[reflection[i]];
        float[] a=mapped(input,true,2.5f),b=mapped(reflected,false,2.5f);
        for(int i=0;i<52;i++)check(a[i]==b[i],"anatomical reflection agrees with the explicit schema permutation");
        System.out.println("FaceExpressionResponseTest: "+checks+" actual mapping checks passed; thresholds still require live calibration");
    }
}
