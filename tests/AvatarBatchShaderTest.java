package com.mirror.bench;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Source contracts only: actual Mali compilation, pixel equivalence and speed require device gates. */
public final class AvatarBatchShaderTest {
    private static int checks;
    public static void main(String[] args) {
        for(int count=1;count<=8;count++)for(boolean multiview:new boolean[]{false,true}) {
            String vertex=AvatarBatchGpu.vertexSource(multiview,count);
            String fragment=AvatarBatchGpu.fragmentSource(count);
            check(vertex.contains("uniform vec4 uColors["+count+"]"),"material array is selected in vertex stage");
            check(vertex.contains("uniform vec4 uParams["+count+"]"),"parameter array is selected in vertex stage");
            check(vertex.contains("vMaterialColor=uColors[int(aPrimitive)];"),"material uses integer item identity");
            check(vertex.contains("vMaterialParams=uParams[int(aPrimitive)].xy;"),"unlit/roughness retain xy order");
            check(vertex.contains("flat out highp vec4 vMaterialColor;")&&fragment.contains("flat in highp vec4 vMaterialColor;"),"material is highp and constant per triangle");
            check(vertex.contains("flat out highp vec2 vMaterialParams;")&&fragment.contains("flat in highp vec2 vMaterialParams;"),"parameters are highp and constant per triangle");
            check(!fragment.contains("uColors")&&!fragment.contains("uParams")&&!fragment.contains("vPrimitive"),"fragment has no item-dependent array lookup");
            check(!vertex.contains("vPrimitive"),"old id varying is removed");
            check(vertex.contains("vec4 world=uWorld[int(aPrimitive)]*vec4(aPosition,1.0);"),"world multiplication remains separate");
            check(vertex.contains("vNormal=uNormal[int(aPrimitive)]*aNormal;vPosition=world.xyz;vColor=aColor;"),"normal/position/COLOR_0 arithmetic unchanged");
            String vp=multiview?"uViewProjection[gl_ViewID_OVR]":"uViewProjection";
            check(vertex.contains("gl_Position="+vp+"*world;"),"VP follows world with original order");
            check(vertex.contains("layout(location=3) in highp uint aPrimitive;"),"integer attribute layout unchanged");
            check(vertex.contains("layout(num_views=4) in;")==multiview,"only multiview shader declares four views");
            check(uniformVectors(vertex)==count*9+(multiview?16:4),"declared vertex uniform budget");
            check(uniformVectors(fragment)==0,"fragment uniform budget is zero");
            check(varyingVectors(vertex)==5,"five conservative varying vectors");
            String body=fragment.substring(fragment.indexOf("vec3 base="));
            check(compact(body).equals(compact(LIGHTING_BODY)),"fragment lighting preserves frozen v8 operation order");
            check(fragment.contains("vec4 uColor=vMaterialColor;float uUnlit=vMaterialParams.x;float uRoughness=vMaterialParams.y;"),"fragment aliases preserve material values and types");
        }
        System.out.println("AvatarBatchShaderTest: "+checks+" checks; source/limits contracts only, no GL execution");
    }
    private static int uniformVectors(String source) {
        Matcher m=Pattern.compile("uniform\\s+(mat4|mat3|vec4)\\s+\\w+(?:\\[(\\d+)])?;").matcher(source);int total=0;
        while(m.find())total+=(m.group(1).equals("mat4")?4:m.group(1).equals("mat3")?3:1)*(m.group(2)==null?1:Integer.parseInt(m.group(2)));
        return total;
    }
    private static int varyingVectors(String source) {
        Matcher m=Pattern.compile("(?:flat\\s+)?out\\s+(?:highp\\s+)?(?:vec[234]|int)\\s+\\w+;").matcher(source);int total=0;
        while(m.find())total++;
        return total;
    }
    private static String compact(String value){return value.replaceAll("\\s+","");}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    // Independent frozen v8 body; do not replace with AvatarGpuScene.FRAGMENT or recomputed values.
    private static final String LIGHTING_BODY="""
        vec3 base=clamp(uColor.rgb*vColor.rgb,0.0,1.0);
        vec3 n=normalize(vNormal),light=normalize(vec3(-0.45,0.65,1.0));
        float diffuse=max(dot(n,light),0.0);
        vec3 h=normalize(light+normalize(vec3(0.0,0.0,3.0)-vPosition));
        float spec=pow(max(dot(n,h),0.0),mix(64.0,8.0,uRoughness))*(1.0-uRoughness)*0.15;
        vec3 lit=base*(0.32+0.68*diffuse)+vec3(spec);
        color=vec4(pow(clamp(mix(lit,base,uUnlit),0.0,1.0),vec3(1.0/2.2)),1.0);
        }
        """;
}
