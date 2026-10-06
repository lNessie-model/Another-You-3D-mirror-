package com.mirror.bench;

import android.opengl.GLES30;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Actual specialization generator/construction/draw execution; GL compiler boundary is a fake. */
public final class AvatarPrimaryColorGlTest {
    private static int checks;private static AvatarAsset asset;private static AvatarRig rig;
    public static void main(String[] args)throws Exception {
        asset=AvatarGlbLoader.load(Files.readAllBytes(Path.of(args[0])));rig=new AvatarRig(asset,Files.readString(Path.of(args[1])));
        defaults();candidateDraw();sameMaterialDifferentColors();rejectedProofBeforeGl();failedLinkEvidence();shaderAnchors();
        System.out.println("AvatarPrimaryColorGlTest: "+checks+" checks; actual production GL-boundary behavior, no Mali/pixel/performance claim");
    }
    private record Fixture(AvatarBatchLayout layout,int[] buffers,float[] colors,float[] params,float[] pbr){}
    private static Fixture fixture(){
        GLES30.reset();boolean[] active=new boolean[asset.nodes().size()];for(int n=0;n<active.length;n++)active[n]=rig.activeNode(n);
        AvatarBatchLayout layout=new AvatarBatchLayout(asset,active);int count=layout.entries().size();int[] buffers=new int[5];GLES30.glGenBuffers(5,buffers,0);
        float[] colors=new float[count*4],params=new float[count*4],pbr=new float[count*4];
        for(int i=0;i<count;i++) {
            var e=layout.entries().get(i);var mat=asset.materials().get(asset.meshes().get(e.mesh).primitives().get(e.primitive).materialIndex());
            mat.baseColor().get(colors,i*4,4);params[i*4]=mat.unlit()?1:0;params[i*4+1]=mat.roughness();params[i*4+2]=mat.textured()?1:0;
            pbr[i*4]=mat.normalScale();pbr[i*4+1]=mat.occlusionStrength();pbr[i*4+2]=mat.metallic();pbr[i*4+3]=mat.pbrMaps()?1:0;
        }
        return new Fixture(layout,buffers,colors,params,pbr);
    }
    private static AvatarBatchSpecializedGpu create(Fixture f,boolean enabled){return new AvatarBatchSpecializedGpu(f.layout,f.buffers,true,f.colors,f.params,f.pbr,enabled);}
    private static List<String> linked(){List<String> out=new ArrayList<>(GLES30.linkedSources.values());Collections.sort(out);return out;}
    private static void defaults()throws Exception {
        Fixture f=fixture();var old=new AvatarBatchSpecializedGpu(f.layout,f.buffers,true,f.colors,f.params,f.pbr);
        List<String> oldSources=linked();check(GLES30.attributeQueries==0,"old constructor adds no linked-attribute query");
        check(!old.status().getBoolean("constant_white_requested")&&!old.status().getBoolean("constant_white_actual"),"old default off truthful");old.close();
        f=fixture();var explicit=create(f,false);
        check(oldSources.equals(linked())&&GLES30.attributeQueries==0,"explicit false keeps exact old generated source and GL queries");explicit.close();
    }
    private static void candidateDraw()throws Exception {
        Fixture f=fixture();float[] before=new float[f.layout.colors().remaining()];f.layout.colors().get(before);var gpu=create(f,true);
        var status=gpu.status();check(status.getBoolean("constant_white_requested")&&status.getBoolean("constant_white_actual"),"candidate actual only after proof/link success");
        check(status.getInt("constant_white_verified_entries")==1&&status.getInt("constant_white_verified_vertices")==13975,"full actual primary scanned");
        check(GLES30.attributeQueries==10&&GLES30.bufferDataCalls==0,"once per program, no extra buffers/uploads");
        Set<Integer> primary=new HashSet<>();var variants=status.getJSONArray("programs");
        for(int i=0;i<variants.length();i++) {
            var p=variants.getJSONObject(i);boolean white=p.getString("color_mode").equals("constant_white_primary");
            check(p.getInt("single_color_attribute")== (white?-1:2)&&p.getInt("multiview_color_attribute")== (white?-1:2),"actual link evidence matches color mode");
            for(String key:new String[]{"single_program","multiview_program"}) {
                int id=p.getInt(key);String source=GLES30.linkedSources.get(id);
                check(source.contains("const vec4 vColor=vec4(1.0);")==white,"only proven program uses white constant");
                check(source.contains("layout(location=2) in vec4 aColor;")!=white&&source.contains("out vec4 vColor;")!=white,"matching producer interface");
                if(white)primary.add(id);
            }
        }
        float[] vp=new float[64],worlds=new float[7*16],normals=new float[7*9];
        for(int i=0;i<vp.length;i++)vp[i]=i+.125f;for(int i=0;i<worlds.length;i++)worlds[i]=i+.25f;for(int i=0;i<normals.length;i++)normals[i]=i+.5f;
        for(int group=0;group<4;group++)gpu.draw(vp,4,worlds,normals);
        check(GLES30.draws.size()==28&&gpu.status().getLong("draw_calls")==28,"unchanged 28 calls across independent 16 views");
        for(int j=0;j<GLES30.draws.size();j++) {
            int i=j%7;var e=f.layout.entries().get(i);var d=GLES30.draws.get(j);
            check(primary.contains(d.program())==(e.mesh==0&&e.primitive==0),"entry selects correct linked color mode");
            check(d.indexBuffer()==f.buffers[3]&&d.byteOffset()==e.firstIndex*4&&d.count()==e.indexCount,"original global IBO order/range");
            check(d.attributes().get(2)==f.buffers[1],"original color VBO retained, no per-entry attrib change");
            check(Arrays.equals(d.vp(),vp)&&Arrays.equals(d.world(),Arrays.copyOfRange(worlds,i*16,i*16+16)),"same original VP/world operands");
        }
        check(GLES30.attributeQueries==10,"zero per-draw attribute queries");
        float[] after=new float[before.length];f.layout.colors().get(after);check(Arrays.equals(before,after),"all immutable packed colors retained");
        gpu.close();check(GLES30.programs.isEmpty()&&GLES30.shaders.isEmpty()&&GLES30.buffers.size()==5,"only owned programs deleted");
    }
    private static void sameMaterialDifferentColors()throws Exception {
        Fixture f=fixture();for(int i=1;i<f.layout.entries().size();i++){System.arraycopy(f.colors,0,f.colors,i*4,4);System.arraycopy(f.params,0,f.params,i*4,4);System.arraycopy(f.pbr,0,f.pbr,i*4,4);}
        var gpu=create(f,true);var status=gpu.status();check(status.getInt("material_variants")==2&&GLES30.programs.size()==4,"same material splits cache by color mode");
        var entries=status.getJSONArray("entry_ranges");for(int i=0;i<entries.length();i++) {
            var e=f.layout.entries().get(i);check(entries.getJSONObject(i).getString("color_mode").equals(e.mesh==0&&e.primitive==0?"constant_white_primary":"interpolated"),"same material does not make other primitives constant");
        }
        gpu.close();
    }
    private static void rejectedProofBeforeGl()throws Exception {
        for(float invalid:new float[]{.99f,-0f,Float.NaN}) {
            Fixture f=fixture();var field=AvatarBatchLayout.class.getDeclaredField("colors");field.setAccessible(true);float[] packed=(float[])field.get(f.layout);
            var primary=f.layout.entries().stream().filter(e->e.mesh==0&&e.primitive==0).findFirst().orElseThrow();packed[(primary.firstVertex+primary.vertexCount)*4-1]=invalid;
            reject(()->create(f,true));check(GLES30.compileCalls==0&&GLES30.linkCalls==0&&GLES30.attributeQueries==0,"bad actual packed last component fails before any program work");
            var legacy=create(f,false);check(GLES30.attributeQueries==0,"explicit default remains unaffected by experimental gate");legacy.close();
        }
    }
    private static void failedLinkEvidence() {
        for(boolean constant:new boolean[]{true,false}) {
            Fixture f=fixture();GLES30.wrongConstantAttribute=constant;GLES30.wrongInterpolatedAttribute=!constant;
            reject(()->create(f,true));check(GLES30.programs.isEmpty()&&GLES30.shaders.isEmpty()&&GLES30.buffers.size()==5,"wrong linked interface rejects and cleans all owned resources");
        }
        for(int fail:new int[]{1,2,10}) {
            Fixture f=fixture();GLES30.failLink=fail;reject(()->create(f,true));check(GLES30.programs.isEmpty()&&GLES30.shaders.isEmpty(),"partial candidate link cleans previous pairs");
        }
    }
    private static void shaderAnchors() {
        var m=new AvatarBatchSpecializedGpu.Material(new float[]{1,1,1,1},new float[]{0,.4f,1,0},new float[]{1,1,0,1},0);
        String fs=AvatarBatchSpecializedGpu.fragmentSource(m);String candidate=AvatarBatchSpecializedGpu.fragmentSource(m,true);
        check(candidate.equals(fs.replace("in vec4 vColor;","const vec4 vColor=vec4(1.0);")),"all fragment arithmetic preserved, only input declaration replaced");
        for(boolean multi:new boolean[]{false,true}) {
            String vs=AvatarBatchSpecializedGpu.vertexSource(multi,m);String expected=vs.replace("layout(location=2) in vec4 aColor;","").replace("out vec4 vColor;","").replace("vColor=aColor;","");
            check(AvatarBatchSpecializedGpu.vertexSource(multi,m,true).equals(expected),"all original vertex arithmetic/UV/OVR preserved");
        }
    }
    private static void reject(Runnable r){try{r.run();throw new AssertionError("Expected candidate rejection");}catch(IllegalStateException|IllegalArgumentException expected){checks++;}}
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
}
