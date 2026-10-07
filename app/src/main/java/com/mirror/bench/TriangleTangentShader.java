package com.mirror.bench;

/** Narrow source transform. Retains screen det, lengthT, handedness, implicit texture LOD and PBR tail. */
final class TriangleTangentShader {
    private TriangleTangentShader(){}
    static String vertex(String original){return once(original,"#version 300 es","#version 320 es");}
    static String fragment(String original){
        String source=vertex(original);
        source=once(source,"uniform vec4 uPbrParams;","uniform vec4 uPbrParams;\nuniform highp sampler2D uTriangleTangent;");
        source=once(source,"vec3 dp1=dFdx(vPosition),dp2=dFdy(vPosition);vec2 du1=dFdx(vUV),du2=dFdy(vUV);","vec2 du1=dFdx(vUV),du2=dFdy(vUV);");
        return once(source,"vec3 t=(dp1*du2.y-dp2*du1.y)/det;\n    vec3 b=(dp2*du1.x-dp1*du2.x)/det;",
                "highp int tangentTexel=gl_PrimitiveID*2;\n    highp ivec2 tangentCoord=ivec2(tangentTexel%512,tangentTexel/512);\n    vec3 t=texelFetch(uTriangleTangent,tangentCoord,0).xyz;\n    vec3 b=texelFetch(uTriangleTangent,tangentCoord+ivec2(1,0),0).xyz;");
    }
    private static String once(String s,String a,String b){int i=s.indexOf(a);if(i<0||s.indexOf(a,i+a.length())>=0)throw new IllegalArgumentException("Original tangent source differs");return s.substring(0,i)+b+s.substring(i+a.length());}
}
