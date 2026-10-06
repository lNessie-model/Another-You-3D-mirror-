package com.mirror.bench;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import org.json.JSONObject;

/** Actual production rig/deformer vertices must fit; does not duplicate the bounds implementation. */
public final class CameraPreviewFramingTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Actual APK assets root required");
        Path root=Path.of(args[0]);
        JSONObject catalog=new JSONObject(Files.readString(root.resolve("avatars/catalog/catalog.json")));
        var entries=catalog.getJSONArray("entries");int avatars=0,totalPoses=0;
        for(int item=0;item<entries.length();item++){
            JSONObject entry=entries.getJSONObject(item);Path directory=root.resolve(entry.getString("directory"));
            byte[] bytes=Files.readAllBytes(directory.resolve("character.glb"));String manifest=Files.readString(directory.resolve("avatar.json"));
            String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            check(sha.equals(entry.getString("modelSha256")),"actual catalog GLB digest");
            AvatarAsset asset=AvatarGlbLoader.load(bytes);AvatarRig rig=new AvatarRig(asset,manifest);
            CameraPreviewFraming camera=CameraPreviewFraming.fromAsset(asset,manifest);
            float[] fit=new float[16];AvatarGeometryBounds.fromAsset(asset,rig).copyFitMatrix(.625f,fit);
            AvatarDeformer deformer=new AvatarDeformer(asset,AvatarDeformer.NormalPolicy.fromManifest(rig.normalPolicy()));
            float[][] weights=new float[asset.meshes().size()][];
            for(int m=0;m<weights.length;m++)weights[m]=new float[asset.meshes().get(m).targetCount()];
            boolean[] headNodes=headNodes(asset,rig.headNodeIndex());float[] vp=new float[16],world=new float[16];
            List<float[]> expressions=expressions();double maximum=0;int poses=0;
            double neutralWidth=0,neutralHeight=0,neutralCenterX=0,neutralCenterY=0;
            for(int e=0;e<expressions.size();e++){
                // Update normals/geometry using the actual runtime operator, once per expression.
                rig.update(expressions.get(e),new float[3]);
                for(int m=0;m<weights.length;m++){rig.copyMeshWeights(m,weights[m]);deformer.updateMesh(m,weights[m]);}
                for(float[] angles:angles()){
                    rig.update(expressions.get(e),angles);
                    camera.copyViewProjection(angles,186,297,vp);
                    double[] extent=project(asset,rig,deformer,headNodes,fit,vp,world);
                    check(extent[0]>=-.92001&&extent[1]>=-.92001&&extent[2]<=.92001&&extent[3]<=.92001,
                        entry.getString("id")+" actual head clipped at expression "+e+" angles "+Arrays.toString(angles)+" "+Arrays.toString(extent));
                    maximum=Math.max(maximum,Math.max(Math.max(-extent[0],-extent[1]),Math.max(extent[2],extent[3])));
                    if(e==0&&Arrays.equals(angles,new float[3])){
                        neutralWidth=(extent[2]-extent[0])/2;neutralHeight=(extent[3]-extent[1])/2;
                        neutralCenterX=(extent[2]+extent[0])/2;neutralCenterY=(extent[3]+extent[1])/2;
                    }
                    poses++;
                }
            }
            check(neutralWidth>=.60&&neutralHeight>=.60,"neutral head occupies majority of both preview dimensions");
            check(Math.abs(neutralCenterX)<.10&&Math.abs(neutralCenterY)<.10,"neutral head naturally centered");
            float[] unchanged=new float[16];camera.copyViewProjection(new float[]{12,-27,8},186,297,unchanged);
            rig.update(expressions.get(1),new float[]{12,-27,8});camera.copyViewProjection(new float[]{12,-27,8},186,297,vp);
            check(Arrays.equals(unchanged,vp),"blink/mouth weights cannot zoom or move camera");
            for(int[] box:new int[][]{{340,544},{400,900},{1000,800},{191,307}}){
                int[] viewport=CameraPreviewPose.viewport(box[0],box[1]);camera.copyViewProjection(new float[3],viewport[2],viewport[3],vp);
                rig.update(new float[52],new float[3]);for(int m=0;m<weights.length;m++){rig.copyMeshWeights(m,weights[m]);deformer.updateMesh(m,weights[m]);}
                double[] extent=project(asset,rig,deformer,headNodes,fit,vp,world);
                check(extent[0]>=-.92001&&extent[1]>=-.92001&&extent[2]<=.92001&&extent[3]<=.92001,"integer viewport aspect covered");
            }
            // rootScale is the established coordinate-unit metadata, never an extra preview zoom.
            JSONObject units=new JSONObject(manifest);units.getJSONObject("coordinates").put("rootScale",37);
            CameraPreviewFraming otherUnits=CameraPreviewFraming.fromAsset(asset,units.toString());
            float[] unitMatrix=new float[16];otherUnits.copyViewProjection(new float[3],186,297,unitMatrix);
            camera.copyViewProjection(new float[3],186,297,vp);check(Arrays.equals(vp,unitMatrix),"coordinate metadata does not cause extra zoom");
            rejects(()->camera.copyViewProjection(null,186,297,new float[16]),"missing angles");
            rejects(()->camera.copyViewProjection(new float[]{Float.NaN,0,0},186,297,new float[16]),"nonfinite angles");
            rejects(()->camera.copyViewProjection(new float[]{91,0,0},186,297,new float[16]),"outside rig angles");
            rejects(()->camera.copyViewProjection(new float[3],0,297,new float[16]),"empty viewport");
            rejects(()->camera.copyViewProjection(new float[3],186,297,new float[15]),"short matrix");
            System.out.printf(java.util.Locale.ROOT,"%s SHA=%s: neutral width=%.6f height=%.6f center=(%.6f,%.6f), %d production poses, maxAbsNdc=%.6f%n",
                entry.getString("id"),sha,neutralWidth,neutralHeight,neutralCenterX,neutralCenterY,poses,maximum);
            avatars++;totalPoses+=poses;
        }
        check(avatars==3,"all three shipped calibration roles actually examined");
        System.out.println("PASS: "+checks+" framing checks, "+totalPoses+" actual production rig/deformer poses; no EGL/HAL/artwork claim");
    }
    private static List<float[]> expressions(){
        List<float[]> result=new ArrayList<>();result.add(new float[52]);float[] all=new float[52];Arrays.fill(all,1);result.add(all);
        for(int channel=1;channel<52;channel++){float[] values=new float[52];values[channel]=1;result.add(values);}
        for(String[] keys:new String[][]{{"eyeBlinkLeft","eyeBlinkRight","browInnerUp"},{"jawOpen","mouthSmileLeft","mouthSmileRight"},
            {"jawOpen","mouthClose"},{"jawOpen","jawLeft","jawForward"},{"jawOpen","jawRight","mouthPucker"}}){
            float[] values=new float[52];for(String key:keys)values[BlendshapeSchema.indexOf(key)]=1;result.add(values);
        }
        Random random=new Random(412997);for(int i=0;i<8;i++){float[] values=new float[52];for(int c=1;c<52;c++)values[c]=random.nextFloat();result.add(values);}
        return result;
    }
    private static List<float[]> angles(){
        List<float[]> result=new ArrayList<>();result.add(new float[3]);
        for(int pitch:new int[]{-45,0,45})for(int yaw:new int[]{-65,0,65})for(int roll:new int[]{-40,0,40})
            if(pitch!=0||yaw!=0||roll!=0)result.add(new float[]{pitch,yaw,roll});
        result.add(new float[]{12,-27,8});result.add(new float[]{-22,34,-17});return result;
    }
    private static boolean[] headNodes(AvatarAsset asset,int head){
        boolean[] result=new boolean[asset.nodes().size()];mark(asset,head,result);return result;
    }
    private static void mark(AvatarAsset asset,int node,boolean[] result){result[node]=true;var children=asset.nodes().get(node).children();while(children.hasRemaining())mark(asset,children.get(),result);}
    private static double[] project(AvatarAsset asset,AvatarRig rig,AvatarDeformer deformer,boolean[] heads,float[] fit,float[] vp,float[] world){
        double[] extent={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        int vertices=0;
        for(int n=0;n<heads.length;n++)if(heads[n]&&rig.activeNode(n)&&asset.nodes().get(n).meshIndex()>=0){
            rig.copyWorldMatrix(n,world);
            for(var primitive:deformer.primitives(asset.nodes().get(n).meshIndex())){
                FloatBuffer points=primitive.positions();for(int v=0;v<points.limit();v+=3){
                    double x=points.get(v),y=points.get(v+1),z=points.get(v+2);
                    double wx=fit[0]*(world[0]*x+world[4]*y+world[8]*z+world[12])+fit[12];
                    double wy=fit[5]*(world[1]*x+world[5]*y+world[9]*z+world[13])+fit[13];
                    double wz=fit[10]*(world[2]*x+world[6]*y+world[10]*z+world[14])+fit[14];
                    double w=vp[3]*wx+vp[7]*wy+vp[11]*wz+vp[15];
                    double nx=(vp[0]*wx+vp[4]*wy+vp[8]*wz+vp[12])/w,ny=(vp[1]*wx+vp[5]*wy+vp[9]*wz+vp[13])/w;
                    double nz=(vp[2]*wx+vp[6]*wy+vp[10]*wz+vp[14])/w;
                    if(!(w>0)||!Double.isFinite(nx)||!Double.isFinite(ny)||!Double.isFinite(nz)||nz< -1.00001||nz>1.00001)
                        throw new AssertionError("actual vertex behind/clipped by depth planes");
                    extent[0]=Math.min(extent[0],nx);extent[1]=Math.min(extent[1],ny);extent[2]=Math.max(extent[2],nx);extent[3]=Math.max(extent[3],ny);vertices++;
                }
            }
        }
        if(vertices==0)throw new AssertionError("empty geometry would not constitute a coverage check");return extent;
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void rejects(Runnable action,String message){try{action.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){checks++;}}
}
