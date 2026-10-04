package com.mirror.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline artwork snapshots from the actual production rig, including joint gaze and products. */
public final class AvatarPoseExport {
    private static JSONObject pose(String name,Object... controls) {
        JSONObject values=new JSONObject();
        for(int i=0;i<controls.length;i+=2)values.put((String)controls[i],controls[i+1]);
        return new JSONObject().put("name",name).put("inputs",values);
    }
    public static void main(String[] args)throws Exception {
        Path directory=Path.of(args[0]),output=Path.of(args[1]);
        if(Files.exists(output))throw new IllegalArgumentException("Preserve previous pose evidence");
        byte[] bytes=Files.readAllBytes(directory.resolve("character.glb"));
        AvatarAsset asset=AvatarGlbLoader.load(bytes);AvatarRig rig=new AvatarRig(asset,Files.readString(directory.resolve("avatar.json")));
        if(!rig.completeSourceCoverage())throw new AssertionError("Missing sources: "+rig.missingSources());
        JSONObject[] poses={pose("neutral"),pose("blink-half","eyeBlinkLeft",.5,"eyeBlinkRight",.5),
                pose("blink-left","eyeBlinkLeft",1),pose("blink-right","eyeBlinkRight",1),pose("blink-both","eyeBlinkLeft",1,"eyeBlinkRight",1),
                pose("squint","eyeSquintLeft",1,"eyeSquintRight",1),pose("wide","eyeWideLeft",1,"eyeWideRight",1),
                pose("blink-squint","eyeBlinkLeft",1,"eyeBlinkRight",1,"eyeSquintLeft",1,"eyeSquintRight",1),
                pose("blink-wide","eyeBlinkLeft",1,"eyeBlinkRight",1,"eyeWideLeft",1,"eyeWideRight",1),
                pose("gaze-left","eyeLookOutLeft",1,"eyeLookInRight",1),pose("gaze-right","eyeLookInLeft",1,"eyeLookOutRight",1),
                pose("gaze-up","eyeLookUpLeft",1,"eyeLookUpRight",1),pose("gaze-down","eyeLookDownLeft",1,"eyeLookDownRight",1),
                pose("gaze-blink","eyeLookOutLeft",1,"eyeLookInRight",1,"eyeBlinkLeft",1,"eyeBlinkRight",1),
                pose("jaw-open","jawOpen",1),pose("jaw-open-mouth-close","jawOpen",1,"mouthClose",1),
                pose("smile-jaw","mouthSmileLeft",1,"mouthSmileRight",1,"jawOpen",.65),
                pose("pucker","mouthPucker",1),pose("brow-up","browInnerUp",1)};
        JSONArray snapshots=new JSONArray();
        for(JSONObject pose:poses) {
            float[] input=new float[52];JSONObject values=pose.getJSONObject("inputs");
            for(String name:values.keySet()) {int index=BlendshapeSchema.indexOf(name);if(index<1)throw new AssertionError(name);input[index]=values.getFloat(name);}
            rig.update(input,new float[3]);JSONArray meshes=new JSONArray(),nodes=new JSONArray();
            for(int m=0;m<asset.meshes().size();m++) {
                float[] weights=new float[asset.meshes().get(m).targetCount()];rig.copyMeshWeights(m,weights);
                meshes.put(new JSONObject().put("name",asset.meshes().get(m).name()).put("targetNames",asset.meshes().get(m).targetNames()).put("weights",weights));
            }
            for(int n=0;n<asset.nodes().size();n++) {
                float[] matrix=new float[16];rig.copyWorldMatrix(n,matrix);
                for(float value:matrix)if(!Float.isFinite(value))throw new AssertionError("Nonfinite world matrix");
                nodes.put(new JSONObject().put("name",asset.nodes().get(n).name()).put("mesh",asset.nodes().get(n).meshIndex()).put("worldMatrixColumnMajor",matrix));
            }
            pose.put("meshes",meshes).put("nodes",nodes);snapshots.put(pose);
        }
        JSONObject report=new JSONObject().put("schemaVersion",1).put("modelSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)))
                .put("completeSourceCoverage",true).put("poses",snapshots).put("scope","Actual production AvatarRig snapshots for offline art rendering; not device output or visual acceptance");
        Files.writeString(output,report.toString(2));System.out.println("Exported "+snapshots.length()+" production-rig poses to "+output);
    }
}
