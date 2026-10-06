package com.mirror.bench;

import android.opengl.GLES30;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Executes actual candidate/program/batch lifecycle and draw dispatch at the GL boundary. */
public final class AvatarBatchSpecializedLifecycleTest {
    private static int checks;
    private static AvatarAsset asset;private static AvatarRig rig;
    public static void main(String[] args)throws Exception {
        asset=AvatarGlbLoader.load(Files.readAllBytes(Path.of(args[0])));rig=new AvatarRig(asset,Files.readString(Path.of(args[1])));
        successful();
        for(int fail=1;fail<=20;fail++)failedConstruction(fail,false);
        for(int fail=1;fail<=10;fail++)failedConstruction(fail,true);
        disposalFailure();defaultBatchAndSelection();
        System.out.println("AvatarBatchSpecializedLifecycleTest: "+checks+" checks; real Java production path, GL boundary fake only");
    }
    private record Fixture(AvatarBatchLayout layout,int[] buffers,float[] colors,float[] params,float[] pbr){}
    private static Fixture fixture(){
        GLES30.reset();boolean[] active=new boolean[asset.nodes().size()];for(int n=0;n<active.length;n++)active[n]=rig.activeNode(n);
        AvatarBatchLayout l=new AvatarBatchLayout(asset,active);int count=l.entries().size();
        int[] buffers=new int[5];GLES30.glGenBuffers(5,buffers,0);
        float[] colors=new float[count*4],params=new float[count*4],pbr=new float[count*4];
        for(int i=0;i<count;i++) {
            var e=l.entries().get(i);var mat=asset.materials().get(asset.meshes().get(e.mesh).primitives().get(e.primitive).materialIndex());
            mat.baseColor().get(colors,i*4,4);params[i*4]=mat.unlit()?1:0;params[i*4+1]=mat.roughness();params[i*4+2]=mat.textured()?1:0;
            pbr[i*4]=mat.normalScale();pbr[i*4+1]=mat.occlusionStrength();pbr[i*4+2]=mat.metallic();pbr[i*4+3]=mat.pbrMaps()?1:0;
        }
        return new Fixture(l,buffers,colors,params,pbr);
    }
    private static AvatarBatchSpecializedGpu create(Fixture f){return new AvatarBatchSpecializedGpu(f.layout,f.buffers,true,f.colors,f.params,f.pbr);}
    private static void successful()throws Exception {
        Fixture f=fixture();var gpu=create(f);Set<Integer> borrowed=Set.copyOf(GLES30.buffers);
        check(GLES30.programs.size()==10&&GLES30.shaders.isEmpty(),"five unique materials times two programs");
        check(GLES30.bufferDataCalls==0&&GLES30.buffers.equals(borrowed),"no storage allocation/upload by specialization");
        float[] vp=new float[64],worlds=new float[7*16],normals=new float[7*9];
        for(int i=0;i<vp.length;i++)vp[i]=i+.125f;for(int i=0;i<worlds.length;i++)worlds[i]=i+.25f;for(int i=0;i<normals.length;i++)normals[i]=i+.5f;
        for(int count:new int[]{1,4}) {
            int first=GLES30.draws.size();gpu.draw(vp,count,worlds,normals);
            check(GLES30.draws.size()==first+7,"seven actual dispatches");
            int total=0;
            for(int i=0;i<7;i++) {
                var draw=GLES30.draws.get(first+i);var e=f.layout.entries().get(i);
                check(draw.indexBuffer()==f.buffers[3]&&draw.byteOffset()==e.firstIndex*4&&draw.count()==e.indexCount,"same contiguous IBO ranges in original entry order");
                check(!draw.attributes().containsKey(3),"packed ID attribute disabled");
                check(draw.attributes().get(0)==f.buffers[0]&&draw.attributes().get(1)==f.buffers[0]&&draw.attributes().get(2)==f.buffers[1]&&draw.attributes().get(4)==f.buffers[4],"same original attribute storage");
                check(Arrays.equals(draw.vp(),Arrays.copyOf(vp,count*16)),"all independent VP values unchanged");
                check(Arrays.equals(draw.world(),Arrays.copyOfRange(worlds,i*16,i*16+16)),"exact world subrange");
                if(draw.normal()!=null)check(Arrays.equals(draw.normal(),Arrays.copyOfRange(normals,i*9,i*9+9)),"exact inverse transpose subrange");
                total+=draw.count();
            }
            check(total==f.layout.indexCount(),"every triangle once per view");
        }
        var status=gpu.status();check(status.getLong("draw_calls")==14&&status.getLong("completed_view_groups")==2,"actual counters");
        check(status.getInt("last_draw_program_id")==GLES30.current,"status corresponds to last bound program");
        AtomicReference<Throwable> error=new AtomicReference<>();Thread wrong=new Thread(()->{try{gpu.close();}catch(Throwable e){error.set(e);}});wrong.start();wrong.join();
        check(error.get() instanceof IllegalStateException&&GLES30.programs.size()==10,"foreign thread cannot destroy GL programs");
        gpu.close();gpu.close();check(GLES30.programs.isEmpty()&&GLES30.buffers.equals(borrowed),"close owned programs once, borrowed buffers untouched");
        reject(()->gpu.draw(vp,4,worlds,normals));
    }
    private static void failedConstruction(int at,boolean link){
        Fixture f=fixture();if(link)GLES30.failLink=at;else GLES30.failCompile=at;
        reject(()->create(f));check(GLES30.programs.isEmpty()&&GLES30.shaders.isEmpty(),"every partial construction releases owned shaders/programs");
        check(GLES30.buffers.size()==5,"partial construction never deletes borrowed buffers");
    }
    private static void disposalFailure()throws Exception {
        Fixture f=fixture();var gpu=create(f);GLES30.failDelete=GLES30.programs.iterator().next();reject(gpu::close);
        check(GLES30.programs.isEmpty()&&GLES30.buffers.size()==5,"failed deletion still closes every other program");gpu.close();
    }
    private static void defaultBatchAndSelection()throws Exception {
        GLES30.reset();AvatarBatchGpu batch=new AvatarBatchGpu(asset,rig,true);
        check(GLES30.programs.size()==2&&GLES30.linkCalls==2,"default OFF no candidate programs");
        check(batch.status().isNull("specialized")&&!batch.status().getBoolean("specialized_selected"),"default status truthful");
        reject(()->batch.selectSpecialized(true));batch.beginSpecializedComparison();
        check(GLES30.programs.size()==12&&!batch.status().getBoolean("specialized_selected"),"lazy begin compiles but reference remains selected");
        reject(batch::beginSpecializedComparison);reject(batch::beginPbrComparison);
        batch.selectSpecialized(true);check(batch.status().getInt("draw_calls_per_view_group")==7,"selected count truthful");
        AtomicReference<Throwable> error=new AtomicReference<>();Thread wrong=new Thread(()->{try{batch.endSpecializedComparison();}catch(Throwable e){error.set(e);}});wrong.start();wrong.join();
        check(error.get() instanceof IllegalStateException&&batch.status().getBoolean("specialized_selected"),"foreign close cannot detach/leak active candidate");
        batch.endSpecializedComparison();check(GLES30.programs.size()==2&&batch.status().getInt("draw_calls_per_view_group")==1,"end restores original path/programs");
        batch.beginPbrComparison();reject(batch::beginSpecializedComparison);batch.endPbrComparison();
        batch.beginSpecializedComparison();GLES30.failDelete=batch.status().getJSONObject("specialized").getJSONArray("programs").getJSONObject(0).getInt("single_program");
        reject(batch::dispose);check(GLES30.programs.isEmpty()&&GLES30.buffers.isEmpty(),"dispose attempts defaults and owned buffers after candidate close failure");batch.dispose();
        GLES30.reset();AvatarBatchGpu fast=new AvatarBatchGpu(asset,rig,true,true);reject(fast::beginSpecializedComparison);fast.dispose();
    }
    private static void reject(Runnable r){try{r.run();throw new AssertionError("expected guard/failure");}catch(IllegalStateException expected){checks++;}}
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
}
