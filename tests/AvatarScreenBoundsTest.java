package com.mirror.bench;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import org.json.JSONObject;

/** The oracle projects real uploaded vertices, not the implementation's AABB/corner algorithm. */
public final class AvatarScreenBoundsTest {
    private static int checks;
    private static final int VIEW_WIDTH=400,VIEW_HEIGHT=640,OUTPUT_WIDTH=1000,OUTPUT_HEIGHT=1600;
    public static void main(String[] args)throws Exception{
        fixtureChecks();actualAssets(Path.of(args[0]));
        System.out.println("PASS: "+checks+" screen-bound checks; actual upload XYZ+normalXYZ stride6; CPU only, no EGL/ADB/render-output claim");
    }
    private static void fixtureChecks()throws Exception{
        AvatarAsset asset=AvatarRigTest.fixture();AvatarRig rig=new AvatarRig(asset,AvatarRigTest.manifest().toString());
        AvatarScreenBounds bounds=new AvatarScreenBounds(asset,rig);float[] fit=identity();fit[0]=fit[5]=fit[10]=.1f;
        float[] worlds=worlds(asset,rig),views=identity(),uv=new float[4];
        check(!copy(bounds,worlds,fit,views,1,uv)&&full(uv),"unuploaded geometry conservatively falls back");
        FloatBuffer uploaded=interleaved(new float[]{0,0,0,1,0,0,0,1,0});
        bounds.updatePrimitive(0,0,uploaded);
        check(copy(bounds,worlds,fit,views,1,uv),"known uploaded primitive permits bounded drawing");
        check(uv[2]-uv[0]<.10&&uv[3]-uv[1]<.10,"small actual primitive yields a small rectangle");
        double padX=2d/VIEW_WIDTH+2d/OUTPUT_WIDTH,padY=2d/VIEW_HEIGHT+2d/OUTPUT_HEIGHT;
        check(uv[0]<=.5-padX&&uv[2]>=.55+padX,"two view texels plus two output pixels of horizontal outward padding");
        check(uv[1]<=.51-padY&&uv[3]>=.56+padY,"vertical padding independently uses both heights");
        assertVertices(asset,rig,new FloatBuffer[][]{{uploaded}},fit,views,1,uv,"fixture");

        float[] saved=uv.clone();uploaded.put(0,-999);check(copy(bounds,worlds,fit,views,1,uv)&&Arrays.equals(saved,uv),
            "released/reused slot changes do not alter last uploaded cache");uploaded.put(0,0);
        FloatBuffer prefixed=FloatBuffer.allocate(25);for(int i=0;i<18;i++)prefixed.put(7+i,uploaded.get(i));
        prefixed.position(7);prefixed.limit(25);prefixed.mark();bounds.updatePrimitive(0,0,prefixed);
        check(prefixed.position()==7&&prefixed.limit()==25,"position/limit unchanged with nonzero span offset");prefixed.reset();
        FloatBuffer direct=ByteBuffer.allocateDirect(18*4).order(ByteOrder.nativeOrder()).asFloatBuffer();direct.put(uploaded.duplicate());direct.flip();
        bounds.updatePrimitive(0,0,direct.asReadOnlyBuffer());check(copy(bounds,worlds,fit,views,1,uv),"direct read-only upload accepted");
        ByteOrder wrongOrder=ByteOrder.nativeOrder()==ByteOrder.LITTLE_ENDIAN?ByteOrder.BIG_ENDIAN:ByteOrder.LITTLE_ENDIAN;
        FloatBuffer wrongEndian=ByteBuffer.allocateDirect(18*4).order(wrongOrder).asFloatBuffer();wrongEndian.put(uploaded.duplicate());wrongEndian.flip();
        bounds.updatePrimitive(0,0,wrongEndian);check(!copy(bounds,worlds,fit,views,1,uv)&&full(uv),"buffer byte order must match GPU native float interpretation");
        bounds.updatePrimitive(0,0,uploaded);
        for(FloatBuffer bad:new FloatBuffer[]{null,FloatBuffer.allocate(17),FloatBuffer.allocate(24),interleaved(new float[]{0,0,Float.NaN,1,0,0,0,1,0})}){
            bounds.updatePrimitive(0,0,bad);check(!copy(bounds,worlds,fit,views,1,uv)&&full(uv),"bad upload revokes stale valid bounds");
            bounds.updatePrimitive(0,0,uploaded);check(copy(bounds,worlds,fit,views,1,uv),"next successful valid upload repairs this primitive");
        }
        FloatBuffer badNormal=uploaded.duplicate();badNormal.put(5,Float.POSITIVE_INFINITY);bounds.updatePrimitive(0,0,badNormal);
        check(!copy(bounds,worlds,fit,views,1,uv)&&full(uv),"nonfinite uploaded normal also invalidates bounds");uploaded.put(5,1);bounds.updatePrimitive(0,0,uploaded);
        AvatarScreenBounds invalidKey=new AvatarScreenBounds(asset,rig);invalidKey.updatePrimitive(0,0,uploaded);invalidKey.updatePrimitive(9,0,uploaded);
        check(!copy(invalidKey,worlds,fit,views,1,uv)&&full(uv),"invalid primitive identity cannot leave stale valid cache");

        for(float[] badFit:new float[][]{null,new float[15],nanAt(identity(),4)})
            check(!copy(bounds,worlds,badFit,views,1,uv)&&full(uv),"invalid fit falls back full screen");
        check(!copy(bounds,null,fit,views,1,uv)&&full(uv),"missing worlds falls back");
        check(!copy(bounds,new float[16],fit,views,1,uv)&&full(uv),"short node worlds rejected");
        check(!copy(bounds,worlds,fit,null,1,uv)&&full(uv),"missing views falls back");
        check(!copy(bounds,worlds,fit,views,2,uv)&&full(uv),"short view array rejected");
        check(!copy(bounds,worlds,fit,views,0,uv)&&full(uv),"empty view list rejected");
        check(!copy(bounds,worlds,fit,views,Integer.MAX_VALUE,uv)&&full(uv),"view-count multiplication cannot overflow");
        check(!bounds.copyUvBounds(worlds,fit,views,1,0,640,1000,1600,uv)&&full(uv),"invalid dimensions rejected");
        check(!copy(bounds,nanAt(worlds,6*16+3),fit,views,1,uv)&&full(uv),"invalid active node matrix rejected");
        check(!copy(bounds,worlds,fit,nanAt(views,3),1,uv)&&full(uv),"nonfinite view matrix rejected");
        boolean shortDestination=false;try{copy(bounds,worlds,fit,views,1,new float[3]);}catch(IllegalArgumentException expected){shortDestination=true;}
        check(shortDestination,"unwritable destination is explicitly rejected");

        // Head and static background share the same mesh; each instance must contribute independently.
        var nodes=new ArrayList<>(asset.nodes());var shoulder=nodes.get(2);
        float[] shoulderMatrix=identity();shoulderMatrix[12]=-1.5f;
        nodes.set(2,new AvatarAsset.Node(shoulder.name(),0,new int[0],shoulderMatrix,shoulderMatrix,new float[0]));
        nodes.add(new AvatarAsset.Node("Unused",0,new int[0],identity(),identity(),new float[0]));
        AvatarAsset instanced=new AvatarAsset(asset.meshes(),nodes,asset.materials(),new int[]{0},6,2,200);
        AvatarRig instanceRig=new AvatarRig(instanced,AvatarRigTest.manifest().toString());AvatarScreenBounds instances=new AvatarScreenBounds(instanced,instanceRig);
        instances.updatePrimitive(0,0,uploaded);float[] instancedWorlds=worlds(instanced,instanceRig);
        instancedWorlds[7*16]=Float.NaN;
        check(copy(instances,instancedWorlds,fit,views,1,uv),"inactive node's invalid matrix is irrelevant");
        check(uv[0]<.425&&uv[2]>.55,"static background/attachment instance included, not just Head subtree");

        float[] shifted=identity();shifted[12]=.4f;shifted[13]=-.2f;shifted[0]=-.3f;shifted[5]=.2f;
        check(copy(bounds,worlds,shifted,views,1,uv),"translated and reflected nonuniform fit accepted");
        assertVertices(asset,rig,new FloatBuffer[][]{{uploaded}},shifted,views,1,uv,"translation/reflection");
        float[] sixteen=new float[16*16];for(int i=0;i<16;i++){float[] view=identity();view[12]=(i-7.5f)*.025f;System.arraycopy(view,0,sixteen,i*16,16);}
        check(copy(bounds,worlds,fit,sixteen,16,uv),"all sixteen independent views accepted");
        assertVertices(asset,rig,new FloatBuffer[][]{{uploaded}},fit,sixteen,16,uv,"sixteen views");
        check(uv[0]<.40&&uv[2]>.64,"union includes extreme views, not just first group");

        FloatBuffer near=interleaved(new float[]{0,0,2.8f,.1f,0,2.95f,0,.1f,2.8f});bounds.updatePrimitive(0,0,near);
        float[] perspective=perspective(0);
        check(!copy(bounds,worlds,identity(),perspective,1,uv)&&full(uv),"AABB crossing near plane falls back");
        FloatBuffer behind=interleaved(new float[]{0,0,2.8f,.1f,0,3.1f,0,.1f,2.8f});bounds.updatePrimitive(0,0,behind);
        check(!copy(bounds,worlds,identity(),perspective,1,uv)&&full(uv),"homogeneous W crossing falls back");
        bounds.updatePrimitive(0,0,uploaded);float[] onNear=identity();onNear[14]=-1;
        check(!copy(bounds,worlds,fit,onNear,1,uv)&&full(uv),"touching near plane is conservatively uncertain");
        float[] offscreen=fit.clone();offscreen[12]=5;
        check(copy(bounds,worlds,offscreen,views,1,uv)&&uv[0]==1&&uv[2]==1,"fully offscreen rectangle safely clamps");
        float[] overflow=identity();overflow[0]=Float.MAX_VALUE;float[] hugeWorlds=worlds.clone();hugeWorlds[6*16]=Float.MAX_VALUE;
        check(!copy(bounds,hugeWorlds,overflow,views,1,uv)&&full(uv),"finite inputs whose products overflow float32 conservatively fall back");
        float[] cancellation=identity();cancellation[12]=-10_000_000;float[] cancelledWorlds=worlds.clone();cancelledWorlds[6*16+12]=10_000_000;
        check(copy(bounds,cancelledWorlds,cancellation,views,1,uv)&&uv[0]==0&&uv[2]==1,"large cancellation expands uncertain float32 XY instead of underestimating");

        // Independently projected float32 vertex pipelines stress matrix rounding, translation and shear.
        Random matrixRandom=new Random(42277);float[] scratchMatrix=new float[16];
        for(int sample=0;sample<200;sample++){
            float[] randomFit=identity();randomFit[0]=.05f+matrixRandom.nextFloat()*.3f;randomFit[5]=.05f+matrixRandom.nextFloat()*.3f;
            randomFit[10]=.05f+matrixRandom.nextFloat()*.3f;randomFit[4]=(matrixRandom.nextFloat()-.5f)*.2f;
            randomFit[12]=(matrixRandom.nextFloat()-.5f)*.2f;randomFit[13]=(matrixRandom.nextFloat()-.5f)*.2f;
            check(copy(bounds,worlds,randomFit,perspective,1,uv),"ordinary translated/sheared float32 fit remains bounded");
            for(int column=0;column<4;column++)for(int row=0;row<4;row++){
                float value=0;for(int k=0;k<4;k++)value+=randomFit[k*4+row]*worlds[6*16+column*4+k];scratchMatrix[column*4+row]=value;
            }
            for(int vertex=0;vertex<3;vertex++){
                float[] point={uploaded.get(vertex*6),uploaded.get(vertex*6+1),uploaded.get(vertex*6+2),1};
                float[] worldPoint=floatTransform(scratchMatrix,point),clipPoint=floatTransform(perspective,worldPoint);
                float u=(clipPoint[0]/clipPoint[3])*.5f+.5f,v=(clipPoint[1]/clipPoint[3])*.5f+.5f;
                check(u>=uv[0]&&u<=uv[2]&&v>=uv[1]&&v<=uv[3],"independent float32 uploaded vertex enclosed");
            }
        }

        var bean=java.lang.management.ManagementFactory.getThreadMXBean();
        if(bean instanceof com.sun.management.ThreadMXBean allocation&&allocation.isThreadAllocatedMemorySupported()){
            allocation.setThreadAllocatedMemoryEnabled(true);long thread=Thread.currentThread().getId();
            for(int i=0;i<20_000;i++)copy(bounds,worlds,fit,sixteen,16,uv);
            long before=allocation.getThreadAllocatedBytes(thread);
            for(int i=0;i<10_000;i++)copy(bounds,worlds,fit,sixteen,16,uv);
            long bytes=allocation.getThreadAllocatedBytes(thread)-before;
            check(bytes==0,"hot copy path allocates no memory: "+bytes);
            System.out.println("Hot-path allocation: "+bytes+" bytes for 10,000 sixteen-view copies after warmup");
        }
    }
    private static void actualAssets(Path root)throws Exception{
        var catalog=new JSONObject(Files.readString(root.resolve("avatars/catalog/catalog.json"))).getJSONArray("entries");
        for(int role=0;role<catalog.length();role++){
            var entry=catalog.getJSONObject(role);Path directory=root.resolve(entry.getString("directory"));
            byte[] bytes=Files.readAllBytes(directory.resolve("character.glb"));String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            check(sha.equals(entry.getString("modelSha256")),"actual catalog model digest");
            AvatarAsset asset=AvatarGlbLoader.load(bytes);AvatarRig rig=new AvatarRig(asset,Files.readString(directory.resolve("avatar.json")));
            AvatarScreenBounds bounds=new AvatarScreenBounds(asset,rig);AvatarDeformer deformer=new AvatarDeformer(asset,AvatarDeformer.NormalPolicy.fromManifest(rig.normalPolicy()));
            float[] fit=new float[16];AvatarGeometryBounds.fromAsset(asset,rig).copyFitMatrix(.625f,fit);
            float[] views=new float[16*16];for(int v=0;v<16;v++)System.arraycopy(perspective(-.2f+.4f*v/15),0,views,v*16,16);
            FloatBuffer[][] uploads=new FloatBuffer[asset.meshes().size()][];float[] uv=new float[4];int poses=0;double maxArea=0;
            Random random=new Random(429997);
            for(int p=0;p<19;p++){
                float[] values=new float[52],angles=new float[3];
                if(p==1)Arrays.fill(values,1);
                if(p>=2){for(int c=1;c<52;c++)values[c]=random.nextFloat();angles[0]=(random.nextFloat()-.5f)*90;angles[1]=(random.nextFloat()-.5f)*130;angles[2]=(random.nextFloat()-.5f)*80;}
                rig.update(values,angles);
                for(int m=0;m<uploads.length;m++){
                    float[] weights=new float[asset.meshes().get(m).targetCount()];rig.copyMeshWeights(m,weights);deformer.updateMesh(m,weights);
                    uploads[m]=new FloatBuffer[deformer.primitives(m).size()];
                    for(int pr=0;pr<uploads[m].length;pr++){uploads[m][pr]=deformer.primitives(m).get(pr).interleaved();bounds.updatePrimitive(m,pr,uploads[m][pr]);}
                }
                check(copy(bounds,worlds(asset,rig),fit,views,16,uv),"actual legal uploaded pose has finite projected bounds");
                assertVertices(asset,rig,uploads,fit,views,16,uv,entry.getString("id")+" actual pose "+p);
                maxArea=Math.max(maxArea,(uv[2]-uv[0])*(uv[3]-uv[1]));poses++;
            }
            System.out.printf(java.util.Locale.ROOT,"%s SHA=%s: %d actual production poses x16 views; all active nodes/primitives/vertices enclosed; max padded UV-rectangle area=%.6f%n",entry.getString("id"),sha,poses,maxArea);
        }
        check(catalog.length()==3,"all three shipped assets examined");
    }
    private static void assertVertices(AvatarAsset asset,AvatarRig rig,FloatBuffer[][] uploads,float[] fit,float[] views,int count,float[] uv,String label){
        float[] world=new float[16];double minU=1,minV=1,maxU=0,maxV=0;long tested=0;
        for(int n=0;n<asset.nodes().size();n++)if(rig.activeNode(n)&&asset.nodes().get(n).meshIndex()>=0){
            rig.copyWorldMatrix(n,world);FloatBuffer[] primitives=uploads[asset.nodes().get(n).meshIndex()];
            for(FloatBuffer buffer:primitives)for(int vertex=buffer.position();vertex<buffer.limit();vertex+=6){
                // Apply matrices in rendering order to actual vertices; no AABB/corner derivation.
                double[] model={buffer.get(vertex),buffer.get(vertex+1),buffer.get(vertex+2),1};
                double[] posed=transform(world,0,model),fitted=transform(fit,0,posed);
                for(int view=0;view<count;view++){
                    double[] clip=transform(views,view*16,fitted);if(!(clip[3]>0))throw new AssertionError(label+" invalid actual W");
                    double u=clip[0]/clip[3]*.5+.5,v=clip[1]/clip[3]*.5+.5;
                    if(u>=0&&u<=1&&v>=0&&v<=1){minU=Math.min(minU,u);minV=Math.min(minV,v);maxU=Math.max(maxU,u);maxV=Math.max(maxV,v);}
                    if(u>=0&&u<=1&&v>=0&&v<=1&&(u<uv[0]||u>uv[2]||v<uv[1]||v>uv[3]))throw new AssertionError(label+" actual vertex excluded");tested++;
                }
            }
        }
        check(tested>0,label+" nonempty vertex oracle");check(minU>=uv[0]&&minV>=uv[1]&&maxU<=uv[2]&&maxV<=uv[3],label+" union encloses all in-screen actual samples");
    }
    private static double[] transform(float[] matrix,int offset,double[] point){double[] result=new double[4];for(int r=0;r<4;r++)for(int c=0;c<4;c++)result[r]+=matrix[offset+c*4+r]*point[c];return result;}
    private static float[] floatTransform(float[] matrix,float[] point){float[] result=new float[4];for(int r=0;r<4;r++)for(int c=0;c<4;c++)result[r]+=matrix[c*4+r]*point[c];return result;}
    private static float[] worlds(AvatarAsset asset,AvatarRig rig){float[] result=new float[asset.nodes().size()*16],one=new float[16];for(int n=0;n<asset.nodes().size();n++)if(rig.activeNode(n)){rig.copyWorldMatrix(n,one);System.arraycopy(one,0,result,n*16,16);}return result;}
    private static FloatBuffer interleaved(float[] xyz){FloatBuffer result=FloatBuffer.allocate(xyz.length*2);for(int v=0;v<xyz.length/3;v++){for(int a=0;a<3;a++)result.put(v*6+a,xyz[v*3+a]);result.put(v*6+5,1);}return result;}
    private static float[] perspective(float eye){float[] result=new float[16];float tanX=.52f*.625f,a=(10+.1f)/(10-.1f),b=2*10*.1f/(10-.1f);result[0]=1/tanX;result[5]=1/.52f;result[8]=-eye/(3*tanX);result[10]=-a;result[11]=-1;result[14]=3*a-b;result[15]=3;return result;}
    private static float[] identity(){return AvatarRigTest.identity();}
    private static float[] nanAt(float[] source,int index){float[] result=source.clone();result[index]=Float.NaN;return result;}
    private static boolean full(float[] value){return Arrays.equals(value,new float[]{0,0,1,1});}
    private static boolean copy(AvatarScreenBounds bounds,float[] worlds,float[] fit,float[] views,int count,float[] dest){return bounds.copyUvBounds(worlds,fit,views,count,VIEW_WIDTH,VIEW_HEIGHT,OUTPUT_WIDTH,OUTPUT_HEIGHT,dest);}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
