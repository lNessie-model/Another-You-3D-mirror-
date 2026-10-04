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
        boolean playback=args.length==3&&args[2].equals("--feedback");
        if(args.length!=2&&!playback)throw new IllegalArgumentException("Expected asset, output and optional --feedback");
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
        if(playback)poses=new JSONObject[]{pose("neutral"),pose("brow-rest","browDownLeft",.08,"browDownRight",.08),
                pose("brow-mixed","browDownLeft",.65,"browDownRight",.65,"browInnerUp",.25),
                pose("brow-up","browDownLeft",.4,"browDownRight",.4,"browInnerUp",.65,"browOuterUpLeft",.3,"browOuterUpRight",.3),
                pose("smile-mild","mouthSmileLeft",.3,"mouthSmileRight",.3,"cheekSquintLeft",.15,"cheekSquintRight",.15),
                pose("smile","mouthSmileLeft",.65,"mouthSmileRight",.65,"cheekSquintLeft",.3,"cheekSquintRight",.3),
                pose("smile-jaw","mouthSmileLeft",.65,"mouthSmileRight",.65,"jawOpen",.35,"cheekSquintLeft",.3,"cheekSquintRight",.3),
                pose("jaw-half","jawOpen",.3),pose("jaw-open","jawOpen",.85),pose("jaw-open-mouth-close","jawOpen",.85,"mouthClose",1),
                pose("blink-half","eyeBlinkLeft",.35,"eyeBlinkRight",.35),pose("blink-both","eyeBlinkLeft",.72,"eyeBlinkRight",.72),
                pose("blink-left","eyeBlinkLeft",.72),pose("blink-wide","eyeBlinkLeft",.72,"eyeBlinkRight",.72,"eyeWideLeft",.5,"eyeWideRight",.5),
                pose("blink-squint","eyeBlinkLeft",.72,"eyeBlinkRight",.72,"eyeSquintLeft",.5,"eyeSquintRight",.5),
                pose("wide","eyeWideLeft",.5,"eyeWideRight",.5),
                pose("blink-brow-up","eyeBlinkLeft",.72,"eyeBlinkRight",.72,"browInnerUp",.65),
                pose("gaze-blink","eyeLookOutLeft",.65,"eyeLookInRight",.65,"eyeBlinkLeft",.72,"eyeBlinkRight",.72),
                pose("gaze-left","eyeLookOutLeft",.65,"eyeLookInRight",.65)};
        JSONArray snapshots=new JSONArray();
        for(JSONObject pose:poses) {
            float[] input=new float[52];JSONObject values=pose.getJSONObject("inputs");
            for(String name:values.keySet()) {int index=BlendshapeSchema.indexOf(name);if(index<1)throw new AssertionError(name);input[index]=values.getFloat(name);}
            if(playback){
                float[] mapped=new float[52];FacePlayback.apply(input,new float[3],true,2.5f,mapped,new float[3]);
                pose.put("sourceCoefficients",input).put("mappedCoefficients",mapped);input=mapped;
            }
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
                .put("completeSourceCoverage",true).put("playbackApplied",playback).put("mirror",playback).put("gain",playback?2.5:1)
                .put("poses",snapshots).put("scope","Actual production AvatarRig snapshots, with actual FacePlayback for --feedback; not detector output, device rendering or visual acceptance");
        Files.writeString(output,report.toString(2));System.out.println("Exported "+snapshots.length()+" production-rig poses to "+output);
    }
}
