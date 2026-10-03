package com.mirror.bench;

/**
 * Pure CPU input preparation for the experimental normalized expression suffix.
 * Unused by the current application. No smoothing, image transforms, clipping or epsilon.
 * Formula and subset are pinned in docs/normalized-blendshape-input.md.
 */
public final class NormalizedBlendshapeInput {
    private NormalizedBlendshapeInput() {}
    private static final int POINTS=146;
    // MediaPipe face_blendshapes_graph.cc, SHA256 a3826ad2...64e4bd3.
    private static final int[] SUBSET={
        0,1,4,5,6,7,8,10,13,14,17,21,33,37,39,
        40,46,52,53,54,55,58,61,63,65,66,67,70,78,80,
        81,82,84,87,88,91,93,95,103,105,107,109,127,132,133,
        136,144,145,146,148,149,150,152,153,154,155,157,158,159,160,
        161,162,163,168,172,173,176,178,181,185,191,195,197,234,246,
        249,251,263,267,269,270,276,282,283,284,285,288,291,293,295,
        296,297,300,308,310,311,312,314,317,318,321,323,324,332,334,
        336,338,356,361,362,365,373,374,375,377,378,379,380,381,382,
        384,385,386,387,388,389,390,397,398,400,402,405,409,415,454,
        466,468,469,470,471,472,473,474,475,476,477
    };

    /** Owned immutable [1,146,2] values; get is read-only and toArray returns a copy. */
    public static final class Vector {
        private final float[] coordinates;
        private Vector(float[] ownedNewArray) { coordinates=ownedNewArray; }
        public int size() { return coordinates.length; }
        public float get(int index) { return coordinates[index]; }
        public float[] toArray() { return coordinates.clone(); }
    }

    /**
     * Select official 146 indices from 478 adjacent XYZ normalized landmarks, then
     * multiply X by image width and Y by height. Z is validated but not selected.
     * The caller supplies already-smoothed landmarks in the image's current orientation.
     */
    public static Vector pixelCoordinates(float[] smoothedXyz,int width,int height) {
        if(!((width==640&&height==480)||(width==480&&height==640)))
            throw invalid("unsupported image dimensions");
        validate(smoothedXyz,478*3);
        float[] pixels=new float[POINTS*2];
        for(int i=0;i<POINTS;i++) {
            int offset=3*SUBSET[i];
            pixels[2*i]=finite(smoothedXyz[offset]*width);
            pixels[2*i+1]=finite(smoothedXyz[offset+1]*height);
        }
        return new Vector(pixels);
    }

    /**
     * Original eight-node float32 front: XY centroid, center, square, sum XY,
     * radius (power .5), mean radius, power -1, multiply centered coordinates.
     * Reductions use fixed point order; no FP32/ORT bit-identity promise is made.
     */
    public static Vector normalizePixels(float[] pixels) {
        validate(pixels,POINTS*2);
        float meanX=0f,meanY=0f;
        for(int i=0;i<POINTS;i++) {
            meanX=finite(meanX+pixels[2*i]);meanY=finite(meanY+pixels[2*i+1]);
        }
        meanX=finite(meanX/POINTS);meanY=finite(meanY/POINTS);
        float[] centered=new float[POINTS*2];float sumRadius=0f;
        for(int i=0;i<POINTS;i++) {
            float x=finite(pixels[2*i]-meanX),y=finite(pixels[2*i+1]-meanY);
            centered[2*i]=x;centered[2*i+1]=y;
            float squaredX=finite(x*x),squaredY=finite(y*y);
            float squaredRadius=finite(squaredX+squaredY);
            float radius=finite((float)Math.pow(squaredRadius,.5));
            sumRadius=finite(sumRadius+radius);
        }
        float meanRadius=finite(sumRadius/POINTS);
        if(!(meanRadius>0f))throw invalid("zero normalization radius");
        float reciprocal=finite((float)Math.pow(meanRadius,-1));
        if(!(reciprocal>0f))throw invalid("invalid reciprocal radius");
        for(int i=0;i<centered.length;i++)centered[i]=finite(centered[i]*reciprocal);
        return new Vector(centered);
    }
    private static void validate(float[] input,int size) {
        if(input==null||input.length!=size)throw invalid("tensor shape");
        for(float value:input)finite(value);
    }
    private static float finite(float value) {
        if(!Float.isFinite(value))throw invalid("nonfinite input or arithmetic");return value;
    }
    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid normalized blendshape input: "+reason);
    }
}
