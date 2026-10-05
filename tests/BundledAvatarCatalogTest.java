package com.mirror.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class BundledAvatarCatalogTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private interface Checked {void run()throws Exception;}
    private static void rejected(Checked task,String message)throws Exception {
        try{task.run();throw new AssertionError("Accepted "+message);}catch(IOException expected){checks++;}
    }
    private static void rejected(JSONObject json,String message)throws Exception {
        var assets=new BundledCatalogTestSupport.Assets(json.toString());
        rejected(()->BundledAvatarCatalog.read(assets),message);
        check(assets.closed==assets.opened,"Failed parse leaked the asset stream");
    }
    public static void main(String[] args)throws Exception {
        var assets=new BundledCatalogTestSupport.Assets(BundledCatalogTestSupport.catalog().toString());
        List<BundledAvatarCatalog.Entry> entries=BundledAvatarCatalog.read(assets);
        check(entries.size()==2,"Real JSON catalog entry count");
        var geralt=BundledAvatarCatalog.find(entries,"geralt");
        check(geralt.displayName.equals("杰洛特")&&geralt.directory.equals("avatars/catalog/geralt"),"Unicode and resolved asset directory");
        check(geralt.drawCalls==7&&geralt.decodedBytes==33554432L,"Typed resource metadata");
        check(BundledAvatarCatalog.defaultId(assets).equals("geralt"),"Default identity");
        check(assets.opened==assets.closed,"Successful parse leaked stream");
        try{entries.clear();throw new AssertionError("Mutable catalog");}catch(UnsupportedOperationException expected){checks++;}
        rejected(()->BundledAvatarCatalog.find(entries,"missing"),"unknown identity");
        rejected(()->BundledAvatarCatalog.find(entries,"../geralt"),"invalid requested identity");

        JSONObject duplicate=BundledCatalogTestSupport.catalog();
        duplicate.getJSONArray("entries").put(BundledCatalogTestSupport.entry("geralt"));
        rejected(duplicate,"duplicate identity");
        for(String directory:new String[]{"avatars/catalog/../geralt","/avatars/catalog/geralt","avatars/catalog/builtin-guide","C:/geralt","https://host/geralt"}) {
            JSONObject json=BundledCatalogTestSupport.catalog();json.getJSONArray("entries").getJSONObject(0).put("directory",directory);
            rejected(json,"directory "+directory);
        }
        for(String thumbnail:new String[]{"../thumbnail.png","/data/thumbnail.png","avatars/catalog/builtin-guide/thumbnail.png","avatars/catalog/geralt/%2e%2e.png","avatars/catalog/geralt/sub/thumbnail.png"}) {
            JSONObject json=BundledCatalogTestSupport.catalog();json.getJSONArray("entries").getJSONObject(0).put("thumbnail",thumbnail);
            rejected(json,"thumbnail "+thumbnail);
        }
        for(String id:new String[]{"Geralt","../geralt"," geralt","","-geralt"}) {
            JSONObject json=BundledCatalogTestSupport.catalog();json.getJSONArray("entries").getJSONObject(0).put("id",id);
            rejected(json,"invalid identity");
        }
        JSONObject missingDefault=BundledCatalogTestSupport.catalog().put("defaultId","missing");rejected(missingDefault,"unknown default");
        rejected(BundledCatalogTestSupport.catalog().put("schemaVersion",2),"unsupported schema");
        rejected(BundledCatalogTestSupport.catalog().put("schemaVersion","1"),"string schema");
        for(String property:new String[]{"modelSha256","manifestSha256"}) {
            JSONObject json=BundledCatalogTestSupport.catalog();json.getJSONArray("entries").getJSONObject(0).put(property,"invalid");rejected(json,"invalid "+property);
        }
        for(String property:new String[]{"vertices","triangles","drawCalls","decodedBytes"}) {
            JSONObject json=BundledCatalogTestSupport.catalog();json.getJSONArray("entries").getJSONObject(0).put(property,-1);rejected(json,"negative "+property);
        }
        JSONObject fraction=BundledCatalogTestSupport.catalog();fraction.getJSONArray("entries").getJSONObject(0).put("drawCalls",1.5);rejected(fraction,"fractional count");
        JSONObject resource=BundledCatalogTestSupport.catalog();resource.getJSONArray("entries").getJSONObject(0).put("decodedBytes",80L*1024*1024+1);rejected(resource,"decoded resource over budget");
        JSONObject names=BundledCatalogTestSupport.catalog();names.getJSONArray("entries").getJSONObject(0).put("displayName","bad\nlabel");rejected(names,"control character label");
        JSONObject tooMany=BundledCatalogTestSupport.catalog();JSONArray many=new JSONArray().put(BundledCatalogTestSupport.entry("geralt"));
        for(int i=0;i<64;i++)many.put(BundledCatalogTestSupport.entry("role-"+i));tooMany.put("entries",many);rejected(tooMany,"65 entries");
        String valid=BundledCatalogTestSupport.catalog().toString();
        String maximum=valid+" ".repeat(256*1024-valid.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        check(BundledAvatarCatalog.read(new BundledCatalogTestSupport.Assets(maximum)).size()==2,"Inclusive 256KiB bound");
        rejected(()->BundledAvatarCatalog.read(new BundledCatalogTestSupport.Assets(maximum+" ")),"catalog byte overflow");
        rejected(()->BundledAvatarCatalog.read(new BundledCatalogTestSupport.Assets(new byte[]{(byte)0xff})),"malformed UTF8");
        rejected(()->BundledAvatarCatalog.read(new BundledCatalogTestSupport.Assets(valid+"{}")),"trailing JSON object");
        rejected(()->BundledAvatarCatalog.read(new BundledCatalogTestSupport.Assets("[".repeat(20)+"]".repeat(20))),"deep JSON");
        if(args.length>0){
            var realAssets=new BundledCatalogTestSupport.Assets(Files.readAllBytes(Path.of(args[0])));
            var actual=BundledAvatarCatalog.read(realAssets);String defaultId=BundledAvatarCatalog.defaultId(realAssets);
            check(BundledAvatarCatalog.find(actual,defaultId)!=null,"Actual APK catalog resolves its default");
            System.out.println("Actual catalog: "+actual.size()+" entries; default="+defaultId);
        }
        System.out.println("BundledAvatarCatalogTest PASS "+checks+" checks");
    }
}
