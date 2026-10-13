package com.mirror.bench;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host contract for static torso + head rotation with a neck correction morph. */
public final class AvatarHeadPoseCorrectionTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        AvatarAsset asset = fixture();
        AvatarRig rig = new AvatarRig(asset, manifest().toString());
        float[] inputs = new float[52];
        float[] weights = new float[6];

        rig.update(inputs, new float[]{0, 15, 0});
        rig.copyMeshWeights(0, weights);
        close(weights[3], .5f); close(weights[4], 0); close(weights[5], 0);

        rig.update(inputs, new float[]{10, -30, 0});
        rig.copyMeshWeights(0, weights);
        close(weights[3], 0); close(weights[4], 1); close(weights[5], .5f);

        rig.update(inputs, new float[]{0, 0, 0});
        rig.copyMeshWeights(0, weights);
        for (float value : weights) close(value, 0);

        JSONObject bad = new JSONObject(manifest().toString());
        bad.getJSONArray("headPoseCorrections").getJSONObject(0).put("axis", "bad");
        rejects(() -> new AvatarRig(asset, bad.toString()));
        System.out.println("AvatarHeadPoseCorrectionTest: " + checks + " assertions passed");
    }

    private static AvatarAsset fixture() {
        float[] positions={0,0,0,1,0,0,0,1,0};
        var morphs=List.of(
                new AvatarAsset.Morph(null,null), new AvatarAsset.Morph(null,null), new AvatarAsset.Morph(null,null),
                new AvatarAsset.Morph(new float[9],null), new AvatarAsset.Morph(new float[9],null),
                new AvatarAsset.Morph(new float[9],null));
        var names=List.of("eyeBlinkLeft","eyeBlinkRight","jawOpen","neckYawPositive","neckYawNegative","neckPitchPositive");
        var primitive=new AvatarAsset.Primitive(positions,null,null,null,new int[]{0,1,2},morphs,0);
        var mesh=new AvatarAsset.Mesh("HeadWithNeck",List.of(primitive),names,new float[6]);
        var nodes=List.of(
                node("Root",-1,new int[]{1,2},0,0,0,0,0,0),
                node("Head",-1,new int[]{3,4,5,6},0,.2f,0,0,.2f,0),
                node("Body",-1,new int[0],0,0,0,0,0,0),
                node("EyeLeft",-1,new int[0],.04f,.1f,.06f,.04f,.3f,.06f),
                node("EyeRight",-1,new int[0],-.04f,.1f,.06f,-.04f,.3f,.06f),
                node("JawAttachments",-1,new int[0],0,.05f,0,0,.25f,0),
                node("HeadWithNeck",0,new int[0],0,0,0,0,.2f,0));
        return new AvatarAsset(List.of(mesh),nodes,
                List.of(new AvatarAsset.Material("skin",new float[]{1,1,1,1},1,false)),
                new int[]{0},3,1,100);
    }

    private static JSONObject manifest() throws Exception {
        return new JSONObject("""
          {"schemaVersion":1,"id":"head-pose-test","displayName":"Head pose test","model":"character.glb",
          "inputSchema":"mediapipe-face-blendshapes-v1","normalPolicy":"recompute-deformed",
          "coordinates":{"up":"+Y","forward":"+Z","subjectLeft":"+X","units":"meters","rootScale":1,"headPivot":[0,0.2,0]},
          "rig":{"headNode":"Head","jawMode":"morph","jawAttachmentNode":"JawAttachments","jawAxis":[1,0,0],
          "jawOpenDegrees":22,"jawLateralMeters":0.00845,"jawForwardMeters":0.0078,
          "leftEyeNode":"EyeLeft","rightEyeNode":"EyeRight","gazeMode":"joint","gazeYawDegrees":18,"gazePitchDegrees":15},
          "bindings":[{"source":"eyeBlinkLeft","mesh":"HeadWithNeck","target":"eyeBlinkLeft","gain":1},
          {"source":"eyeBlinkRight","mesh":"HeadWithNeck","target":"eyeBlinkRight","gain":1},
          {"source":"jawOpen","mesh":"HeadWithNeck","target":"jawOpen","gain":1}],
          "headPoseCorrections":[
            {"axis":"yaw","sign":1,"mesh":"HeadWithNeck","target":"neckYawPositive","maxDegrees":30},
            {"axis":"yaw","sign":-1,"mesh":"HeadWithNeck","target":"neckYawNegative","maxDegrees":30},
            {"axis":"pitch","sign":1,"mesh":"HeadWithNeck","target":"neckPitchPositive","maxDegrees":20}],
          "ignoredSources":["_neutral"]}
          """);
    }

    private static AvatarAsset.Node node(String name,int mesh,int[] children,float x,float y,float z,float wx,float wy,float wz) {
        float[] local=identity(),world=identity();
        local[12]=x; local[13]=y; local[14]=z; world[12]=wx; world[13]=wy; world[14]=wz;
        return new AvatarAsset.Node(name,mesh,children,local,world,new float[0]);
    }
    private static float[] identity(){return new float[]{1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};}
    private static void close(float actual,float expected){checks++;if(Math.abs(actual-expected)>1e-5f)throw new AssertionError(actual+" != "+expected);}
    private interface Checked{void run()throws Exception;}
    private static void rejects(Checked action)throws Exception{try{action.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError("expected rejection");}
}
