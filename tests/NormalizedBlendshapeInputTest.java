package com.mirror.bench;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/** Independent hand fixtures and frozen recorded tensors; no Android or RKNN calls. */
public final class NormalizedBlendshapeInputTest {
    private static int checks;
    private static void check(boolean ok,String message) { checks++;if(!ok)throw new AssertionError(message); }
    private static void rejects(Runnable action) {
        try { action.run();throw new AssertionError("Expected invalid input rejection"); }
        catch(IllegalArgumentException expected) { checks++; }
    }
    private static void bits(float a,float b,String message) { check(Float.floatToRawIntBits(a)==Float.floatToRawIntBits(b),message); }
    private static float[] unequalRadii() {
        float[] a=new float[292];
        for(int i=0;i<146;i++){a[2*i]=10f+(i<72?1f:i<144?-1f:i==144?2f:-2f);a[2*i+1]=20f;}
        return a;
    }
    private static void handFixtures() {
        float[] a=unequalRadii(),before=a.clone();
        var norm=NormalizedBlendshapeInput.normalizePixels(a);
        check(norm.size()==292,"fixed [1,146,2]");
        float scale=1f/(148f/146f);
        // Mean Euclidean radius is 148/146. RMS radius would give a different result.
        for(int i=0;i<146;i++) {
            float wanted=(i<72?1f:i<144?-1f:i==144?2f:-2f)*scale;
            check(Math.abs(norm.get(2*i)-wanted)<=2*Math.ulp(wanted),"mean-radius hand oracle");
            bits(norm.get(2*i+1),0f,"centered y");
        }
        check(Arrays.equals(a,before),"normalization does not mutate input");
        float saved=norm.get(0);a[0]=999f;float[] export=norm.toArray();export[0]=888f;
        bits(norm.get(0),saved,"immutable result owns data; exported array is a copy");
        check(norm.toArray()!=norm.toArray(),"new arrays on export");
        float[] diagonal=new float[292];
        for(int i=0;i<146;i++){diagonal[2*i]=i<73?3:-3;diagonal[2*i+1]=i<73?4:-4;}
        var d=NormalizedBlendshapeInput.normalizePixels(diagonal);
        for(int i=0;i<146;i++){bits(d.get(2*i),(i<73?3f:-3f)*.2f,"3-4-5 x");bits(d.get(2*i+1),(i<73?4f:-4f)*.2f,"3-4-5 y");}
        float[] xyz=new float[1434];
        for(int i=0;i<478;i++){xyz[3*i]=i/1024f;xyz[3*i+1]=-i/512f;xyz[3*i+2]=i;}
        float[] copy=xyz.clone();
        var landscape=NormalizedBlendshapeInput.pixelCoordinates(xyz,640,480);
        var portrait=NormalizedBlendshapeInput.pixelCoordinates(xyz,480,640);
        // Independent endpoints and gaps in the official subset catch all-points/XY/Z/reorder errors.
        int[] outputPositions={0,1,2,3,11,12,72,73,134,135,145};
        int[] sourceIndices={0,1,4,5,21,33,197,234,454,466,477};
        for(int j=0;j<outputPositions.length;j++) {
            int o=outputPositions[j],s=sourceIndices[j];
            bits(landscape.get(2*o),xyz[3*s]*640,"official subset x");
            bits(landscape.get(2*o+1),xyz[3*s+1]*480,"official subset y");
            bits(portrait.get(2*o),xyz[3*s]*480,"portrait x scale only");
            bits(portrait.get(2*o+1),xyz[3*s+1]*640,"portrait y scale only");
        }
        check(Arrays.equals(xyz,copy),"selection does not mutate input");
        float first=landscape.get(2);xyz[3]=999f;float[] detached=landscape.toArray();detached[2]=999f;
        bits(landscape.get(2),first,"selected vector owns data");
    }
    private static void invalidInputs() {
        rejects(()->NormalizedBlendshapeInput.normalizePixels(null));
        rejects(()->NormalizedBlendshapeInput.normalizePixels(new float[291]));
        rejects(()->NormalizedBlendshapeInput.normalizePixels(new float[294]));
        rejects(()->NormalizedBlendshapeInput.normalizePixels(new float[292]));
        float[] same=new float[292];Arrays.fill(same,32f);rejects(()->NormalizedBlendshapeInput.normalizePixels(same));
        for(float bad:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}) {
            float[] a=unequalRadii();a[291]=bad;rejects(()->NormalizedBlendshapeInput.normalizePixels(a));
            float[] xyz=new float[1434];xyz[1433]=bad;rejects(()->NormalizedBlendshapeInput.pixelCoordinates(xyz,640,480));
            float[] unselected=new float[1434];unselected[9]=bad;rejects(()->NormalizedBlendshapeInput.pixelCoordinates(unselected,640,480));
        }
        float[] huge=new float[292];Arrays.fill(huge,Float.MAX_VALUE);rejects(()->NormalizedBlendshapeInput.normalizePixels(huge));
        float[] squares=new float[292];for(int i=0;i<146;i++)squares[2*i]=i<73?1e20f:-1e20f;
        rejects(()->NormalizedBlendshapeInput.normalizePixels(squares));
        float[] tiny=new float[292];for(int i=0;i<146;i++)tiny[2*i]=i<73?Float.MIN_VALUE:-Float.MIN_VALUE;
        rejects(()->NormalizedBlendshapeInput.normalizePixels(tiny));
        rejects(()->NormalizedBlendshapeInput.pixelCoordinates(null,640,480));
        rejects(()->NormalizedBlendshapeInput.pixelCoordinates(new float[1431],640,480));
        rejects(()->NormalizedBlendshapeInput.pixelCoordinates(new float[1437],640,480));
        for(int[] dims:new int[][]{{0,480},{640,0},{-1,480},{320,240},{640,640},{Integer.MAX_VALUE,480}})
            rejects(()->NormalizedBlendshapeInput.pixelCoordinates(new float[1434],dims[0],dims[1]));
        float[] overflow=new float[1434];overflow[0]=Float.MAX_VALUE;
        rejects(()->NormalizedBlendshapeInput.pixelCoordinates(overflow,640,480));
    }
    private static float[] read(Path path,int count)throws Exception {
        byte[] bytes=Files.readAllBytes(path);check(bytes.length==count*4,"fixed fixture byte count");
        float[] values=new float[count];ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values);return values;
    }
    private static void write(Path path,float[] values)throws Exception {
        ByteBuffer b=ByteBuffer.allocate(values.length*4).order(ByteOrder.LITTLE_ENDIAN);b.asFloatBuffer().put(values);
        Files.write(path,b.array(),StandardOpenOption.CREATE_NEW);
    }
    private static void recorded(Path input,Path output)throws Exception {
        float[] xyz=read(input.resolve("landmarks72.f32"),72*1434),raw=read(input.resolve("raw92.f32"),92*292);
        float[] portrait=read(input.resolve("portrait72.f32"),72*292),expected=read(input.resolve("normalized92-reference.f32"),92*292);
        byte[] subsetBytes=Files.readAllBytes(input.resolve("official-subset.i32"));check(subsetBytes.length==146*4,"official subset size");
        int[] subset=new int[146];ByteBuffer.wrap(subsetBytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(subset);
        float[] sentinel=new float[1434];for(int i=0;i<478;i++){sentinel[3*i]=i+.25f;sentinel[3*i+1]=-i-.5f;sentinel[3*i+2]=9999f;}
        var chosen=NormalizedBlendshapeInput.pixelCoordinates(sentinel,640,480);
        for(int i=0;i<146;i++){bits(chosen.get(2*i),sentinel[3*subset[i]]*640,"all official x indices");bits(chosen.get(2*i+1),sentinel[3*subset[i]+1]*480,"all official y indices");}
        float[] selected=new float[72*292],rotated=new float[72*292],normalized=new float[92*292];
        for(int i=0;i<72;i++) {
            float[] row=Arrays.copyOfRange(xyz,i*1434,(i+1)*1434),before=row.clone();
            float[] landscape=NormalizedBlendshapeInput.pixelCoordinates(row,640,480).toArray();
            float[] vertical=NormalizedBlendshapeInput.pixelCoordinates(row,480,640).toArray();
            for(int j=0;j<292;j++){bits(landscape[j],raw[i*292+j],"recorded original pixel bits");bits(vertical[j],portrait[i*292+j],"recorded portrait pixel bits");}
            check(Arrays.equals(before,row),"recorded input ownership");
            System.arraycopy(landscape,0,selected,i*292,292);System.arraycopy(vertical,0,rotated,i*292,292);
        }
        double max=0,sum=0;int changed=0;
        for(int i=0;i<92;i++) {
            float[] row=Arrays.copyOfRange(raw,i*292,(i+1)*292),before=row.clone();
            float[] actual=NormalizedBlendshapeInput.normalizePixels(row).toArray();
            check(Arrays.equals(before,row),"raw fixture ownership");
            System.arraycopy(actual,0,normalized,i*292,292);
            for(int j=0;j<292;j++){check(Float.isFinite(actual[j]),"finite normalized fixture");double delta=Math.abs((double)actual[j]-expected[i*292+j]);max=Math.max(max,delta);sum+=delta;if(Float.floatToRawIntBits(actual[j])!=Float.floatToRawIntBits(expected[i*292+j]))changed++;}
        }
        Files.createDirectory(output);write(output.resolve("java-pixels72.f32"),selected);write(output.resolve("java-portrait72.f32"),rotated);write(output.resolve("java-normalized92.f32"),normalized);
        System.out.println("F32 normalization vs frozen ORT: max="+max+", MAE="+(sum/normalized.length)+", changed_bits_values="+changed);
        check(max<=1e-5,"predeclared normalized F32 absolute tolerance 1e-5 (not bit identity)");
    }
    public static void main(String[] args)throws Exception {
        handFixtures();invalidInputs();
        if(args.length==2)recorded(Path.of(args[0]),Path.of(args[1]));else check(args.length==0,"fixture/output pair required");
        System.out.println("NormalizedBlendshapeInputTest: "+checks+" checks passed; no Android/RKNN/device execution");
    }
}
