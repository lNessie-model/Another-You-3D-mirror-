package com.mirror.bench;

import android.content.res.AssetManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

/** One background-loaded immutable selection. Never activates, replaces or silently falls back from current. */
final class CameraPreviewAsset {
    final AvatarAsset asset;
    final String manifest,sha256,source,name;
    private CameraPreviewAsset(AvatarAsset asset,String manifest,String sha256,String source,String name){
        this.asset=asset;this.manifest=manifest;this.sha256=sha256;this.source=source;this.name=name;
    }
    static CameraPreviewAsset load(AssetManager assets,File storeRoot,int androidApi)throws Exception{
        interrupted();
        if(AvatarPackageStore.supportsAndroidApi(androidApi)){
            AvatarPackageStore.LoadedPackage loaded=AvatarPackageStore.open(storeRoot,androidApi).readCurrent();
            interrupted();
            if(loaded!=null)return new CameraPreviewAsset(loaded.asset,loaded.manifestJson,loaded.ticket.modelSha256,
                    "current:"+loaded.ticket.packageId,loaded.rig.displayName());
        }
        byte[] manifest,glb;String directory="avatars/builtin-guide/";
        try(InputStream stream=assets.open(directory+"avatar.json")){manifest=read(stream,262144);}
        String text=new String(manifest,StandardCharsets.UTF_8);JSONObject metadata=new JSONObject(text);
        String file=metadata.getString("model");
        if(!file.matches("[A-Za-z0-9_-]+\\.glb"))throw new IOException("Invalid builtin model path");
        try(InputStream stream=assets.open(directory+file)){glb=read(stream,AvatarGlbLoader.MAX_FILE_BYTES);}
        StringBuilder hex=new StringBuilder();
        for(byte value:MessageDigest.getInstance("SHA-256").digest(glb))hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        String hash=hex.toString();if(!hash.equalsIgnoreCase(metadata.getString("modelSha256")))throw new IOException("Builtin model digest mismatch");
        interrupted();AvatarAsset asset=AvatarGlbLoader.load(glb);AvatarRig rig=new AvatarRig(asset,text);
        AvatarGeometryBounds.fromAsset(asset,rig);interrupted();
        return new CameraPreviewAsset(asset,text,hash,"builtin",rig.displayName());
    }
    private static byte[] read(InputStream input,int limit)throws IOException{
        ByteArrayOutputStream result=new ByteArrayOutputStream();byte[] block=new byte[8192];
        for(int count;(count=input.read(block))!=-1;){
            interrupted();if(count==0)continue;if(result.size()>limit-count)throw new IOException("Preview asset exceeds byte limit");
            result.write(block,0,count);
        }
        return result.toByteArray();
    }
    private static void interrupted()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Preview load cancelled");}
}
