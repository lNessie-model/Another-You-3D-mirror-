package com.mirror.bench;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import sun.misc.Unsafe;

/** Real assets/rig/69 fixtures and production key extraction. Unsafe bypasses EGL construction only. */
public final class AvatarBackgroundStateTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static void set(Object target,String name,Object value)throws Exception{Field f=AvatarGpuScene.class.getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private static boolean same(float[] a,float[] b){if(a.length!=b.length)return false;for(int i=0;i<a.length;i++)if(Float.floatToRawIntBits(a[i])!=Float.floatToRawIntBits(b[i]))return false;return true;}
    public static void main(String[] args)throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("Three real head/background scene folders required");
        Field singleton=Unsafe.class.getDeclaredField("theUnsafe");singleton.setAccessible(true);Unsafe unsafe=(Unsafe)singleton.get(null);
        for(String argument:args){
            Path directory=Path.of(argument);AvatarAsset asset=AvatarGlbLoader.load(Files.readAllBytes(directory.resolve("character.glb")));
            AvatarRig rig=new AvatarRig(asset,Files.readString(directory.resolve("avatar.json")));
            AvatarDrawPartition partition=new AvatarDrawPartition(asset,rig);
            AvatarGpuScene scene=(AvatarGpuScene)unsafe.allocateInstance(AvatarGpuScene.class);
            float[] worlds=new float[asset.nodes().size()*16],matrix=new float[16];
            set(scene,"asset",asset);set(scene,"rig",rig);set(scene,"drawMode",AvatarGpuScene.DrawMode.INDIVIDUAL);
            set(scene,"drawPartition",partition);set(scene,"staticBackgroundNodes",partition.staticNodes());
            set(scene,"framing",AvatarGeometryBounds.fromAsset(asset,rig));set(scene,"fit",new float[16]);set(scene,"displayedWorlds",worlds);
            check(scene.hasCacheableStaticBackground(),"Actual scene eligible");
            float[] expected=null,state=new float[scene.staticBackgroundStateFloats()],weights=new float[52],angles=new float[3];
            float[] firstHead=null;int moved=0;
            for(var pose:AvatarPoseFixtures.regression()){
                pose.copyWeights(weights);pose.copyAngles(angles);rig.update(weights,angles);
                for(int n=0;n<asset.nodes().size();n++)if(rig.activeNode(n)){rig.copyWorldMatrix(n,matrix);System.arraycopy(matrix,0,worlds,n*16,16);}
                scene.copyStaticBackgroundState(.625f,state);
                if(expected==null)expected=state.clone();
                check(same(expected,state),"Head/mouth/eye pose must not invalidate fixed fit/background: "+pose.name());
                rig.copyWorldMatrix(rig.headNodeIndex(),matrix);if(firstHead==null)firstHead=matrix.clone();else if(!same(firstHead,matrix))moved++;
            }
            check(moved>0,"The actual head moved while the background key stayed fixed");
            scene.copyStaticBackgroundState(.7f,state);check(!same(expected,state),"Physical projection aspect changes actual fit key");
            scene.copyStaticBackgroundState(.625f,state);check(same(expected,state),"Return to original fit is bitwise stable");
            worlds[partition.staticNodes()[0]*16+12]+=.001f;
            scene.copyStaticBackgroundState(.625f,state);check(!same(expected,state),"Actual displayed background world change invalidates key");
        }
        System.out.println("AvatarBackgroundStateTest: "+checks+" checks passed across 3 real assets and 69 poses each; no GL/deformation pixels/artwork qualification");
    }
}
