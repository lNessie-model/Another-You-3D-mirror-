package com.mirror.bench;

import java.nio.FloatBuffer;
import java.util.List;

/** Duplicate UV vertices smooth only when authored normals and every morph trajectory agree. */
public final class AvatarSmoothNormalsTest {
    public static void main(String[] args){
        float[] positions={0,0,0,1,0,0,0,1,0,0,0,0,0,1,0,0,0,1};
        float[] normals={.7071f,0,.7071f,0,0,1,.7071f,0,.7071f,.7071f,0,.7071f,.7071f,0,.7071f,1,0,0};
        float[] delta=new float[18];delta[1]=delta[10]=.1f;
        AvatarAsset smooth=asset(positions,normals,delta);
        AvatarDeformer d=new AvatarDeformer(smooth,AvatarDeformer.NormalPolicy.SMOOTH_DEFORMED);
        checkPair(d,.70710678f);
        d.updateMesh(0,new float[]{1});FloatBuffer n=d.primitives(0).get(0).normals();
        for(int c=0;c<3;c++)close(n.get(c),n.get(9+c));
        float[] hard=normals.clone();hard[9]=1;hard[11]=0;
        n=new AvatarDeformer(asset(positions,hard,delta),AvatarDeformer.NormalPolicy.SMOOTH_DEFORMED).primitives(0).get(0).normals();
        close(n.get(2),1);close(n.get(9),1);
        delta[10]=.2f;
        n=new AvatarDeformer(asset(positions,normals,delta),AvatarDeformer.NormalPolicy.SMOOTH_DEFORMED).primitives(0).get(0).normals();
        close(n.get(2),1);close(n.get(9),1);
        AvatarDeformer legacy=new AvatarDeformer(smooth,AvatarDeformer.NormalPolicy.RECOMPUTE_DEFORMED);
        close(legacy.primitives(0).get(0).normals().get(2),1);
        System.out.println("AvatarSmoothNormalsTest: UV seams smooth, hard edges and diverging morphs stay separate; legacy unchanged");
    }
    private static void checkPair(AvatarDeformer d,float expected){FloatBuffer n=d.primitives(0).get(0).normals();close(n.get(0),expected);close(n.get(2),expected);for(int c=0;c<3;c++)close(n.get(c),n.get(9+c));}
    private static void close(float a,float b){if(Math.abs(a-b)>1e-5)throw new AssertionError(a+" != "+b);}
    private static AvatarAsset asset(float[] p,float[] n,float[] delta){
        var primitive=new AvatarAsset.Primitive(p,n,null,null,new int[]{0,1,2,3,4,5},List.of(new AvatarAsset.Morph(delta,null)),0);
        var mesh=new AvatarAsset.Mesh("Face",List.of(primitive),List.of("jawOpen"),new float[1]);
        return new AvatarAsset(List.of(mesh),List.of(),List.of(),new int[0],6,2,0);
    }
}
