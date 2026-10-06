package com.mirror.bench;

import android.opengl.GLES30;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opt-in material specialization; shares the parent batch's buffers and original index order. */
final class AvatarBatchSpecializedGpu implements AutoCloseable {
    private final Thread owner=Thread.currentThread();
    private final AvatarBatchLayout layout;
    private final int[] buffers;
    private final Pair[] entries;
    private final List<Pair> materials=new ArrayList<>();
    private final boolean multiview;
    private long drawCalls,groups,serialGroups,multiviewGroups;
    private int lastProgram,lastViewCount;
    private boolean closed;

    AvatarBatchSpecializedGpu(AvatarBatchLayout layout,int[] borrowedBuffers,boolean multiview,
            float[] colors,float[] params,float[] pbrParams) {
        if(layout==null||borrowedBuffers==null||borrowedBuffers.length!=5)
            throw new IllegalArgumentException("Existing textured PBR batch buffers required");
        for(int buffer:borrowedBuffers)if(buffer==0)throw new IllegalArgumentException("Live batch buffers required");
        this.layout=layout;this.buffers=borrowedBuffers;this.multiview=multiview;
        entries=new Pair[layout.entries().size()];
        LinkedHashMap<String,Pair> unique=new LinkedHashMap<>();
        try {
            for(int i=0;i<entries.length;i++) {
                Material material=new Material(colors,params,pbrParams,i);String key=fragmentSource(material);
                Pair pair=unique.get(key);
                if(pair==null) {
                    pair=new Pair(material,multiview);materials.add(pair);unique.put(key,pair);
                }
                entries[i]=pair;
            }
            checkGl("specialized batch construction");
        }catch(RuntimeException|Error failure){try{close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    /** All matrices were computed by the original parent draw; global indices stay unchanged. */
    void draw(float[] vp,int count,float[] worlds,float[] normals) {
        requireOwner();
        if((count!=1&&count!=4)||count==4&&!multiview||vp==null||vp.length<count*16
                ||worlds==null||worlds.length<entries.length*16||normals==null||normals.length<entries.length*9)
            throw new IllegalArgumentException("Complete specialized view group/matrices required");
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);GLES30.glEnable(GLES30.GL_CULL_FACE);
        GLES30.glFrontFace(GLES30.GL_CCW);GLES30.glCullFace(GLES30.GL_BACK);GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,buffers[0]);
        GLES30.glEnableVertexAttribArray(0);GLES30.glVertexAttribPointer(0,3,GLES30.GL_FLOAT,false,24,0);
        GLES30.glEnableVertexAttribArray(1);GLES30.glVertexAttribPointer(1,3,GLES30.GL_FLOAT,false,24,12);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,buffers[1]);
        GLES30.glEnableVertexAttribArray(2);GLES30.glVertexAttribPointer(2,4,GLES30.GL_FLOAT,false,16,0);
        // aPrimitive is absent. Do not fetch the packed id buffer or change index values.
        GLES30.glDisableVertexAttribArray(3);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,buffers[4]);
        GLES30.glEnableVertexAttribArray(4);GLES30.glVertexAttribPointer(4,2,GLES30.GL_FLOAT,false,8,0);
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER,buffers[3]);
        for(int i=0;i<entries.length;i++) {
            Program p=count==4?entries[i].multi:entries[i].single;
            GLES30.glUseProgram(p.id);
            GLES30.glUniformMatrix4fv(p.vp,count,false,vp,0);
            GLES30.glUniformMatrix4fv(p.world,1,false,worlds,i*16);
            if(p.normal>=0)GLES30.glUniformMatrix3fv(p.normal,1,false,normals,i*9);
            if(p.atlas>=0)GLES30.glUniform1i(p.atlas,0);
            if(p.normalMap>=0)GLES30.glUniform1i(p.normalMap,1);
            if(p.orm>=0)GLES30.glUniform1i(p.orm,2);
            var e=layout.entries().get(i);
            GLES30.glDrawElements(GLES30.GL_TRIANGLES,e.indexCount,GLES30.GL_UNSIGNED_INT,e.firstIndex*4);
            drawCalls++;lastProgram=p.id;lastViewCount=count;
        }
        for(int i=0;i<5;i++)GLES30.glDisableVertexAttribArray(i);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0);GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER,0);
        groups++;if(count==4)multiviewGroups++;else serialGroups++;
    }
    JSONObject status()throws Exception {
        JSONArray programs=new JSONArray(),ranges=new JSONArray();
        for(int i=0;i<materials.size();i++) {
            Pair p=materials.get(i);programs.put(new JSONObject().put("material_variant",i)
                    .put("single_program",p.single.id).put("multiview_program",p.multi==null?0:p.multi.id)
                    .put("fragment_sha256",p.single.fragmentSha));
        }
        for(int i=0;i<entries.length;i++) {
            var e=layout.entries().get(i);ranges.put(new JSONObject().put("entry",i).put("node",e.node)
                    .put("mesh",e.mesh).put("primitive",e.primitive).put("index_count",e.indexCount)
                    .put("index_byte_offset",e.firstIndex*4).put("material_variant",materials.indexOf(entries[i])));
        }
        return new JSONObject().put("backend","per_entry_material_specialized").put("closed",closed)
                .put("draw_calls",drawCalls).put("completed_view_groups",groups)
                .put("serial_groups",serialGroups).put("multiview_groups",multiviewGroups)
                .put("last_draw_program_id",lastProgram).put("last_view_count",lastViewCount)
                .put("draw_calls_per_view_group",entries.length).put("material_variants",materials.size())
                .put("compiled_program_count",materials.size()*(multiview?2:1)).put("programs",programs).put("entry_ranges",ranges)
                .put("extra_vertex_bytes",0).put("extra_index_bytes",0).put("extra_texture_bytes",0)
                .put("math","unchanged_reference_pbr").put("glsl_validation","requires_actual_driver_pixel_gate");
    }
    @Override public void close() {
        if(Thread.currentThread()!=owner)throw new IllegalStateException("Specialized programs belong to their GL owner");
        if(closed)return;closed=true;
        ResourceCleanup cleanup=new ResourceCleanup(null);
        for(Pair p:materials)cleanup.close("specialized material programs",p);
        rethrow(cleanup.failure());
    }
    void requireOwner(){if(Thread.currentThread()!=owner||closed)throw new IllegalStateException("Live GL-owner specialized batch required");}
    private static void rethrow(Throwable failure){if(failure instanceof Error e)throw e;if(failure instanceof RuntimeException r)throw r;if(failure!=null)throw new IllegalStateException(failure);}
    private static void checkGl(String label){int error=GLES30.glGetError();if(error!=GLES30.GL_NO_ERROR)throw new IllegalStateException(label+" GL error "+error);}
    private static final class Pair implements AutoCloseable {
        final Program single,multi;private boolean closed;
        Pair(Material material,boolean multiview) {
            Program a=new Program(false,material),b=null;
            try{if(multiview)b=new Program(true,material);}
            catch(RuntimeException|Error failure){try{a.close();}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
            single=a;multi=b;
        }
        @Override public void close(){if(closed)return;closed=true;ResourceCleanup c=new ResourceCleanup(null);c.close("single",single);c.close("multiview",multi);rethrow(c.failure());}
    }
    private static final class Program implements AutoCloseable {
        final int id,vp,world,normal,atlas,normalMap,orm;
        final String fragmentSha;
        private boolean closed;
        Program(boolean multiview,Material material) {
            String fragment=fragmentSource(material);fragmentSha=AvatarPbrShaderVariant.sha256(fragment);
            int vs=0,fs=0,created=0;
            try {
                vs=AvatarGpuScene.shader(GLES30.GL_VERTEX_SHADER,vertexSource(multiview,material));
                fs=AvatarGpuScene.shader(GLES30.GL_FRAGMENT_SHADER,fragment);
                created=GLES30.glCreateProgram();GLES30.glAttachShader(created,vs);GLES30.glAttachShader(created,fs);GLES30.glLinkProgram(created);
                int[] ok=new int[1];GLES30.glGetProgramiv(created,GLES30.GL_LINK_STATUS,ok,0);
                if(ok[0]==0)throw new IllegalStateException("Specialized PBR program: "+GLES30.glGetProgramInfoLog(created));
                id=created;vp=location(id,"uViewProjection");world=location(id,"uWorld");
                normal=material.lit?GLES30.glGetUniformLocation(id,"uNormal"):-1;
                // Constant material arithmetic may legitimately optimize a sampler/normal away.
                atlas=material.textured?GLES30.glGetUniformLocation(id,"uAtlas"):-1;
                normalMap=material.maps?GLES30.glGetUniformLocation(id,"uNormalMap"):-1;
                orm=material.maps?GLES30.glGetUniformLocation(id,"uOrmMap"):-1;
            }catch(RuntimeException|Error failure){if(created!=0)try{GLES30.glDeleteProgram(created);}catch(RuntimeException|Error cleanup){failure.addSuppressed(cleanup);}throw failure;}
            finally{if(vs!=0)GLES30.glDeleteShader(vs);if(fs!=0)GLES30.glDeleteShader(fs);}
        }
        private static int location(int id,String name){int value=GLES30.glGetUniformLocation(id,name);if(value<0)throw new IllegalStateException("Missing specialized uniform "+name);return value;}
        @Override public void close(){if(!closed){closed=true;GLES30.glDeleteProgram(id);}}
    }
    static final class Material {
        final boolean lit,textured,maps,uv;
        private final float[] color=new float[4],pbr=new float[4];
        private final float roughness;
        Material(float[] colors,float[] params,float[] pbrParams,int entry) {
            int offset=Math.multiplyExact(entry,4);
            if(entry<0||colors==null||params==null||pbrParams==null||offset>colors.length-4||offset>params.length-4||offset>pbrParams.length-4)
                throw new IllegalArgumentException("Complete immutable material values required");
            for(int i=0;i<4;i++) {
                if(!Float.isFinite(colors[offset+i])||!Float.isFinite(params[offset+i])||!Float.isFinite(pbrParams[offset+i]))
                    throw new IllegalArgumentException("Finite material values required");
                color[i]=colors[offset+i];pbr[i]=pbrParams[offset+i];
            }
            lit=params[offset]<=.5f;textured=params[offset+2]>.5f;maps=lit&&pbr[3]>.5f;
            uv=textured||maps;roughness=params[offset+1];
        }
    }
    static String literal(float value) {
        if(!Float.isFinite(value))throw new IllegalArgumentException("Finite GLSL constant required");
        // Java's shortest decimal round-trips binary32, including -0.0. Not hexadecimal GLSL.
        return Float.toString(value);
    }
    private static String vector(float[] values) {
        return "vec4("+literal(values[0])+","+literal(values[1])+","+literal(values[2])+","+literal(values[3])+")";
    }
    static String vertexSource(boolean multiview,Material material) {
        String source=AvatarGpuScene.VERTEX;
        if(!material.lit)source=replace(source,"layout(location=1) in vec3 aNormal;","")
                .replace("uniform mat3 uNormal;","").replace("out vec3 vNormal;out vec3 vPosition;","")
                .replace("vNormal=uNormal*aNormal;vPosition=world.xyz;","");
        if(material.uv)source=AvatarGpuScene.atlasVertex(source);
        if(multiview)source=replace(source,"#version 300 es","#version 300 es\n#extension GL_OVR_multiview2 : require\nlayout(num_views=4) in;")
                .replace("uniform mat4 uViewProjection;","uniform mat4 uViewProjection[4];")
                .replace("uViewProjection*","uViewProjection[gl_ViewID_OVR]*");
        return source;
    }
    static String fragmentSource(Material material) {
        String source=replace(AvatarGpuScene.PBR_FRAGMENT,"uniform vec4 uColor;uniform float uUnlit;uniform float uRoughness;",
                "const vec4 uColor="+vector(material.color)+";const float uRoughness="+literal(material.roughness)+";");
        source=replace(source,"uniform sampler2D uAtlas;uniform float uUseTexture;",material.textured?"uniform sampler2D uAtlas;":"");
        source=replace(source,"uniform vec4 uPbrParams;","const vec4 uPbrParams="+vector(material.pbr)+";");
        source=replace(source,"vec3 albedo=uUseTexture>0.5?texture(uAtlas,vUV).rgb:vec3(1.0);",
                material.textured?"vec3 albedo=texture(uAtlas,vUV).rgb;":"vec3 albedo=vec3(1.0);");
        if(!material.maps) {
            int start=source.indexOf("vec3 detailNormal("),end=source.indexOf("void main(){",start);
            if(start<0||end<=start)throw new IllegalStateException("PBR normal helper anchor changed");
            source=source.substring(0,start)+source.substring(end);
            source=replace(source,"uniform sampler2D uNormalMap;uniform sampler2D uOrmMap;","");
        }
        String unlit="if(uUnlit>0.5){color=vec4(encodeSRGB(base),1.0);return;}";
        if(!material.lit) {
            int start=source.indexOf(unlit);
            if(start<0)throw new IllegalStateException("PBR unlit anchor changed");
            source=source.substring(0,start)+"color=vec4(encodeSRGB(base),1.0);\n}\n";
            source=replace(source,"in vec3 vNormal;in vec3 vPosition;","");
        }else {
            source=replace(source,unlit,"");
            int start=source.indexOf("if(uPbrParams.w>0.5){"),end=source.indexOf("roughness=clamp",start);
            if(start<0||end<=start)throw new IllegalStateException("PBR map branch anchor changed");
            String block=source.substring(start,end);
            if(material.maps)source=source.substring(0,start)+block.replace("if(uPbrParams.w>0.5)","")+source.substring(end);
            else source=source.substring(0,start)+source.substring(end);
        }
        if(!material.uv)source=replace(source,"in vec2 vUV;","");
        return source;
    }
    private static String replace(String source,String from,String to) {
        int at=source.indexOf(from);
        if(at<0||source.indexOf(from,at+from.length())>=0)throw new IllegalStateException("Shader anchor changed: "+from);
        return source.substring(0,at)+to+source.substring(at+from.length());
    }
}
