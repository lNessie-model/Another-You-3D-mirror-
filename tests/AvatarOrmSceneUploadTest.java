package com.mirror.bench;

import android.graphics.Bitmap;
import android.opengl.GLES30;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import javax.imageio.ImageIO;
import org.json.JSONObject;
import sun.misc.Unsafe;

/** Executes production uploadMap with boundary fixtures, no constructor/EGL/Mali or Android decode. */
public final class AvatarOrmSceneUploadTest {
    private static int checks;
    private static final Method UPLOAD,STATUS;
    static {try{
        UPLOAD=AvatarGpuScene.class.getDeclaredMethod("uploadMap",AvatarAsset.AlbedoAtlas.class,int.class);UPLOAD.setAccessible(true);
        STATUS=AvatarGpuScene.class.getDeclaredMethod("ormUploadStatus");STATUS.setAccessible(true);
    }catch(Exception e){throw new ExceptionInInitializerError(e);}}
    public static void main(String[] args)throws Exception {
        AvatarAsset asset=AvatarGlbLoader.load(Files.readAllBytes(Path.of(args[0])));
        var off=AvatarOrmUploadPolicy.decide(asset,AvatarOrmUploadPolicy.GERALT_SHA256,false);
        var on=AvatarOrmUploadPolicy.decide(asset,AvatarOrmUploadPolicy.GERALT_SHA256,true);
        AvatarGpuScene scene=fixture(asset,off);GLES30.reset();upload(scene,asset.ormMap(),2);
        check(GLES30.internal==0x8058&&GLES30.rgbaUploads==1&&GLES30.rgUploads==0,"default actual RGBA8 path");
        check(GLES30.levels==12&&GLES30.mips==1,"existing full mip generation");
        check(GLES30.calls.stream().noneMatch(s->s.startsWith("get")||s.startsWith("store")),"default no new unpack GL operations");
        check(!status(scene).getBoolean("requested")&&status(scene).getString("actual").equals("rgba8"),"default publication");
        scene=fixture(asset,AvatarOrmUploadPolicy.decide(asset,"0".repeat(64),true));GLES30.reset();upload(scene,asset.ormMap(),2);
        check(GLES30.internal==0x8058&&GLES30.rgbaUploads==1&&GLES30.rgUploads==0,"requested unknown model falls back on real RGBA branch");
        check(status(scene).getString("fallback_reason").equals("model_sha_not_allowlisted")&&status(scene).getLong("saved_logical_bytes")==0,"fallback publication");
        scene=fixture(asset,on);GLES30.reset();upload(scene,asset.ormMap(),2);
        check(GLES30.internal==0x822b&&GLES30.rgUploads==32&&GLES30.rgbaUploads==0,"real production RG8 strip branch");
        check(GLES30.levels==12&&GLES30.mips==1,"candidate complete mip generation");
        check(GLES30.active==0x84c0&&GLES30.texture==0,"successful upload binding postcondition");
        restored();check(Bitmap.last.recycled,"decoded bitmap released");
        var png=ImageIO.read(asset.ormMap().openStream());
        for(int y=0;y<2048;y++)for(int x=0;x<2048;x++){
            int p=png.getRGB(x,y),i=(y*2048+x)*2;
            check(GLES30.pixels[i]==(byte)(p>>>16)&&GLES30.pixels[i+1]==(byte)(p>>>8),"every uploaded source channel");
        }
        JSONObject report=status(scene);check(report.getString("actual").equals("rg8")&&report.getLong("saved_logical_bytes")==11184810L,"publish only successful candidate");
        check(GLES30.calls.indexOf("mipmap")>GLES30.calls.lastIndexOf("rg"),"mips after complete source upload");
        // Exercise the same production packing/upload with odd rows; admission still only permits exact 2K asset.
        var odd=png(3,65);scene=fixture(asset,on);GLES30.reset();upload(scene,odd,2);
        check(GLES30.rgUploads==2&&GLES30.pixels.length==390,"odd width and partial strip");restored();
        scene=fixture(asset,on);GLES30.reset();upload(scene,asset.normalMap(),1);
        check(GLES30.internal==0x8058&&GLES30.rgbaUploads==1,"normal never packed");
        scene=fixture(asset,on);GLES30.reset();upload(scene,asset.albedoAtlas(),0);
        check(GLES30.internal==0x8c43&&GLES30.rgbaUploads==1,"albedo remains SRGB8_ALPHA8");
        scene=fixture(asset,off);GLES30.reset();GLES30.failRgba=true;
        Throwable rgbaFailure=failingUpload(scene,odd);
        check(rgbaFailure.getMessage().contains("injected RGBA")&&GLES30.deleted.size()==1,"RGBA failure preserves cause and releases name");
        for(int fault=0;fault<4;fault++){
            scene=fixture(asset,on);GLES30.reset();
            if(fault==0)GLES30.failRg=true;
            if(fault==1){GLES30.failRg=true;GLES30.failRestore=true;GLES30.failDelete=true;}
            if(fault==2)GLES30.state.put(0x88ef,9);
            if(fault==3)GLES30.failRestore=true;
            Throwable failure=failingUpload(scene,odd);
            check(GLES30.deleted.size()==1&&GLES30.deleted.get(0)==17,"failed texture deleted exactly once");
            check(((int[])get(scene,"detailTextures"))[1]==0,"failed texture name not retained");
            check(!status(scene).getString("actual").equals("rg8"),"failure never published as candidate success");
            check(Bitmap.last.recycled,"failure releases decoded bitmap");
            if(fault<=1){check(failure.getMessage().contains("injected RG"),"original upload failure retained");restored();}
            if(fault==1)check(failure.getSuppressed().length==2,"restore and deletion errors suppressed on original");
            GLES30.failDelete=false;scene.dispose();check(GLES30.deleted.size()==1,"constructor cleanup cannot double delete");
        }
        comparisonOwnership(asset,on,off);
        System.out.println("AvatarOrmSceneUploadTest: "+checks+" checks; SDK-compiled production methods + host boundaries only; no GPU claim");
    }
    private static void comparisonOwnership(AvatarAsset asset,AvatarOrmUploadPolicy.Decision on,AvatarOrmUploadPolicy.Decision off)throws Exception{
        AvatarGpuScene scene=fixture(asset,off);GLES30.reset();upload(scene,asset.ormMap(),2);
        try{scene.createOrmComparison();throw new AssertionError("default cannot compare RG8");}catch(IllegalStateException expected){checks++;}
        scene=fixture(asset,on);GLES30.reset();upload(scene,asset.ormMap(),2);
        AvatarGpuScene.OrmComparison scope=scene.createOrmComparison();JSONObject ids=scope.status();
        check(ids.getInt("reference_texture")==18&&ids.getInt("candidate_texture")==17,"independent actual storage names");
        check(((int[])get(scene,"detailTextures"))[1]==17&&status(scene).getString("actual").equals("rg8"),"creation restores candidate");
        check(GLES30.rgbaUploads==1&&GLES30.rgUploads==32,"reference and candidate use production uploads");
        Method verify=scope.getClass().getDeclaredMethod("verifyBound");verify.setAccessible(true);
        GLES30.glActiveTexture(0x84c2);GLES30.glBindTexture(0xde1,17);verify.invoke(scope);
        check(scope.status().getLong("rg8_binding_checks")==1,"actual unit-two candidate binding observed");
        GLES30.glBindTexture(0xde1,18);
        try{verify.invoke(scope);throw new AssertionError("wrong bound texture accepted");}catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalStateException,"mismatched binding rejected");}
        Throwable[] crossThread={null};Thread thread=new Thread(()->{try{scope.close();}catch(Throwable t){crossThread[0]=t;}});
        thread.start();thread.join();check(crossThread[0] instanceof IllegalStateException&&!scope.status().getBoolean("closed"),"wrong thread cannot release GL resource");
        scope.close();scope.close();check(GLES30.deleted.equals(java.util.List.of(18)),"reference release once, preserves candidate");
        scene.dispose();check(GLES30.deleted.equals(java.util.List.of(18,17)),"scene later releases candidate once");
        scene=fixture(asset,on);GLES30.reset();upload(scene,asset.ormMap(),2);GLES30.failRgba=true;
        try{scene.createOrmComparison();throw new AssertionError("injected reference failure");}catch(IllegalStateException expected){check(expected.getMessage().contains("injected RGBA"),"creation retains upload error");}
        check(GLES30.deleted.equals(java.util.List.of(18))&&((int[])get(scene,"detailTextures"))[1]==17,"failed reference released, candidate retained");
        check(status(scene).getString("actual").equals("rg8")&&get(scene,"ormComparison")==null,"failed comparison restores actual scene status");
        scene.dispose();check(GLES30.deleted.equals(java.util.List.of(18,17)),"failed reference does not cause later double deletion");
    }
    private static AvatarGpuScene fixture(AvatarAsset asset,AvatarOrmUploadPolicy.Decision policy)throws Exception{
        Field singleton=Unsafe.class.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        AvatarGpuScene scene=(AvatarGpuScene)((Unsafe)singleton.get(null)).allocateInstance(AvatarGpuScene.class);
        set(scene,"asset",asset);set(scene,"ormUploadPolicy",policy);set(scene,"detailTextures",new int[2]);set(scene,"allocatedBuffers",new ArrayList<Integer>());
        return scene;
    }
    private static void restored(){check(GLES30.state.get(0xcf5)==8&&GLES30.state.get(0xcf2)==99&&GLES30.state.get(0xcf3)==3&&GLES30.state.get(0xcf4)==2,"all unpack state restored");}
    private static AvatarAsset.AlbedoAtlas png(int w,int h)throws Exception{
        BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)image.setRGB(x,y,(x*27<<16)|(y*3<<8)|0x7f);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return new AvatarAsset.AlbedoAtlas(bytes.toByteArray(),w,h);
    }
    private static Throwable failingUpload(AvatarGpuScene scene,AvatarAsset.AlbedoAtlas map)throws Exception{
        try{UPLOAD.invoke(scene,map,2);throw new AssertionError("expected upload failure");}catch(InvocationTargetException e){return e.getCause();}
    }
    private static void upload(AvatarGpuScene s,AvatarAsset.AlbedoAtlas a,int unit)throws Exception{try{UPLOAD.invoke(s,a,unit);}catch(InvocationTargetException e){throw new AssertionError("production upload failed",e.getCause());}}
    private static JSONObject status(AvatarGpuScene s)throws Exception{return (JSONObject)STATUS.invoke(s);}
    private static void set(Object target,String name,Object value)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static Object get(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static void check(boolean good,String message){checks++;if(!good)throw new AssertionError(message);}
}
