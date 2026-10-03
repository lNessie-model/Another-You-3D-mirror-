package com.mirror.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual asset fit, optionally proving an offline static-mesh candidate retains it exactly. */
public final class TripoFramingCheck {
    private static float[] fit(String directory) throws Exception {
        Path root=Path.of(directory);
        AvatarAsset asset=AvatarGlbLoader.load(Files.readAllBytes(root.resolve("character.glb")));
        AvatarRig rig=new AvatarRig(asset,Files.readString(root.resolve("avatar.json")));
        float[] matrix=new float[16];
        AvatarGeometryBounds.fromAsset(asset,rig).copyFitMatrix(.625f,matrix);
        return matrix;
    }
    public static void main(String[] args) throws Exception {
        float[] original=fit(args[0]);
        if(args.length>1) {
            float[] candidate=fit(args[1]);
            for(int i=0;i<16;i++)if(Float.floatToIntBits(original[i])!=Float.floatToIntBits(candidate[i]))
                throw new AssertionError("Static optimization altered framing at "+i);
        }
        System.out.println(new JSONObject().put("fitMatrix",new JSONArray(original))
                .put("displayAspect",.625).put("cameraDistance",3).put("maxEyeOffset",.2)
                .put("comparisonPassed",args.length>1).toString(2));
    }
}
