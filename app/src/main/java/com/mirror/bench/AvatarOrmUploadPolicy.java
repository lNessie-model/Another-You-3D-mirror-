package com.mirror.bench;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.security.MessageDigest;

/** Narrow, opt-in upload experiment. No GL calls, asset mutation, or per-frame work. */
final class AvatarOrmUploadPolicy {
    static final String GERALT_SHA256="9381f452c53098314f97e1a1799ec55d2be37878e66bf205c7a26958afcef531";
    static final int STRIP_ROWS=64;
    private static final String[] MAP_HASHES={
        "5c62dc7dfc0cc0274897b7fabee82ea7b2d2603408e33f48d7a6a11221ec3909",
        "9d63927bafd376077507eb51a7034a1dacc65715058204b9ce740004b3e2df70",
        "cd9d37a71943b0a74f4da5437487631b591daa7bcc24c0b625144e68414179f7"};
    private static final String[] MATERIAL_NAMES={"OriginalSkinHairPBR","OralTissuePBR","RetopologizedSkin","OcularSurface","WarmWhiteDentition"};
    private static final float[] ROUGHNESS={1,.94f,.66f,.18f,.28f};

    static final class Decision {
        final boolean requested,eligible;
        final String reason;
        final long rgbaBytes,rgBytes;
        private Decision(boolean requested,boolean eligible,String reason,AvatarAsset asset){
            this.requested=requested;this.eligible=eligible;this.reason=reason;
            AvatarAsset.AlbedoAtlas orm=asset.ormMap();
            rgbaBytes=orm==null?0:logicalBytes(orm.width(),orm.height(),4,asset.normalMap()!=null);
            rgBytes=orm==null?0:logicalBytes(orm.width(),orm.height(),2,asset.normalMap()!=null);
        }
    }
    /** The caller must supply the loader asset with its verified model digest, as fromAsset requires.
     * Re-checks every material (including unused ones) and all maps to fail closed on a mismatched pair.
     * The loader's PBR profile enforces exactly 3 maps: color=0, normal=1, ORM/AO=2, with no aliases.
     * Unknown/changed assets always use RGBA; this is not a general metallic-factor optimizer.
     */
    static Decision decide(AvatarAsset asset,String verifiedModelSha,boolean requested){
        if(asset==null)throw new IllegalArgumentException("Validated asset required");
        if(!requested)return new Decision(false,false,"not_requested",asset);
        if(!GERALT_SHA256.equals(verifiedModelSha))return new Decision(true,false,"model_sha_not_allowlisted",asset);
        AvatarAsset.AlbedoAtlas[] maps={asset.albedoAtlas(),asset.normalMap(),asset.ormMap()};
        for(var map:maps)if(map==null)return new Decision(true,false,"maps_missing",asset);
        for(var map:maps)if(map.width()!=2048||map.height()!=2048)return new Decision(true,false,"map_dimensions",asset);
        if(!materialsMatch(asset))return new Decision(true,false,"material_contract",asset);
        for(int i=0;i<maps.length;i++)if(!MAP_HASHES[i].equals(digest(maps[i])))return new Decision(true,false,"map_digest",asset);
        return new Decision(true,true,"eligible_exact_geralt",asset);
    }
    private static boolean materialsMatch(AvatarAsset asset){
        if(asset.materials().size()!=MATERIAL_NAMES.length)return false;
        for(int i=0;i<MATERIAL_NAMES.length;i++){
            AvatarAsset.Material m=asset.materials().get(i);
            if(!MATERIAL_NAMES[i].equals(m.name())||m.unlit()||m.textured()!=(i<4)||m.pbrMaps()!=(i==0)
                    ||!bits(m.metallic(),0)||!bits(m.roughness(),ROUGHNESS[i])
                    ||!bits(m.normalScale(),i==0?.65f:1)||!bits(m.occlusionStrength(),i==0?.5f:0))return false;
            FloatBuffer c=m.baseColor();if(c==null||c.remaining()!=4)return false;
            for(int channel=0;channel<4;channel++)if(!bits(c.get(c.position()+channel),i==1&&channel<3?.2f:1))return false;
        }
        return true;
    }
    private static boolean bits(float a,float b){return Float.floatToRawIntBits(a)==Float.floatToRawIntBits(b);}
    private static String digest(AvatarAsset.AlbedoAtlas map){
        try(InputStream input=map.openStream()){
            MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] block=new byte[8192];int n;
            while((n=input.read(block))!=-1)hash.update(block,0,n);
            StringBuilder out=new StringBuilder(64);for(byte b:hash.digest()){
                int u=b&255;out.append(Character.forDigit(u>>>4,16));out.append(Character.forDigit(u&15,16));
            }
            return out.toString();
        }catch(java.io.IOException|java.security.NoSuchAlgorithmException failure){
            throw new IllegalStateException("ORM map verification failed",failure);
        }
    }
    /** Packs from the current source/destination positions without moving either view or mutating
     * source storage. ARGB int channels are independent of native byte order. Validate before writing.
     */
    static void packArgb(IntBuffer argb,ByteBuffer rg,int pixels){
        if(argb==null||rg==null||rg.isReadOnly()||pixels<0||pixels>argb.remaining()||2L*pixels>rg.remaining())
            throw new IllegalArgumentException("ORM packing buffer range");
        int source=argb.position(),destination=rg.position();
        for(int i=0;i<pixels;i++){
            int pixel=argb.get(source+i);rg.put(destination+2*i,(byte)(pixel>>>16));rg.put(destination+2*i+1,(byte)(pixel>>>8));
        }
    }
    /** Sized-format texel payload only: excludes driver tiling, compression, metadata, and residency. */
    static long logicalBytes(int width,int height,int channels,boolean mip){
        if(width<=0||height<=0||width>4096||height>4096||(channels!=2&&channels!=4))throw new IllegalArgumentException("ORM dimensions/format");
        long bytes=0;while(true){bytes+=(long)channels*width*height;if(!mip||(width==1&&height==1))return bytes;width=Math.max(1,width/2);height=Math.max(1,height/2);}
    }
    private AvatarOrmUploadPolicy(){}
}
