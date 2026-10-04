package com.mirror.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.json.JSONArray;
import org.json.JSONObject;

/** Independent Blender oracle: execute production playback and rig, not a Python reimplementation. */
public final class BlenderFaceControlReference {
    private static final JSONArray cases=new JSONArray();
    private static AvatarAsset asset;
    private static AvatarRig rig;
    private static void capture(String name,float[] source,float[] pose,boolean mirror,float gain) {
        float[] weights=new float[52],angles=new float[3];
        FacePlayback.apply(source,pose,mirror,gain,weights,angles);
        rig.update(weights,angles);
        JSONArray meshes=new JSONArray(),nodes=new JSONArray();
        for(int i=0;i<asset.meshes().size();i++) {
            float[] targets=new float[asset.meshes().get(i).targetCount()];rig.copyMeshWeights(i,targets);
            meshes.put(new JSONObject().put("name",asset.meshes().get(i).name())
                    .put("targetNames",asset.meshes().get(i).targetNames()).put("weights",targets));
        }
        for(int i=0;i<asset.nodes().size();i++) {
            float[] matrix=new float[16];rig.copyWorldMatrix(i,matrix);
            nodes.put(new JSONObject().put("name",asset.nodes().get(i).name()).put("worldMatrixColumnMajor",matrix));
        }
        cases.put(new JSONObject().put("name",name).put("source",source).put("headAngles",pose)
                .put("mirror",mirror).put("gain",gain).put("meshes",meshes).put("nodes",nodes));
    }
    public static void main(String[] args)throws Exception {
        Path directory=Path.of(args[0]),output=Path.of(args[1]);
        if(Files.exists(output))throw new IllegalArgumentException("Reference output already exists");
        byte[] model=Files.readAllBytes(directory.resolve("character.glb"));
        asset=AvatarGlbLoader.load(model);rig=new AvatarRig(asset,Files.readString(directory.resolve("avatar.json")));
        if(!rig.completeSourceCoverage())throw new AssertionError("Incomplete production rig");
        JSONArray names=new JSONArray();for(int i=0;i<52;i++)names.put(BlendshapeSchema.name(i));
        for(int i=0;i<52;i++) {
            float[] input=new float[52];input[i]=.37f;
            capture("single-"+names.getString(i),input,new float[3],false,1);
            capture("mirrored-"+names.getString(i),input,new float[3],true,2.5f);
        }
        for(int sample=0;sample<18;sample++) {
            float[] input=new float[52];
            for(int i=0;i<52;i++)input[i]=((sample*17+i*11)%29)/28f;
            float[] pose={(sample-8)*6,(8-sample)*4,(sample%7-3)*9};
            for(float gain:new float[]{.5f,1,2.5f,4})for(boolean mirror:new boolean[]{false,true})
                capture("mixed-"+sample+"-"+gain+"-"+mirror,input,pose,mirror,gain);
        }
        for(String side:new String[]{"Left","Right"})for(String extra:new String[]{"eyeSquint","eyeWide"}) {
            float[] input=new float[52];input[BlendshapeSchema.indexOf("eyeBlink"+side)]=1;
            input[BlendshapeSchema.indexOf(extra+side)]=1;
            capture("blink-"+extra+side,input,new float[3],false,2.5f);
        }
        float[] jaw=new float[52];jaw[25]=1;jaw[27]=1;
        capture("jaw-close-endpoint",jaw,new float[3],false,2.5f);
        JSONObject result=new JSONObject().put("schemaVersion",1).put("inputNames",names)
                .put("mirrorPermutation",FaceControlMapper.mirrorPermutation()).put("cases",cases)
                .put("modelSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(model)))
                .put("scope","Filtered coefficient controls through actual FacePlayback and AvatarRig; no detector, temporal filter, lighting or optical parity claim");
        Files.writeString(output,result.toString(2));
        System.out.println("Exported "+cases.length()+" actual production control states");
    }
}
