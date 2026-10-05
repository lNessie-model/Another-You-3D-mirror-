package com.mirror.bench;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Runs the production CPU loader against actual APK assets; does not simulate GL or artwork. */
public final class CameraPreviewBundledAssetTest {
    private static int checks;
    private static final class Assets extends AssetManager {
        final Path root;String corrupt="";int models;
        Assets(Path root){this.root=root;}
        @Override public InputStream open(String name)throws IOException{
            if(name.endsWith(".glb"))models++;
            if(name.equals(corrupt))return new ByteArrayInputStream(new byte[]{1,2,3});
            return Files.newInputStream(root.resolve(name));
        }
    }
    private static final class TestContext extends Context {
        final Assets assets;final Map<String,BundledCatalogTestSupport.Preferences> prefs=new HashMap<>();
        TestContext(Path root){assets=new Assets(root);}
        @Override public AssetManager getAssets(){return assets;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){
            return prefs.computeIfAbsent(name,key->new BundledCatalogTestSupport.Preferences());
        }
        void rawSelection(String id){getSharedPreferences("bundled_avatar_selection_v1",MODE_PRIVATE).edit().putString("selected_id",id).commit();}
    }
    public static void main(String[] arguments)throws Exception{
        Path root=Path.of(arguments[0]);File unused=new File(arguments[1]);TestContext context=new TestContext(root);
        CameraPreviewAsset geralt=CameraPreviewAsset.load(context,unused,29);
        check(geralt.source.equals("bundled:geralt"),"default calibration uses same product role");
        check(geralt.sha256.equals("9381f452c53098314f97e1a1799ec55d2be37878e66bf205c7a26958afcef531"),"actual corrected model loaded");
        check(geralt.name.equals("杰洛特"),"actual catalog display name");
        check(context.assets.models==1,"only selected model decoded");
        check(!unused.exists(),"bundled preview does not create or clean imported store");
        BundledAvatarSelection.save(context,"builtin-guide");
        CameraPreviewAsset guide=CameraPreviewAsset.load(context,unused,30);
        check(guide.source.equals("bundled:builtin-guide"),"explicit bundle precedes API30 imported store");
        check(guide.sha256.equals("8349c9b7795a317c7f04cc5cb10d0fc4b2b53171689fe5d2e07965b4a5f1e407"),"actual reference guide loaded");
        check(context.assets.models==2,"no eager catalog model decoding");
        check(!unused.exists(),"explicit bundle does not touch imported store");
        context.rawSelection("removed-role");int before=context.assets.models;
        expectFailure(()->CameraPreviewAsset.load(context,unused,30),"removed identity visible");
        check(context.assets.models==before,"invalid choice cannot load fallback model");
        BundledAvatarSelection.save(context,"geralt");
        context.assets.corrupt="avatars/catalog/geralt/avatar.json";
        expectFailure(()->CameraPreviewAsset.load(context,unused,30),"manifest corruption rejected");
        check(context.assets.models==before,"bad manifest rejected before GLB read");
        context.assets.corrupt="avatars/catalog/geralt/character.glb";
        expectFailure(()->CameraPreviewAsset.load(context,unused,30),"GLB digest corruption rejected");
        check(!unused.exists(),"invalid bundle preserves store boundary");
        Thread.currentThread().interrupt();boolean cancelled=false;
        try{CameraPreviewAsset.load(context,unused,30);}catch(InterruptedIOException expected){cancelled=true;}
        finally{Thread.interrupted();}
        check(cancelled,"cancelled background load publishes no asset");
        System.out.println("PASS: "+checks+" bundled calibration checks with two real GLBs; no GL/device claim");
    }
    private interface Action{void run()throws Exception;}
    private static void expectFailure(Action action,String message)throws Exception{
        boolean failed=false;try{action.run();}catch(IOException expected){failed=true;}check(failed,message);
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
