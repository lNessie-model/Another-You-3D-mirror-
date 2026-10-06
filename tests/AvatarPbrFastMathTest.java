package com.mirror.bench;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Random;

/** Source transform + independent scalar numerical checks. Not a GLSL/Mali emulator. */
public final class AvatarPbrFastMathTest {
    private static int checks;
    private static double maxRelative,maxAbsolute,maxSchlick;
    public static void main(String[] args)throws Exception {
        Class<?> type=Class.forName("com.mirror.bench.AvatarPbrShaderVariant");
        Method fragment=type.getDeclaredMethod("fragment",boolean.class),candidate=type.getDeclaredMethod("candidate",String.class);
        fragment.setAccessible(true);candidate.setAccessible(true);
        String reference=AvatarGpuScene.PBR_FRAGMENT,fast=(String)fragment.invoke(null,true);
        check(reference.equals(fragment.invoke(null,false)),"reference untouched");
        String oldF="vec3 f0=mix(vec3(0.04),base,metallic),f=f0+(1.0-f0)*pow(1.0-vh,5.0);";
        String newF="float schlick=1.0-vh,schlick2=schlick*schlick;\n    vec3 f0=mix(vec3(0.04),base,metallic),f=f0+(1.0-f0)*(schlick2*schlick2*schlick);";
        String oldG="float g=(nv/(nv*(1.0-k)+k))*(nl/(nl*(1.0-k)+k));";
        String newG="float smithV=nv*(1.0-k)+k,smithL=nl*(1.0-k)+k;";
        String oldS="vec3 spec=d*g*f/max(4.0*nv*nl,0.001);";
        String newS="vec3 spec=(d*f*(nv*nl))/(smithV*smithL*max(4.0*nv*nl,0.001));";
        check(fast.replace(newF,oldF).replace(newG,oldG).replace(newS,oldS).equals(reference),"only three exact source replacements; normalization/maps/lights unchanged");
        check(fast.contains("precision highp float;")&&fast.contains("max(4.0*nv*nl,0.001)"),"highp and grazing clamp retained");
        for(String bad:new String[]{reference.replace(oldF,"changed"),reference+oldF,reference.replace(oldG,"changed"),reference.replace(oldS,"changed"),reference.replace("precision highp float;","precision mediump float;")}) {
            try{candidate.invoke(null,bad);throw new AssertionError("changed template accepted");}
            catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalArgumentException,"unexpected template rejected");}
        }
        for(boolean atlas:new boolean[]{false,true})for(boolean pbr:new boolean[]{false,true})for(int count:new int[]{1,7,8}) {
            Method batch=AvatarBatchGpu.class.getDeclaredMethod("fragmentSource",int.class,boolean.class,boolean.class,boolean.class);batch.setAccessible(true);
            check(AvatarBatchGpu.fragmentSource(count,atlas,pbr).equals(batch.invoke(null,count,atlas,pbr,false)),"old batch overload stays reference");
            String bf=(String)batch.invoke(null,count,atlas,pbr,true);
            check(pbr?bf.contains(newS):bf.equals(AvatarBatchGpu.fragmentSource(count,atlas,pbr)),"candidate only PBR");
        }
        float[] rough={.12f,Math.nextUp(.12f),.2f,.5f,1f};
        float[] nv={.001f,Math.nextUp(.001f),.01f,.25f,1f};
        float[] nh={0,.25f,.8f,.99f,1};
        for(float r:rough)for(float v:nv)for(float h:nh){
            float edge=.001f/(4*v);
            for(float l:new float[]{0,Float.MIN_VALUE,1e-8f,Math.nextDown(edge),edge,Math.nextUp(edge),.5f,1})
                numerical(r,v,l,h,.04f);
        }
        Random random=new Random(361737L);
        for(int i=0;i<50000;i++)numerical(.12f+random.nextFloat()*.88f,.001f+random.nextFloat()*.999f,random.nextFloat(),random.nextFloat(),random.nextFloat());
        for(int i=0;i<=10000;i++){
            float x=i/10000f,x2=x*x;float product=x2*x2*x,power=(float)Math.pow(x,5);
            maxSchlick=Math.max(maxSchlick,Math.abs((double)product-power));
            check(Float.isFinite(product)&&Math.abs((double)product-power)<=2e-7,"Schlick bounded F32 roundoff");
        }
        // The tempting cancellation loses the grazing clamp: this counterexample must differ.
        double r=.12,v=.001,l=1e-7,k=(r+1)*(r+1)/8,sv=v*(1-k)+k,sl=l*(1-k)+k;
        double kept=(v*l)/(sv*sl*Math.max(4*v*l,.001)),wrong=1/(4*sv*sl);
        check(wrong>kept*1000,"grazing cancellation is not equivalent");
        System.out.println("AvatarPbrFastMathTest: "+checks+" checks; max relative scalar F32="+maxRelative+", max absolute="+maxAbsolute+", max Schlick="+maxSchlick+"; GPU pixel/performance gates pending");
    }
    private static void numerical(float rough,float nv,float nl,float nh,float fresnel){
        float a=rough*rough,a2=a*a,den=nh*nh*(a2-1)+1,d=a2/((float)Math.PI*den*den),k=(rough+1)*(rough+1)/8;
        float sv=nv*(1-k)+k,sl=nl*(1-k)+k,clamp=Math.max(4*nv*nl,.001f);
        float old=d*((nv/sv)*(nl/sl))*fresnel/clamp;
        float fast=(d*fresnel*(nv*nl))/(sv*sl*clamp);
        check(Float.isFinite(old)&&Float.isFinite(fast)&&old>=0&&fast>=0,"finite nonnegative extrema");
        double error=Math.abs((double)old-fast),relative=error/Math.max(1e-30,Math.abs((double)old));
        maxAbsolute=Math.max(maxAbsolute,error);maxRelative=Math.max(maxRelative,relative);
        check(error<=2e-6*Math.max(1,Math.abs((double)old)),"scalar highp F32 rearrangement envelope");
        if(nl==0)check(old==0&&fast==0,"zero incident light remains zero");
        double exactOld=(double)d*((double)nv/sv)*((double)nl/sl)*fresnel/clamp;
        double exactNew=(double)d*fresnel*((double)nv*nl)/((double)sv*sl*clamp);
        check(Math.abs(exactOld-exactNew)<=1e-12*Math.max(1,Math.abs(exactOld)),"independent real algebra envelope");
    }
    private static void check(boolean good,String why){checks++;if(!good)throw new AssertionError(why);}
}
