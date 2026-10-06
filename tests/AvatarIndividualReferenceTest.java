package com.mirror.bench;

import android.opengl.GLES30;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.json.JSONObject;

/** Actual Scene+checker group path, real Geralt/rig/deformer; GL/Bitmap boundary fixture only. */
public final class AvatarIndividualReferenceTest {
    private static int checks;
    private static AvatarAsset asset;private static String manifest;
    private static final String SHA="9381f452c53098314f97e1a1799ec55d2be37878e66bf205c7a26958afcef531";
    public static void main(String[] args)throws Exception {
        asset=AvatarGlbLoader.load(Files.readAllBytes(Path.of(args[0])));manifest=Files.readString(Path.of(args[1]));
        ordinaryDefault();paired();failedClose();cancelledClose();
        System.out.println("AvatarIndividualReferenceTest: "+checks+" checks; actual Scene/checker Java calls and real CPU geometry, no rasterization or Mali claim");
    }
    private static AvatarGpuScene scene(AvatarGpuScene.DrawMode mode)throws Exception {
        GLES30.reset();return AvatarGpuScene.fromAsset(asset,manifest,SHA,true,false,mode);
    }
    private static void ordinaryDefault()throws Exception {
        var s=scene(AvatarGpuScene.DrawMode.INDIVIDUAL);
        check(s.status().getString("draw_backend").equals("individual")&&!s.status().has("batch"),"ordinary remains individual/no packed allocation");
        check(s.status().getLong("individual_draw_calls")==0,"default draw counters start zero");
        reject(s::beginSpecializedBatch);s.dispose();checkEmpty();
    }
    private static void paired()throws Exception {
        var s=scene(AvatarGpuScene.DrawMode.VERIFY);check(s.status().getString("pose_backend").equals("synchronous_gl_thread"),"VERIFY owns a synchronous pose");
        s.beginSpecializedBatch();reject(s::beginSpecializedBatch);reject(s::createPbrComparison);
        check(s.status().getString("deformation_scope").contains("separate"),"VERIFY buffer scope truthful");
        float[] vp=new float[64];for(int i=0;i<vp.length;i++)vp[i]=(i%17==0)?1:i*.0001f;
        float[] weights=new float[52],angles=new float[3];
        for(int pose=0;pose<2;pose++) {
            weights[25]=pose*.7f;angles[0]=pose*18;long updates=s.status().getLong("morph_updates");
            s.prepare(weights,angles);check(s.status().getLong("morph_updates")==updates+1,"one prepare updates both stores");
            JSONObject before=s.status();int first=GLES30.draws.size();
            for(int group=0;group<4;group++)AvatarSpecializedCheck.drawVerifiedGroup(s,vp,4,.625f,false);
            int candidateStart=GLES30.draws.size();
            for(int group=0;group<4;group++)AvatarSpecializedCheck.drawVerifiedGroup(s,vp,4,.625f,true);
            var after=s.status();
            check(candidateStart-first==28&&GLES30.draws.size()-candidateStart==28,"28 real ordinary and 28 candidate draw submissions");
            check(after.getLong("individual_draw_calls")-before.getLong("individual_draw_calls")==28,"ordinary counter matches fake-observed actual GL calls");
            check(after.getLong("individual_view_groups")-before.getLong("individual_view_groups")==4,"ordinary completed groups");
            check(after.getJSONObject("batch").getLong("reference_draw_calls")==0,"packed original is not the reference");
            JSONObject a=after.getJSONObject("batch").getJSONObject("specialized"),b=before.getJSONObject("batch").getJSONObject("specialized");
            check(a.getLong("draw_calls")-b.getLong("draw_calls")==28&&a.getLong("multiview_groups")-b.getLong("multiview_groups")==4,"specialized actual calls/groups");
            for(int i=0;i<28;i++){
                var ref=GLES30.draws.get(first+i);var cand=GLES30.draws.get(candidateStart+i);
                check(ref.program()!=cand.program(),"different linked programs actually submitted");
                check(ref.indexBuffer()!=cand.indexBuffer()&&ref.attributes().get(0)!=cand.attributes().get(0),"independent primitive vs packed storage");
                check(Arrays.equals(ref.vp(),cand.vp())&&Arrays.equals(ref.world(),cand.world()),"same supplied VP and computed world");
                equalIndexedGeometry(ref,cand);
            }
        }
        int calls=GLES30.draws.size();GLES30.failDraw=calls+3;
        long before=s.status().getLong("individual_draw_calls"),groups=s.status().getLong("individual_view_groups");
        reject(()->AvatarSpecializedCheck.drawVerifiedGroup(s,vp,4,.625f,false));
        check(s.status().getLong("individual_draw_calls")==before+2&&s.status().getLong("individual_view_groups")==groups,"partial failed group counts only returned draws, no completed group");
        GLES30.failDraw=0;GLES30.wrongCurrent=true;
        reject(()->AvatarSpecializedCheck.drawVerifiedGroup(s,vp,4,.625f,false));
        reject(()->AvatarSpecializedCheck.drawVerifiedGroup(s,vp,4,.625f,true));
        GLES30.wrongCurrent=false;s.endSpecializedBatch();
        reject(()->s.individualProgramIdForVerification(4));s.dispose();s.dispose();checkEmpty();
    }
    private static void equalIndexedGeometry(GLES30.Draw ref,GLES30.Draw cand){
        check(ref.count()==cand.count(),"same triangles per item");
        int[] ri=GLES30.indexData.get(ref.indexBuffer()),ci=GLES30.indexData.get(cand.indexBuffer());
        float[] rv=GLES30.vertexData.get(ref.attributes().get(0)),cv=GLES30.vertexData.get(cand.attributes().get(0));
        for(int n=0;n<ref.count();n++)for(int c=0;c<6;c++) {
            float a=rv[ri[ref.byteOffset()/4+n]*6+c],b=cv[ci[cand.byteOffset()/4+n]*6+c];
            if(Float.floatToRawIntBits(a)!=Float.floatToRawIntBits(b))throw new AssertionError("different CPU xyz/normal value at triangle index");
        }
        checks++;
    }
    private static void failedClose()throws Exception {
        var s=scene(AvatarGpuScene.DrawMode.VERIFY);s.beginSpecializedBatch();
        GLES30.failDelete=s.status().getJSONObject("batch").getJSONObject("specialized").getJSONArray("programs").getJSONObject(0).getInt("single_program");
        reject(s::dispose);checkEmpty();s.dispose();
    }
    private static void cancelledClose()throws Exception {
        var s=scene(AvatarGpuScene.DrawMode.VERIFY);s.beginSpecializedBatch();
        Throwable original=new java.util.concurrent.CancellationException("cancelled fixture");
        GLES30.failDelete=s.status().getJSONObject("batch").getJSONObject("specialized").getJSONArray("programs").getJSONObject(0).getInt("single_program");
        ResourceCleanup cleanup=new ResourceCleanup(original);cleanup.close("verify scene",s::dispose);
        check(cleanup.failure()==original&&original.getSuppressed().length==1,"cancellation remains primary, cleanup retained");checkEmpty();
    }
    private static void checkEmpty(){check(GLES30.programs.isEmpty()&&GLES30.buffers.isEmpty()&&GLES30.textures.isEmpty(),"all scene programs/buffers/textures released");}
    private interface Run {void run()throws Exception;}
    private static void reject(Run r)throws Exception {try{r.run();throw new AssertionError("expected failure");}catch(IllegalStateException ok){checks++;}}
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
}
