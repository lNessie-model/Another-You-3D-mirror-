package com.mirror.bench;

import android.opengl.GLES30;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Executes the real production class; records uniforms at every actual draw call. */
public final class AvatarGroupSubmissionGlTest {
    private static int checks;private static AvatarAsset asset;private static boolean[] active;
    private record Fixture(AvatarBatchLayout layout,int[] buffers,float[] colors,float[] params,float[] pbr){}
    private record Result(List<GLES30.Draw> draws,List<GLES30.Call> calls,Map<Integer,String> shaders,JSONObject status){}
    public static void main(String[] args)throws Exception {
        asset=AvatarGlbLoader.load(Files.readAllBytes(Path.of(args[0])));var rig=new AvatarRig(asset,Files.readString(Path.of(args[1])));
        active=new boolean[asset.nodes().size()];for(int i=0;i<active.length;i++)active[i]=rig.activeNode(i);
        if(args.length>2&&args[2].equals("--legacy-trace")){legacyTrace();return;}
        defaultTrace();groupEquivalence();syntheticLayouts();failures();guardsAndClose();
        System.out.println("AvatarGroupSubmissionGlTest: "+checks+" checks; real production generator/GL dispatch, no GPU rasterization or timing");
    }
    private static Fixture fixture(AvatarAsset a,boolean[] activeNodes){
        GLES30.reset();var layout=new AvatarBatchLayout(a,activeNodes);int count=layout.entries().size();int[] buffers=new int[5];GLES30.glGenBuffers(5,buffers,0);
        float[] colors=new float[count*4],params=new float[count*4],pbr=new float[count*4];
        for(int i=0;i<count;i++) {
            var e=layout.entries().get(i);var mat=a.materials().get(a.meshes().get(e.mesh).primitives().get(e.primitive).materialIndex());
            mat.baseColor().get(colors,i*4,4);params[i*4]=mat.unlit()?1:0;params[i*4+1]=mat.roughness();params[i*4+2]=mat.textured()?1:0;
            pbr[i*4]=mat.normalScale();pbr[i*4+1]=mat.occlusionStrength();pbr[i*4+2]=mat.metallic();pbr[i*4+3]=mat.pbrMaps()?1:0;
        }
        return new Fixture(layout,buffers,colors,params,pbr);
    }
    private static Fixture fixture(){return fixture(asset,active);}
    private static AvatarBatchSpecializedGpu create(Fixture f,boolean reuse){return new AvatarBatchSpecializedGpu(f.layout,f.buffers,true,f.colors,f.params,f.pbr,true,reuse);}
    private static float[] values(int size,float seed){float[] out=new float[size];for(int i=0;i<size;i++)out[i]=seed+i*.0625f;return out;}
    private static void textureState(int seed){for(int i=0;i<3;i++){GLES30.glActiveTexture(0x84c0+i);GLES30.glBindTexture(0x0de1,seed+i);}}
    private static long count(List<GLES30.Call> calls,String name){return calls.stream().filter(c->c.name().equals(name)).count();}
    private static Result run(boolean reuse,int[] groups)throws Exception {
        Fixture f=fixture();var gpu=create(f,reuse);int queryCount=GLES30.attributeQueries;
        for(int g=0;g<groups.length;g++) {
            // A foreign current program and changed context texture state cannot seed a local cache.
            GLES30.current=-999;textureState(100+g*10);
            gpu.draw(values(64,10+g*100),groups[g],values(7*16,20+g*200),values(7*9,30+g*300));
        }
        check(GLES30.attributeQueries==queryCount,"no hot-path GL query");check(GLES30.bufferDataCalls==0,"no new storage/upload");
        var result=new Result(List.copyOf(GLES30.draws),List.copyOf(GLES30.calls),Map.copyOf(GLES30.linkedSources),gpu.status());
        assertCounters(gpu.status(),GLES30.calls);gpu.close();check(GLES30.programs.isEmpty()&&GLES30.buffers.size()==5,"close only owned programs");return result;
    }
    private static void defaultTrace()throws Exception {
        Fixture f=fixture();var old=new AvatarBatchSpecializedGpu(f.layout,f.buffers,true,f.colors,f.params,f.pbr);
        old.draw(values(64,1),4,values(112,2),values(63,3));List<GLES30.Call> six=List.copyOf(GLES30.calls);var sixShaders=Map.copyOf(GLES30.linkedSources);
        check(!old.status().getBoolean("group_uniform_reuse_requested")&&!old.status().getBoolean("group_uniform_reuse_actual"),"six arg default off");old.close();
        f=fixture();var explicit=new AvatarBatchSpecializedGpu(f.layout,f.buffers,true,f.colors,f.params,f.pbr,false,false);
        explicit.draw(values(64,1),4,values(112,2),values(63,3));check(six.equals(GLES30.calls)&&sixShaders.equals(GLES30.linkedSources),"six arg and explicit false full trace/source identical");explicit.close();
        f=fixture();var seven=new AvatarBatchSpecializedGpu(f.layout,f.buffers,true,f.colors,f.params,f.pbr,true);
        seven.draw(values(64,1),4,values(112,2),values(63,3));var trace=List.copyOf(GLES30.calls);seven.close();
        f=fixture();var eight=create(f,false);eight.draw(values(64,1),4,values(112,2),values(63,3));check(trace.equals(GLES30.calls),"seven arg preserves all returned calls and their order");
        int at=0;String[][] samplers={{"uAtlas","uNormalMap","uOrmMap"},{"uAtlas"},{"uAtlas"},{"uAtlas"},{"uAtlas"},{},{}};
        for(int entry=0;entry<7;entry++) {
            for(String name:new String[]{"use","vp","world","normal"})check(trace.get(at++).name().equals(name),"original per-entry call ordering");
            for(String name:samplers[entry])check(trace.get(at++).uniform().equals(name),"original sampler ordering");
            check(trace.get(at++).name().equals("draw"),"original draw follows all uniforms");
        }
        check(at==trace.size(),"no added default calls");eight.close();
    }
    private static void groupEquivalence()throws Exception {
        for(int[] counts:new int[][]{{4,4},{1,4,1}}) {
            Result reference=run(false,counts),candidate=run(true,counts);
            check(reference.shaders.equals(candidate.shaders),"shader programs byte-exact");check(reference.draws.size()==candidate.draws.size(),"all original draws retained");
            for(int i=0;i<reference.draws.size();i++)sameDraw(reference.draws.get(i),candidate.draws.get(i));
            int g=counts.length;check(count(candidate.calls,"use")==g*5&&count(candidate.calls,"vp")==g*5,"five actual binds/VP per fresh group");
            check(count(candidate.calls,"sampler")==g*6,"six active sampler assignments per fresh group");
            check(count(candidate.calls,"world")==g*7&&count(candidate.calls,"normal")==g*7&&count(candidate.calls,"draw")==g*7,"all world/normal/draw calls retained");
            check(candidate.status.getLong("skipped_use_program_calls")==g*2&&candidate.status.getLong("shared_uniform_reuses")==g*2,"actual skipped decisions counted");
            check(candidate.status.getLong("completed_view_groups")==g&&candidate.status.getBoolean("group_uniform_reuse_actual"),"scope count and requested mode truthful");
            for(int i=0;i<candidate.draws.size();i++) {
                var d=candidate.draws.get(i);int group=i/7,entry=i%7;
                check(Arrays.equals(d.vp(),Arrays.copyOf(values(64,10+group*100),counts[group]*16)),"exact current group's independent VP array");
                check(Arrays.equals(d.world(),Arrays.copyOfRange(values(112,20+group*200),entry*16,entry*16+16)),"independent eye/jaw world range at draw");
            }
        }
    }
    private static void sameDraw(GLES30.Draw a,GLES30.Draw b){
        check(a.program()==b.program()&&a.count()==b.count()&&a.byteOffset()==b.byteOffset()&&a.indexBuffer()==b.indexBuffer(),"same program/order/index draw");
        check(a.attributes().equals(b.attributes())&&a.textures().equals(b.textures()),"borrowed attribute/texture state unchanged");
        check(Arrays.equals(a.vp(),b.vp())&&Arrays.equals(a.world(),b.world())&&Arrays.equals(a.normal(),b.normal()),"per-draw matrix snapshots identical");
        check(a.samplers().equals(b.samplers()),"per-program sampler state identical at each draw");
    }
    private static AvatarAsset tiny(int[] meshOrder) {
        int meshes=1+Arrays.stream(meshOrder).max().orElseThrow();List<AvatarAsset.Mesh> ms=new ArrayList<>();List<AvatarAsset.Material> mats=new ArrayList<>();
        for(int m=0;m<meshes;m++) {
            float[] colors=values(12,.5f);if(m==0)Arrays.fill(colors,1);
            var p=new AvatarAsset.Primitive(new float[]{0,0,0,1,0,0,0,1,0},new float[]{0,0,1,0,0,1,0,0,1},null,colors,new int[]{0,1,2},List.of(),m);
            ms.add(new AvatarAsset.Mesh("mesh"+m,List.of(p),List.of(),new float[0]));
            mats.add(new AvatarAsset.Material("mat"+m,new float[]{1,1,1,1},.1f+m*.05f,false,true));
        }
        List<AvatarAsset.Node> nodes=new ArrayList<>();float[] matrix={1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
        for(int i=0;i<meshOrder.length;i++)nodes.add(new AvatarAsset.Node("instance"+i,meshOrder[i],new int[0],matrix.clone(),matrix.clone(),null));
        return new AvatarAsset(ms,nodes,mats,new int[0],nodes.size()*3,nodes.size(),0);
    }
    private static void syntheticLayouts()throws Exception {
        for(int[] order:new int[][]{{0,1,0},{0,1,2,3,4,5,6,7}}) {
            boolean[] all=new boolean[order.length];Arrays.fill(all,true);Fixture f=fixture(tiny(order),all);var gpu=create(f,true);
            gpu.draw(values(64,1),4,values(order.length*16,2),values(order.length*9,3));
            int unique=(int)Arrays.stream(order).distinct().count();
            check(count(GLES30.calls,"use")==order.length,"nonconsecutive programs still rebound in original order");
            check(count(GLES30.calls,"vp")==unique&&count(GLES30.calls,"sampler")==unique,"each bounded variant only once per group");
            check(count(GLES30.calls,"world")==order.length&&count(GLES30.calls,"normal")==order.length&&GLES30.draws.size()==order.length,"every instance keeps own matrices and draw");
            for(int i=0;i<order.length;i++)check(Arrays.equals(GLES30.draws.get(i).world(),Arrays.copyOfRange(values(order.length*16,2),i*16,i*16+16)),"nonconsecutive/maximum variants preserve instance offsets");
            assertCounters(gpu.status(),GLES30.calls);gpu.close();
        }
    }
    private static void failures()throws Exception {
        for(String failure:new String[]{"use","vp","sampler","world","normal","draw"})for(int failAt:new int[]{2,5}) {
            Fixture f=fixture();var gpu=create(f,true);GLES30.failCall=failure;GLES30.failAt=failAt;
            reject(()->gpu.draw(values(64,1),4,values(112,2),values(63,3)));
            check(gpu.status().getLong("completed_view_groups")==0,"failed group never completed");assertCounters(gpu.status(),GLES30.calls);
            int start=GLES30.draws.size();GLES30.failCall=null;GLES30.current=-888;
            gpu.draw(values(64,500),4,values(112,700),values(63,900));
            check(GLES30.draws.size()==start+7&&gpu.status().getLong("completed_view_groups")==1,"fresh group after failure fully resubmits");
            for(int i=start;i<GLES30.draws.size();i++)check(Arrays.equals(GLES30.draws.get(i).vp(),values(64,500)),"failure never seeds next group VP cache");
            assertCounters(gpu.status(),GLES30.calls);gpu.close();
        }
    }
    private static void guardsAndClose()throws Exception {
        Fixture f=fixture();reject(()->new AvatarBatchSpecializedGpu(f.layout,f.buffers,true,f.colors,f.params,f.pbr,false,true));
        check(GLES30.compileCalls==0&&GLES30.linkCalls==0,"reuse without proven constant-white rejected before GL");
        var gpu=create(f,true);AtomicReference<Throwable> error=new AtomicReference<>();Thread wrong=new Thread(()->{try{gpu.draw(values(64,1),4,values(112,2),values(63,3));}catch(Throwable e){error.set(e);}});wrong.start();wrong.join();
        check(error.get() instanceof IllegalStateException&&GLES30.calls.isEmpty(),"foreign owner cannot submit or seed local state");
        GLES30.failDelete=GLES30.programs.iterator().next();reject(gpu::close);check(GLES30.programs.isEmpty()&&GLES30.buffers.size()==5,"close failure still cleans owned programs only");gpu.close();
        check(!gpu.status().getBoolean("group_uniform_reuse_actual"),"closed candidate not active");reject(()->gpu.draw(values(64,1),4,values(112,2),values(63,3)));
    }
    private static void assertCounters(JSONObject status,List<GLES30.Call> calls)throws Exception {
        String[] keys={"returned_use_program_calls","vp_upload_calls","world_upload_calls","normal_upload_calls","sampler_uniform_calls","draw_calls"};
        String[] names={"use","vp","world","normal","sampler","draw"};
        for(int i=0;i<keys.length;i++)check(status.getLong(keys[i])==count(calls,names[i]),"returned API counter matches independent trace: "+keys[i]);
    }
    /** Can run against the frozen V46 class first on the classpath: only old 6/7-arg APIs used. */
    private static void legacyTrace()throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(boolean white:new boolean[]{false,true}) {
            Fixture f=fixture();var gpu=white?new AvatarBatchSpecializedGpu(f.layout,f.buffers,true,f.colors,f.params,f.pbr,true)
                    :new AvatarBatchSpecializedGpu(f.layout,f.buffers,true,f.colors,f.params,f.pbr);
            int group=0;for(int count:new int[]{4,1,4}){textureState(100+group);gpu.draw(values(64,10+group),count,values(112,20+group),values(63,30+group));group++;}
            digest.update(new TreeMap<>(GLES30.linkedSources).toString().getBytes(StandardCharsets.UTF_8));
            digest.update(GLES30.calls.toString().getBytes(StandardCharsets.UTF_8));
            for(var d:GLES30.draws) {
                String value=d.program()+":"+d.count()+":"+d.byteOffset()+":"+d.indexBuffer()+":"+new TreeMap<>(d.attributes())
                        +":"+Arrays.toString(d.vp())+":"+Arrays.toString(d.world())+":"+Arrays.toString(d.normal())
                        +":"+new TreeMap<>(d.samplers())+":"+new TreeMap<>(d.textures());
                digest.update(value.getBytes(StandardCharsets.UTF_8));
            }
            gpu.close();
        }
        System.out.println("Default old-API trace/source/draw-uniform SHA256: "+HexFormat.of().formatHex(digest.digest()));
    }
    private static void reject(Runnable action){try{action.run();throw new AssertionError("Expected guard/injected failure");}catch(IllegalArgumentException|IllegalStateException expected){checks++;}}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
}
