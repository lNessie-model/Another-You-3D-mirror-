package com.mirror.bench;

/** Stable labels of MediaPipe FaceBlendshapesGraph; validate the native output before using indices.
 * Source: google-ai-edge/mediapipe, tasks/cc/vision/face_landmarker/face_blendshapes_graph.cc.
 */
public final class BlendshapeSchema {
    public static final String ID = "mediapipe-face-blendshapes-v1";
    private static final String[] NAMES = {
        "_neutral", "browDownLeft", "browDownRight", "browInnerUp", "browOuterUpLeft", "browOuterUpRight",
        "cheekPuff", "cheekSquintLeft", "cheekSquintRight", "eyeBlinkLeft", "eyeBlinkRight",
        "eyeLookDownLeft", "eyeLookDownRight", "eyeLookInLeft", "eyeLookInRight",
        "eyeLookOutLeft", "eyeLookOutRight", "eyeLookUpLeft", "eyeLookUpRight",
        "eyeSquintLeft", "eyeSquintRight", "eyeWideLeft", "eyeWideRight",
        "jawForward", "jawLeft", "jawOpen", "jawRight", "mouthClose", "mouthDimpleLeft", "mouthDimpleRight",
        "mouthFrownLeft", "mouthFrownRight", "mouthFunnel", "mouthLeft", "mouthLowerDownLeft", "mouthLowerDownRight",
        "mouthPressLeft", "mouthPressRight", "mouthPucker", "mouthRight", "mouthRollLower", "mouthRollUpper",
        "mouthShrugLower", "mouthShrugUpper", "mouthSmileLeft", "mouthSmileRight", "mouthStretchLeft", "mouthStretchRight",
        "mouthUpperUpLeft", "mouthUpperUpRight", "noseSneerLeft", "noseSneerRight"
    };
    public static final int SIZE = NAMES.length;
    private BlendshapeSchema() {}
    public static String name(int index) {
        if (index < 0 || index >= SIZE) throw new IllegalArgumentException("Unknown blendshape index: " + index);
        return NAMES[index];
    }
    public static int indexOf(String name) {
        for (int i = 0; i < SIZE; i++) if (NAMES[i].equals(name)) return i;
        return -1;
    }
    public static void validateClassification(int position, String label, int declaredIndex) {
        if (!name(position).equals(label) || declaredIndex != position)
            throw new IllegalArgumentException("MediaPipe blendshape schema mismatch at " + position + ": " + label + " / " + declaredIndex);
    }
    /** Validate the complete post-network transaction before publishing any of its components. */
    static float[][] copyValidatedPostOutput(float[] weights,float[] pose,float[] landmarks) {
        float[][] result={copyFinite(weights,SIZE,"blendshapes"),copyFinite(pose,16,"pose"),
                copyFinite(landmarks,1434,"landmarks")};
        for(float value:result[0])if(value<0||value>1)
            throw new IllegalArgumentException("Blendshape score outside [0,1]");
        return result;
    }
    private static float[] copyFinite(float[] source,int count,String name) {
        if(source==null||source.length!=count)throw new IllegalArgumentException("Incomplete "+name+" output");
        float[] copy=source.clone();
        for(float value:copy)if(!Float.isFinite(value))throw new IllegalArgumentException("Nonfinite "+name+" output");
        return copy;
    }
}
