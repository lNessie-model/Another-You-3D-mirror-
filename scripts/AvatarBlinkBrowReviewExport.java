package com.mirror.bench;
import java.nio.file.*;
import org.json.*;
/** Extra actual-production closure/brow combinations for independent review. */
public final class AvatarBlinkBrowReviewExport {
  private static JSONObject pose(String name,Object... values) {
    JSONObject input=new JSONObject();for(int i=0;i<values.length;i+=2)input.put((String)values[i],values[i+1]);
    return new JSONObject().put("name",name).put("inputs",input);
  }
  public static void main(String[] args)throws Exception {
    Path root=Path.of(args[0]),out=Path.of(args[1]);if(Files.exists(out))throw new AssertionError("Preserve review evidence");
    byte[] bytes=Files.readAllBytes(root.resolve("character.glb"));AvatarAsset asset=AvatarGlbLoader.load(bytes);
    AvatarRig rig=new AvatarRig(asset,Files.readString(root.resolve("avatar.json")));if(!rig.completeSourceCoverage())throw new AssertionError("Incomplete source mapping");
    JSONObject[] poses={pose("blink-outer","eyeBlinkLeft",.72,"eyeBlinkRight",.72,"browOuterUpLeft",.3,"browOuterUpRight",.3),
      pose("blink-inner-outer","eyeBlinkLeft",.72,"eyeBlinkRight",.72,"browInnerUp",.65,"browOuterUpLeft",.3,"browOuterUpRight",.3),
      pose("blink-inner-outer-down","eyeBlinkLeft",.72,"eyeBlinkRight",.72,"browInnerUp",.65,"browOuterUpLeft",.3,"browOuterUpRight",.3,"browDownLeft",.4,"browDownRight",.4),
      pose("blink-maximum-brows","eyeBlinkLeft",.72,"eyeBlinkRight",.72,"browInnerUp",1,"browOuterUpLeft",1,"browOuterUpRight",1),
      pose("blink-maximum-down","eyeBlinkLeft",.72,"eyeBlinkRight",.72,"browDownLeft",1,"browDownRight",1),
      pose("blink-down-outer","eyeBlinkLeft",.72,"eyeBlinkRight",.72,"browDownLeft",1,"browDownRight",1,"browOuterUpLeft",.3,"browOuterUpRight",.3)};
    JSONArray rows=new JSONArray();for(JSONObject row:poses){
      float[] input=new float[52],mapped=new float[52];JSONObject values=row.getJSONObject("inputs");
      for(String name:values.keySet())input[BlendshapeSchema.indexOf(name)]=values.getFloat(name);
      FacePlayback.apply(input,new float[3],true,2.5f,mapped,new float[3]);rig.update(mapped,new float[3]);
      JSONArray meshes=new JSONArray(),nodes=new JSONArray();for(int i=0;i<asset.meshes().size();i++){
        float[] weights=new float[asset.meshes().get(i).targetCount()];rig.copyMeshWeights(i,weights);
        meshes.put(new JSONObject().put("name",asset.meshes().get(i).name()).put("targetNames",asset.meshes().get(i).targetNames()).put("weights",weights));}
      for(int i=0;i<asset.nodes().size();i++){float[] matrix=new float[16];rig.copyWorldMatrix(i,matrix);
        nodes.put(new JSONObject().put("name",asset.nodes().get(i).name()).put("mesh",asset.nodes().get(i).meshIndex()).put("worldMatrixColumnMajor",matrix));}
      row.put("sourceCoefficients",input).put("mappedCoefficients",mapped).put("meshes",meshes).put("nodes",nodes);rows.put(row);
    }
    JSONObject report=new JSONObject().put("modelSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)))
      .put("poses",rows).put("playbackApplied",true).put("gain",2.5).put("mirror",true).put("scope","Additional actual production FacePlayback and AvatarRig combinations; no live calibration or artist acceptance");
    Files.writeString(out,report.toString(2));System.out.println("Exported "+rows.length()+" blink/brow review poses");
  }
}
