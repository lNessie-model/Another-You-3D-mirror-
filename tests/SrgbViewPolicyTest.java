package com.mirror.bench;

public final class SrgbViewPolicyTest {
    static int checks;
    static void ok(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    static void rejects(Runnable action){checks++;try{action.run();throw new AssertionError("accepted invalid input");}catch(IllegalArgumentException expected){}}
    public static void main(String[] args){
        String original=AvatarGpuScene.PBR_FRAGMENT;
        ok(SrgbViewPolicy.fragment(original,false)==original);
        String linear=SrgbViewPolicy.fragment(original,true);
        ok(!linear.contains("encodeSRGB"));
        ok(linear.contains("if(uUnlit>0.5){color=vec4(base,1.0);return;}"));
        ok(linear.contains("color=vec4(clamp(lit,0.0,1.0),1.0);"));
        String common=original.substring(original.indexOf("vec3 detailNormal("),original.indexOf("void main(){"));
        ok(linear.contains(common));
        String lighting=original.substring(original.indexOf("vec3 n=normalize(vNormal);float roughness"),original.indexOf("color=vec4(encodeSRGB(clamp"));
        ok(linear.contains(lighting));
        rejects(()->SrgbViewPolicy.fragment(original.replace("1.0/2.4","1.0/2.2"),true));
        rejects(()->SrgbViewPolicy.fragment(original.replace("vec4(encodeSRGB(base),1.0)","vec4(base,1.0)"),true));
        rejects(()->SrgbViewPolicy.fragment(null,true));
        SrgbViewPolicy.requireModel(SrgbViewPolicy.GERALT,true,true,false,false,false);
        for(int bad=0;bad<6;bad++){final int b=bad;rejects(()->SrgbViewPolicy.requireModel(b==0?"other":SrgbViewPolicy.GERALT,b!=1,b!=2,b==3,b==4,b==5));}
        SrgbViewPolicy.requireExtensions("GL_EXT_texture_sRGB_decode GL_EXT_sRGB_write_control");
        rejects(()->SrgbViewPolicy.requireExtensions("GL_EXT_texture_sRGB_decode_suffix GL_EXT_sRGB_write_control"));
        rejects(()->SrgbViewPolicy.requireExtensions("GL_EXT_texture_sRGB_decode"));
        rejects(()->SrgbViewPolicy.requireExtensions(null));
        System.out.println("SrgbViewPolicyTest "+checks+" checks GREEN");
    }
}
