package com.mirror.bench;

/** Deliberately narrow experiment: unchanged original Geralt PBR, encoded-space view sampling. */
final class SrgbViewPolicy {
    static final String GERALT="9381f452c53098314f97e1a1799ec55d2be37878e66bf205c7a26958afcef531";
    private static final String ENCODER="vec3 encodeSRGB(vec3 c){return mix(12.92*c,1.055*pow(max(c,vec3(0.0)),vec3(1.0/2.4))-0.055,step(vec3(0.0031308),c));}";
    private SrgbViewPolicy(){}
    static void requireModel(String sha,boolean pbr,boolean ordinary,boolean orm,boolean fast,boolean props){
        if(!GERALT.equals(sha)||!pbr||!ordinary||orm||fast||props)
            throw new IllegalArgumentException("sRGB views require exact Geralt original PBR individual rendering without other material/3D-background experiments");
    }
    static void requireExtensions(String extensions){
        String tokens=" "+extensions+" ";
        if(extensions==null||!tokens.contains(" GL_EXT_texture_sRGB_decode ")||!tokens.contains(" GL_EXT_sRGB_write_control "))
            throw new IllegalArgumentException("sRGB views require EXT_texture_sRGB_decode and EXT_sRGB_write_control");
    }
    static String fragment(String original,boolean linearOutput){
        if(!linearOutput)return original;
        if(original==null)throw new IllegalArgumentException("Original PBR shader required");
        String result=once(original,ENCODER,"");
        result=once(result,"vec4(encodeSRGB(base),1.0)","vec4(base,1.0)");
        result=once(result,"vec4(encodeSRGB(clamp(lit,0.0,1.0)),1.0)","vec4(clamp(lit,0.0,1.0),1.0)");
        if(result.contains("encodeSRGB"))throw new IllegalArgumentException("Unconverted output encoder");
        return result;
    }
    private static String once(String source,String from,String to){
        int at=source.indexOf(from);
        if(at<0||source.indexOf(from,at+from.length())>=0)throw new IllegalArgumentException("Original PBR output contract changed");
        return source.substring(0,at)+to+source.substring(at+from.length());
    }
}
