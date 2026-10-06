package com.mirror.bench;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;

/** Real GLB + PNG data; pure packing and admission behavior, not an Android/GPU pixel gate. */
public final class AvatarOrmUploadPolicyTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        byte[] glb=Files.readAllBytes(Path.of(args[0]));
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(glb));
        check(sha.equals(AvatarOrmUploadPolicy.GERALT_SHA256),"exact production asset");
        AvatarAsset asset=AvatarGlbLoader.load(glb);
        var off=AvatarOrmUploadPolicy.decide(asset,sha,false);
        check(!off.requested&&!off.eligible&&off.reason.equals("not_requested"),"default stays RGBA");
        var on=AvatarOrmUploadPolicy.decide(asset,sha,true);
        check(on.requested&&on.eligible&&on.reason.equals("eligible_exact_geralt"),"exact eligible model");
        check(on.rgbaBytes==22369620L&&on.rgBytes==11184810L,"complete linear 2K mip payload");
        reject(asset,"0".repeat(64),"model_sha_not_allowlisted");
        reject(asset,null,"model_sha_not_allowlisted");
        reject(copy(asset,asset.materials(),asset.albedoAtlas(),asset.normalMap(),null),sha,"maps_missing");
        reject(copy(asset,asset.materials(),asset.albedoAtlas(),asset.normalMap(),new AvatarAsset.AlbedoAtlas(new byte[0],2047,2048)),sha,"map_dimensions");
        byte[] changed=asset.ormMap().openStream().readAllBytes();changed[changed.length-1]^=1;
        reject(copy(asset,asset.materials(),asset.albedoAtlas(),asset.normalMap(),new AvatarAsset.AlbedoAtlas(changed,2048,2048)),sha,"map_digest");
        reject(copy(asset,asset.materials(),asset.ormMap(),asset.normalMap(),asset.ormMap()),sha,"map_digest");
        List<AvatarAsset.Material> materials=new ArrayList<>(asset.materials());
        // Last material is not the visible ORM draw: checking only material zero would miss this.
        materials.set(4,new AvatarAsset.Material("WarmWhiteDentition",new float[]{1,1,1,1},.28f,false,true,true,1,1,1));
        reject(copy(asset,materials,asset.albedoAtlas(),asset.normalMap(),asset.ormMap()),sha,"material_contract");
        materials=new ArrayList<>(asset.materials());materials.add(asset.materials().get(0));
        reject(copy(asset,materials,asset.albedoAtlas(),asset.normalMap(),asset.ormMap()),sha,"material_contract");
        for(float metal:new float[]{-0.0f,Float.NaN,Float.POSITIVE_INFINITY,0.01f}){
            materials=new ArrayList<>(asset.materials());
            materials.set(0,new AvatarAsset.Material("OriginalSkinHairPBR",new float[]{1,1,1,1},1,false,true,true,metal,.65f,.5f));
            reject(copy(asset,materials,asset.albedoAtlas(),asset.normalMap(),asset.ormMap()),sha,"material_contract");
        }
        packing();
        var image=ImageIO.read(asset.ormMap().openStream());
        int[] row=new int[image.getWidth()];IntBuffer source=IntBuffer.wrap(row);ByteBuffer rg=ByteBuffer.allocateDirect(row.length*2);
        MessageDigest expected=MessageDigest.getInstance("SHA-256"),actual=MessageDigest.getInstance("SHA-256");
        for(int y=0;y<image.getHeight();y++){
            image.getRGB(0,y,row.length,1,row,0,row.length);
            AvatarOrmUploadPolicy.packArgb(source,rg,row.length);
            for(int x=0;x<row.length;x++){
                int p=image.getRGB(x,y);byte r=(byte)(p>>>16),g=(byte)(p>>>8);
                check(rg.get(x*2)==r&&rg.get(x*2+1)==g,"all production R/G bytes preserved");
                expected.update(r);expected.update(g);actual.update(rg.get(x*2));actual.update(rg.get(x*2+1));
            }
        }
        check(Arrays.equals(expected.digest(),actual.digest()),"full ORM R/G SHA equals independent pixel oracle");
        check(AvatarOrmUploadPolicy.logicalBytes(3,5,2,true)==36,"odd rectangular mip chain");
        bad(()->AvatarOrmUploadPolicy.logicalBytes(0,3,2,true));
        System.out.println("AvatarOrmUploadPolicyTest: "+checks+" checks; real GLB/PNG; no Android decode or GPU execution");
    }
    private static void packing(){
        int[] values={0xdeadbeef,0xff123456,0x00112233,0xff00ff00,0xffabcd01,0xaabbccdd};
        IntBuffer src=IntBuffer.wrap(values).asReadOnlyBuffer();src.position(1);src.limit(5);
        ByteBuffer dst=ByteBuffer.allocateDirect(15);for(int i=0;i<15;i++)dst.put(i,(byte)0x7f);
        dst.position(3);dst.limit(11);AvatarOrmUploadPolicy.packArgb(src,dst,4);
        check(src.position()==1&&src.limit()==5&&dst.position()==3&&dst.limit()==11,"positions and limits unchanged");
        int[] expected={0x12,0x34,0x11,0x22,0,0xff,0xab,0xcd};
        for(int i=0;i<8;i++)check((dst.get(3+i)&255)==expected[i],"unsigned ARGB channels/order");
        ByteBuffer all=dst.duplicate();all.clear();check(all.get(2)==0x7f&&all.get(11)==0x7f,"only destination range written");
        int[] before=values.clone();AvatarOrmUploadPolicy.packArgb(src,dst,0);check(Arrays.equals(values,before),"read-only source unchanged");
        byte[] output=new byte[15];all.get(output);bad(()->AvatarOrmUploadPolicy.packArgb(src,dst,5));
        bad(()->AvatarOrmUploadPolicy.packArgb(src,dst,-1));bad(()->AvatarOrmUploadPolicy.packArgb(src,dst,Integer.MAX_VALUE));
        bad(()->AvatarOrmUploadPolicy.packArgb(src,dst.asReadOnlyBuffer(),1));
        all.clear();byte[] after=new byte[15];all.get(after);check(Arrays.equals(output,after),"invalid calls do not partially write");
        for(int r=0;r<256;r++)for(int g=0;g<256;g++){
            IntBuffer pixel=IntBuffer.wrap(new int[]{0x7f000099|(r<<16)|(g<<8)});ByteBuffer pair=ByteBuffer.allocate(2);
            AvatarOrmUploadPolicy.packArgb(pixel,pair,1);check((pair.get(0)&255)==r&&(pair.get(1)&255)==g,"all channel byte combinations");
        }
    }
    private static AvatarAsset copy(AvatarAsset a,List<AvatarAsset.Material> materials,AvatarAsset.AlbedoAtlas color,AvatarAsset.AlbedoAtlas normal,AvatarAsset.AlbedoAtlas orm){
        int[] roots=new int[a.sceneRoots().remaining()];a.sceneRoots().get(roots);
        return new AvatarAsset(a.meshes(),a.nodes(),materials,roots,a.vertexCount(),a.triangleCount(),a.decodedBytes(),color,normal,orm);
    }
    private static void reject(AvatarAsset a,String hash,String reason){var p=AvatarOrmUploadPolicy.decide(a,hash,true);check(p.requested&&!p.eligible&&p.reason.equals(reason),"fallback "+reason+": "+p.reason);}
    private interface Action{void run();}
    private static void bad(Action action){try{action.run();throw new AssertionError("expected rejection");}catch(IllegalArgumentException expected){checks++;}}
    private static void check(boolean good,String message){checks++;if(!good)throw new AssertionError(message);}
}
