package com.mirror.bench;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Opt-in highp source algebra only. No compiler fast-math flag, precision change or texture change. */
final class AvatarPbrShaderVariant {
    static final String REFERENCE="reference";
    static final String FAST="highp_schlick5_smith_combined_v1";
    private AvatarPbrShaderVariant(){}
    private static final class Candidate {static final String SOURCE=candidate(AvatarGpuScene.PBR_FRAGMENT);}
    static String fragment(boolean fast){return fast?Candidate.SOURCE:AvatarGpuScene.PBR_FRAGMENT;}
    static String name(boolean pbr,boolean fast){return pbr?(fast?FAST:REFERENCE):"not_pbr";}
    static String candidate(String source){
        if(source==null||!source.contains("precision highp float;"))throw new IllegalArgumentException("PBR highp source required");
        source=replaceOnce(source,"vec3 f0=mix(vec3(0.04),base,metallic),f=f0+(1.0-f0)*pow(1.0-vh,5.0);",
                "float schlick=1.0-vh,schlick2=schlick*schlick;\n    vec3 f0=mix(vec3(0.04),base,metallic),f=f0+(1.0-f0)*(schlick2*schlick2*schlick);");
        source=replaceOnce(source,"float g=(nv/(nv*(1.0-k)+k))*(nl/(nl*(1.0-k)+k));",
                "float smithV=nv*(1.0-k)+k,smithL=nl*(1.0-k)+k;");
        return replaceOnce(source,"vec3 spec=d*g*f/max(4.0*nv*nl,0.001);",
                "vec3 spec=(d*f*(nv*nl))/(smithV*smithL*max(4.0*nv*nl,0.001));");
    }
    private static String replaceOnce(String source,String old,String replacement){
        int first=source.indexOf(old);
        if(first<0||source.indexOf(old,first+old.length())>=0)throw new IllegalArgumentException("PBR template changed or duplicated");
        return source.substring(0,first)+replacement+source.substring(first+old.length());
    }
    static String sha256(String source){
        try{
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder(64);for(byte b:bytes)out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));
            return out.toString();
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
