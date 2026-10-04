package com.mirror.bench;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Integration through the real rig/deformer, including scaled hierarchies and simultaneous controls. */
public final class AvatarGeometryBoundsTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        AvatarAsset asset=fixture(false);JSONObject config=config(asset);
        AvatarRig rig=new AvatarRig(asset,config.toString());
        float[] input=new float[52];input[9]=.4f;input[25]=.8f;rig.update(input,new float[]{20,30,-40});
        float[] before=new float[16],after=new float[16],weights=new float[4],newWeights=new float[4];
        rig.copyWorldMatrix(3,before);rig.copyMeshWeights(0,weights);
        AvatarFraming fit=AvatarGeometryBounds.fromAsset(asset,rig);
        rig.copyWorldMatrix(3,after);rig.copyMeshWeights(0,newWeights);
        check(Arrays.equals(before,after)&&Arrays.equals(weights,newWeights),"factory preserves live rig state");
        validate(asset,rig,fit,"simple hierarchy");
        JSONObject scaled=new JSONObject(config.toString());scaled.getJSONObject("coordinates").put("rootScale",37);
        AvatarFraming units=AvatarGeometryBounds.fromAsset(asset,new AvatarRig(asset,scaled.toString()));
        check(fit.scale(.625f)==units.scale(.625f),"rootScale is unit conversion, not a post-fit zoom");
        rejects(()->AvatarGeometryBounds.fromAsset(fixture(false),rig),"mismatched asset and rig");
        rejects(()->AvatarGeometryBounds.fromAsset(asset,null),"missing rig");
        AvatarAsset nonuniform=fixture(true);JSONObject nonuniformConfig=config(nonuniform);
        // The rig accepts a near-unit axis; its unnormalized Rodrigues matrix is not exactly orthogonal.
        nonuniformConfig.getJSONObject("rig").put("jawAxis",new JSONArray("[1.0009,0,0]"));
        AvatarRig scaledRig=new AvatarRig(nonuniform,nonuniformConfig.toString());
        validate(nonuniform,scaledRig,AvatarGeometryBounds.fromAsset(nonuniform,scaledRig),"nonuniform rotated ancestors");
        System.out.println("AvatarGeometryBoundsTest passed: "+checks+" assertions");
        for(String p:args) {
            Path model=Path.of(p);AvatarAsset real=AvatarGlbLoader.load(Files.readAllBytes(model));
            AvatarRig realRig=new AvatarRig(real,Files.readString(model.resolveSibling("avatar.json")));
            AvatarFraming realFit=AvatarGeometryBounds.fromAsset(real,realRig);
            check(realFit.scale(.625f)>3.30f&&realFit.scale(.625f)<3.316f,"actual automatic envelope remains tight");
            validate(real,realRig,realFit,"actual GLB");
            System.out.println("Automatic asset framing scale="+realFit.scale(.625f)+" at aspect .625");
        }
    }
    private static void validate(AvatarAsset asset,AvatarRig rig,AvatarFraming framing,String label) {
        AvatarDeformer deformer=new AvatarDeformer(asset,AvatarDeformer.NormalPolicy.RECOMPUTE_DEFORMED);
        float[] fit=new float[16],world=new float[16];framing.copyFitMatrix(.625f,fit);
        float[][] meshWeights=new float[asset.meshes().size()][];
        for(int m=0;m<meshWeights.length;m++)meshWeights[m]=new float[asset.meshes().get(m).targetCount()];
        double largest=0;int poses=0;
        for(int variant=0;variant<8;variant++) {
            float[] values=new float[52];
            if(variant==1)Arrays.fill(values,1);
            if(variant>=2) {
                values[9]=(variant&1)==0?1:0;values[10]=(variant&1)==1?1:0;
                values[25]=1;values[23]=(variant&2)==0?1:0;values[(variant&1)==0?24:26]=1;
                values[(variant&2)==0?11:17]=1;values[(variant&2)==0?18:12]=1;
                values[(variant&4)==0?15:13]=1;values[(variant&4)==0?14:16]=1;
            }
            for(int pitch=-90;pitch<=90;pitch+=90)for(int yaw=-90;yaw<=90;yaw+=90)for(int roll=-90;roll<=90;roll+=90) {
                rig.update(values,new float[]{pitch,yaw,roll});
                for(int m=0;m<meshWeights.length;m++){rig.copyMeshWeights(m,meshWeights[m]);deformer.updateMesh(m,meshWeights[m]);}
                double max=0;
                for(int n=0;n<asset.nodes().size();n++)if(rig.activeNode(n)&&asset.nodes().get(n).meshIndex()>=0) {
                    rig.copyWorldMatrix(n,world);int m=asset.nodes().get(n).meshIndex();
                    for(var primitive:deformer.primitives(m)) {
                        FloatBuffer points=primitive.positions();
                        for(int v=0;v<points.limit();v+=3) {
                            double x=world[0]*points.get(v)+world[4]*points.get(v+1)+world[8]*points.get(v+2)+world[12];
                            double y=world[1]*points.get(v)+world[5]*points.get(v+1)+world[9]*points.get(v+2)+world[13];
                            double z=world[2]*points.get(v)+world[6]*points.get(v+1)+world[10]*points.get(v+2)+world[14];
                            x=fit[0]*x+fit[12];y=fit[5]*y+fit[13];z=fit[10]*z+fit[14];
                            double depth=3-z;if(depth<.09999||depth>10.00001)throw new AssertionError(label+" depth clipped");
                            for(int eye=-1;eye<=1;eye++)max=Math.max(max,Math.max(Math.abs(x-eye*.2*z/3)/(depth*.52*.625),Math.abs(y)/(depth*.52)));
                        }
                    }
                }
                if(max>.920001)throw new AssertionError(label+" clipped "+variant+"/"+pitch+","+yaw+","+roll+": "+max);
                largest=Math.max(largest,max);poses++;
            }
        }
        check(poses==216,label+" complete combinations");
        System.out.println(label+": "+poses+" simultaneous head/morph/gaze/jaw poses x 3 views, maxNDC="+largest);
    }
    private static AvatarAsset fixture(boolean scaled) {
        float[] face={0,0,0,.08f,.12f,.03f,-.09f,.11f,-.025f};
        var morphs=List.of(new AvatarAsset.Morph(new float[]{.12f,0,0,0,.08f,0,0,0,0},null),
                new AvatarAsset.Morph(new float[]{0,0,.03f,-.15f,0,0,0,0,0},null),
                new AvatarAsset.Morph(new float[]{0,-.1f,.02f,0,0,0,0,0,0},null),
                new AvatarAsset.Morph(new float[]{0,0,-.04f,0,0,0,0,0,0},null));
        var skin=new AvatarAsset.Mesh("Face",List.of(new AvatarAsset.Primitive(face,null,null,null,new int[]{0,1,2},morphs,0)),
                List.of("eyeBlinkLeft","eyeBlinkRight","jawOpen","unbound"),new float[]{0,0,0,-2});
        var rigid=new AvatarAsset.Mesh("Rigid",List.of(new AvatarAsset.Primitive(new float[]{-.05f,0,.02f,.05f,0,.02f,0,.04f,-.03f},null,null,null,new int[]{0,1,2},List.of(),0)),List.of(),new float[0]);
        String[] names={"Root","Head","Shoulders","EyeLeft","EyeRight","JawAttachments","Face"};
        int[][] kids={{1,2},{3,4,5,6},{},{},{},{},{}};int[] parent={-1,0,0,1,1,1,1},mesh={-1,-1,1,1,1,1,0};
        float[][] local=new float[7][],world=new float[7][];
        for(int n=0;n<7;n++)local[n]=AvatarRigTest.identity();
        local[1][13]=.2f;local[2][13]=-.08f;
        local[3][12]=.04f;local[3][13]=.1f;local[3][14]=.06f;
        local[4][12]=-.04f;local[4][13]=.1f;local[4][14]=.06f;local[5][13]=.05f;
        if(scaled) {
            AvatarRig.rotation(local[0],0,20,15);multiplyScale(local[0],1.4f,.6f,2.1f);
            multiplyScale(local[1],.75f,1.6f,.8f);
            float[] eyeRotation=new float[16];AvatarRig.rotation(eyeRotation,0,0,30);float[] temp=new float[16];
            AvatarRig.multiply(temp,0,local[3],0,eyeRotation,0);local[3]=temp;multiplyScale(local[3],2,.5f,1);
        }
        var nodes=new ArrayList<AvatarAsset.Node>();
        for(int n=0;n<7;n++) {
            world[n]=new float[16];if(parent[n]<0)System.arraycopy(local[n],0,world[n],0,16);else AvatarRig.multiply(world[n],0,world[parent[n]],0,local[n],0);
            nodes.add(new AvatarAsset.Node(names[n],mesh[n],kids[n],local[n],world[n],new float[0]));
        }
        return new AvatarAsset(List.of(skin,rigid),nodes,List.of(new AvatarAsset.Material("opaque",new float[]{1,1,1,1},1,false)),new int[]{0},15,5,1000);
    }
    private static void multiplyScale(float[] m,float x,float y,float z){for(int i=0;i<4;i++){m[i]*=x;m[4+i]*=y;m[8+i]*=z;}}
    private static JSONObject config(AvatarAsset a)throws Exception {
        JSONObject config=AvatarRigTest.manifest();FloatBuffer w=a.nodes().get(1).worldMatrix();
        config.getJSONObject("coordinates").put("headPivot",new JSONArray(new float[]{w.get(12),w.get(13),w.get(14)}));return config;
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    private static void rejects(Runnable action,String message){try{action.run();throw new AssertionError("Expected rejection: "+message);}catch(IllegalArgumentException expected){checks++;}}
}
