package com.mirror.bench;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AvatarRigTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        AvatarAsset asset = fixture(); JSONObject json = manifest();
        AvatarRig rig = new AvatarRig(asset, json.toString());
        float[] values = new float[52], angles = new float[3], matrix = new float[16], weights = new float[3];
        values[9]=.8f; values[10]=.2f; values[25]=.6f;
        rig.update(values, angles); rig.copyMeshWeights(0,weights);
        close(weights[0], .8f); close(weights[1], .2f); close(weights[2], .6f);
        check(!rig.completeSourceCoverage());
        values[15]=1; values[16]=1; values[17]=1;
        rig.update(values,angles);
        rig.copyWorldMatrix(3,matrix); check(matrix[8] > 0); check(matrix[9] > 0);
        rig.copyWorldMatrix(4,matrix); check(matrix[8] < 0); close(matrix[9],0);
        rig.copyWorldMatrix(5,matrix); check(matrix[9] < 0);
        angles[1]=45; rig.update(values,angles);
        rig.copyWorldMatrix(1,matrix); close(matrix[12],0); close(matrix[13],.2f); close(matrix[8],(float)Math.sqrt(.5));
        rig.copyWorldMatrix(2,matrix); close(matrix[0],1); close(matrix[8],0);
        float old = matrix[0]; values[9]=Float.NaN;
        rejects(() -> rig.update(values,angles)); rig.copyWorldMatrix(2,matrix); close(matrix[0],old);
        JSONObject bad = new JSONObject(json.toString()); bad.getJSONArray("bindings").getJSONObject(0).put("source","eyeLookOutLeft");
        final String doubleGaze=bad.toString(); rejects(() -> new AvatarRig(asset,doubleGaze));
        bad=new JSONObject(json.toString()); bad.getJSONArray("bindings").put(bad.getJSONArray("bindings").getJSONObject(0));
        final String duplicate=bad.toString(); rejects(() -> new AvatarRig(asset,duplicate));
        bad=new JSONObject(json.toString()); bad.getJSONObject("rig").put("headNode","Shoulders");
        final String wrongParent=bad.toString(); rejects(() -> new AvatarRig(asset,wrongParent));
        bad=new JSONObject(json.toString()); bad.getJSONObject("coordinates").put("headPivot",new JSONArray("[0,99,0]"));
        final String wrongPivot=bad.toString(); rejects(() -> new AvatarRig(asset,wrongPivot));
        bad=new JSONObject(json.toString()); bad.getJSONArray("bindings").getJSONObject(0).put("gain","1");
        final String coerced=bad.toString(); rejects(() -> new AvatarRig(asset,coerced));
        bad=new JSONObject(json.toString()); bad.getJSONArray("bindings").getJSONObject(0).put("gamma",0);
        final String badGamma=bad.toString(); rejects(() -> new AvatarRig(asset,badGamma));
        var nodes=new ArrayList<>(asset.nodes()); nodes.add(new AvatarAsset.Node("Head",-1,new int[0],identity(),identity(),new float[0]));
        var ambiguous=new AvatarAsset(asset.meshes(),nodes,asset.materials(),new int[]{0,7},3,1,100);
        rejects(() -> new AvatarRig(ambiguous,json.toString()));
        System.out.println("AvatarRigTest: "+checks+" assertions passed");
    }
    static AvatarAsset fixture() {
        float[] positions={0,0,0,1,0,0,0,1,0};
        var morphs=List.of(new AvatarAsset.Morph(null,null),new AvatarAsset.Morph(null,null),new AvatarAsset.Morph(null,null));
        var primitive=new AvatarAsset.Primitive(positions,null,null,null,new int[]{0,1,2},morphs,0);
        var mesh=new AvatarAsset.Mesh("Face",List.of(primitive),List.of("eyeBlinkLeft","eyeBlinkRight","jawOpen"),new float[3]);
        var nodes=List.of(node("Root",-1,new int[]{1,2},0,0,0,0,0,0),
            node("Head",-1,new int[]{3,4,5,6},0,.2f,0,0,.2f,0),
            node("Shoulders",-1,new int[0],0,0,0,0,0,0),
            node("EyeLeft",-1,new int[0],.04f,.1f,.06f,.04f,.3f,.06f),
            node("EyeRight",-1,new int[0],-.04f,.1f,.06f,-.04f,.3f,.06f),
            node("JawAttachments",-1,new int[0],0,.05f,0,0,.25f,0),
            node("Face",0,new int[0],0,0,0,0,.2f,0));
        return new AvatarAsset(List.of(mesh),nodes,List.of(new AvatarAsset.Material("skin",new float[]{1,1,1,1},1,false)),new int[]{0},3,1,100);
    }
    private static AvatarAsset.Node node(String name,int mesh,int[] children,float x,float y,float z,float wx,float wy,float wz) {
        float[] local=identity(),world=identity();local[12]=x;local[13]=y;local[14]=z;world[12]=wx;world[13]=wy;world[14]=wz;
        return new AvatarAsset.Node(name,mesh,children,local,world,new float[0]);
    }
    static float[] identity(){return new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};}
    static JSONObject manifest() throws Exception {
        return new JSONObject("""
          {"schemaVersion":1,"id":"test","displayName":"Test","model":"character.glb",
          "inputSchema":"mediapipe-face-blendshapes-v1","normalPolicy":"recompute-deformed",
          "coordinates":{"up":"+Y","forward":"+Z","subjectLeft":"+X","units":"meters","rootScale":1,"headPivot":[0,0.2,0]},
          "rig":{"headNode":"Head","jawMode":"morph","jawAttachmentNode":"JawAttachments","jawAxis":[1,0,0],
          "jawOpenDegrees":22,"jawLateralMeters":0.00845,"jawForwardMeters":0.0078,
          "leftEyeNode":"EyeLeft","rightEyeNode":"EyeRight","gazeMode":"joint","gazeYawDegrees":18,"gazePitchDegrees":15},
          "bindings":[{"source":"eyeBlinkLeft","mesh":"Face","target":"eyeBlinkLeft","gain":1},
          {"source":"eyeBlinkRight","mesh":"Face","target":"eyeBlinkRight","gain":1},
          {"source":"jawOpen","mesh":"Face","target":"jawOpen","gain":1}],"ignoredSources":["_neutral"]}
          """);
    }
    private static void check(boolean ok){checks++;if(!ok)throw new AssertionError("check "+checks);}
    private static void close(float a,float b){check(Math.abs(a-b)<1e-5f);}
    private interface Checked{void run()throws Exception;}
    private static void rejects(Checked action)throws Exception{try{action.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError("expected rejection");}
}
