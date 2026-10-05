package com.mirror.bench;

import android.content.res.AssetManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

/** Contract failures and packaged-byte witnesses, using the real production parser. */
public final class AssetLibraryCatalogTest {
    private static int checks;
    private static void check(boolean pass,String message){checks++;if(!pass)throw new AssertionError(message);}
    private interface Checked {void run()throws Exception;}
    private static void rejected(Checked action,String message)throws Exception{
        try{action.run();throw new AssertionError("Accepted "+message);}catch(IOException expected){checks++;}
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static final byte[] MODEL="current verified role bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MANIFEST="current verified role manifest".getBytes(StandardCharsets.UTF_8);
    private static byte[] png(int width,int height){
        byte[] bytes=new byte[24];byte[] sig={(byte)137,80,78,71,13,10,26,10};System.arraycopy(sig,0,bytes,0,8);
        bytes[11]=13;bytes[12]=73;bytes[13]=72;bytes[14]=68;bytes[15]=82;
        java.nio.ByteBuffer.wrap(bytes).putInt(16,width).putInt(20,height);return bytes;
    }
    private static JSONObject entry(String id,String type,String status)throws Exception{
        return new JSONObject().put("id",id).put("displayName","艾达 / 自身原件").put("type",type).put("status",status)
            .put("sourceLabel","2026-10-03 自身生成原件").put("notes","面部校正中，暂不用于实时魔镜")
            .put("sourcePath","library/"+id+"/source."+(type.equals("image")?"png":"glb"))
            .put("sourceSha256",hash("own original source".getBytes(StandardCharsets.UTF_8)))
            .put("previewPath","library/"+id+"/preview.png").put("previewSha256",hash(png(512,512)));
    }
    private static JSONObject catalog(JSONObject... entries){
        JSONArray array=new JSONArray();for(JSONObject entry:entries)array.put(entry);
        return new JSONObject().put("schemaVersion",1).put("entries",array);
    }
    private static JSONObject liveCatalog()throws Exception{
        return new JSONObject().put("schemaVersion",1).put("defaultId","geralt").put("entries",new JSONArray()
            .put(new JSONObject().put("id","geralt").put("displayName","杰洛特").put("directory","avatars/catalog/geralt")
                .put("modelSha256",hash(MODEL)).put("manifestSha256",hash(MANIFEST))
                .put("thumbnail","avatars/catalog/geralt/thumbnail.png").put("status","device_verified")
                .put("vertices",19907).put("triangles",19157).put("drawCalls",7).put("decodedBytes",73552494)));
    }
    private static final class Assets extends AssetManager{
        final Map<String,byte[]> files=new HashMap<>();final List<String> opened=new ArrayList<>();int closed;
        Assets(JSONObject json)throws Exception{files.put("library/catalog.json",json.toString().getBytes(StandardCharsets.UTF_8));
            files.put("avatars/catalog/catalog.json",liveCatalog().toString().getBytes(StandardCharsets.UTF_8));
            files.put("avatars/catalog/geralt/character.glb",MODEL);files.put("avatars/catalog/geralt/avatar.json",MANIFEST);}
        public InputStream open(String name)throws IOException{
            opened.add(name);byte[] bytes=files.get(name);if(bytes==null)throw new IOException("Missing "+name);
            return new ByteArrayInputStream(bytes){public void close()throws IOException{closed++;super.close();}};
        }
    }
    private static void invalid(JSONObject json,String label)throws Exception{
        Assets assets=new Assets(json);rejected(()->AssetLibraryCatalog.read(assets),label);
        check(assets.closed==assets.opened.size(),"Stream leak on "+label);
    }
    public static void main(String[] args)throws Exception{
        Assets assets=new Assets(catalog(entry("ada","head","static_preview"),entry("gothic-v1","scene","archive"),entry("mist","image","static_preview")));
        var entries=AssetLibraryCatalog.read(assets);
        check(entries.size()==3,"Head/scene/image entries");check(assets.opened.equals(List.of("library/catalog.json")),"Metadata read eagerly loaded another asset");
        check(assets.closed==1,"Successful catalog stream closure");
        check(AssetLibraryCatalog.find(entries,"ada").sourceLabel.contains("自身"),"Unicode own-source metadata");
        try{entries.clear();throw new AssertionError("Mutable entries");}catch(UnsupportedOperationException expected){checks++;}
        rejected(()->AssetLibraryCatalog.find(entries,"absent"),"unknown identity");
        rejected(()->AssetLibraryCatalog.find(entries,"../ada"),"traversal requested identity");
        invalid(catalog(entry("ada","head","static_preview"),entry("ada","scene","archive")),"duplicate identity");
        for(String id:new String[]{"Ada","../ada"," ada","","-ada"})invalid(catalog(entry("ada","head","static_preview").put("id",id)),"id "+id);
        for(String p:new String[]{"C:/source.glb","https://host/source.glb","library/ada/../source.glb","library/other/source.glb","library/ada/sub/source.glb","library/ada/%2e%2e.glb","assets/library/ada/source.glb"})
            invalid(catalog(entry("ada","head","static_preview").put("sourcePath",p)),"source path "+p);
        for(String p:new String[]{"../preview.png","library/other/preview.png","library/ada/preview.jpg","library/ada/sub/preview.png"})
            invalid(catalog(entry("ada","head","static_preview").put("previewPath",p)),"preview path "+p);
        invalid(catalog(entry("ada","image","static_preview").put("sourcePath","library/ada/source.glb")),"image source extension");
        for(String key:new String[]{"sourceSha256","previewSha256"})invalid(catalog(entry("ada","head","static_preview").put(key,"bad")),"hash "+key);
        invalid(catalog(entry("ada","other","static_preview")),"unknown type");
        invalid(catalog(entry("ada","head","ready")),"unknown status");
        invalid(catalog(entry("ada","head","static_preview").put("liveRoleId","geralt")),"hidden activation field");
        invalid(catalog(entry("ada","head","static_preview").put("unknown","bad")),"unknown operation");
        invalid(catalog(entry("ada","head","static_preview").put("notes","x".repeat(1201))),"long notes");
        invalid(catalog(entry("ada","head","static_preview").put("displayName","bad\u0000label")),"control text");
        Assets unicode=new Assets(catalog(entry("ada","head","static_preview").put("notes","unpaired")));
        unicode.files.put("library/catalog.json",new String(unicode.files.get("library/catalog.json"),StandardCharsets.UTF_8)
            .replace("\"unpaired\"","\"bad\\ud800\"").getBytes(StandardCharsets.UTF_8));
        rejected(()->AssetLibraryCatalog.read(unicode),"unpaired escaped Unicode surrogate");
        check(unicode.closed==1,"Rejected Unicode stream closed");
        invalid(catalog(entry("ada","head","static_preview")).put("unknown","bad"),"unknown root field");
        invalid(catalog(entry("ada","head","static_preview")).put("schemaVersion",2),"schema version");
        invalid(catalog(entry("ada","head","static_preview")).put("schemaVersion","1"),"string schema");
        JSONArray many=new JSONArray();for(int i=0;i<129;i++)many.put(entry("asset-"+i,"head","static_preview"));
        invalid(new JSONObject().put("schemaVersion",1).put("entries",many),"entry count");
        var valid=assets.files.get("library/catalog.json");assets.files.put("library/catalog.json",new byte[]{(byte)0xff});
        rejected(()->AssetLibraryCatalog.read(assets),"malformed UTF8");
        assets.files.put("library/catalog.json",(new String(valid,StandardCharsets.UTF_8)+"{}").getBytes(StandardCharsets.UTF_8));
        rejected(()->AssetLibraryCatalog.read(assets),"trailing JSON");
        assets.files.put("library/catalog.json",("[".repeat(30)+"]".repeat(30)).getBytes(StandardCharsets.UTF_8));
        rejected(()->AssetLibraryCatalog.read(assets),"deep recursive JSON");
        byte[] maximum=new byte[256*1024];java.util.Arrays.fill(maximum,(byte)' ');System.arraycopy(valid,0,maximum,0,valid.length);
        assets.files.put("library/catalog.json",maximum);check(AssetLibraryCatalog.read(assets).size()==3,"Inclusive catalog byte bound");
        assets.files.put("library/catalog.json",java.util.Arrays.copyOf(maximum,maximum.length+1));rejected(()->AssetLibraryCatalog.read(assets),"oversized catalog");
        AssetManager infinite=new AssetManager(){public InputStream open(String name){return new InputStream(){public int read(){return 32;}public int read(byte[] b,int o,int n){java.util.Arrays.fill(b,o,o+n,(byte)32);return n;}};}};
        rejected(()->AssetLibraryCatalog.read(infinite),"infinite advancing stream");
        AssetManager stalled=new AssetManager(){public InputStream open(String name){return new InputStream(){public int read(){return 0;}public int read(byte[] b,int o,int n){return 0;}};}};
        rejected(()->AssetLibraryCatalog.read(stalled),"stalled stream");
        Thread.currentThread().interrupt();
        try{Assets cancelled=new Assets(catalog(entry("ada","head","static_preview")));
            rejected(()->AssetLibraryCatalog.read(cancelled),"cancelled metadata read");check(cancelled.closed==1,"Cancelled metadata stream closed");
        }finally{Thread.interrupted();}

        JSONObject ready=entry("geralt-original","head","runtime_ready").put("liveRoleId","geralt").put("liveModelSha256",hash(MODEL));
        Assets readyAssets=new Assets(catalog(ready));var readyEntry=AssetLibraryCatalog.read(readyAssets).get(0);
        check(readyAssets.opened.equals(List.of("library/catalog.json","avatars/catalog/catalog.json")),"Ready metadata read decoded source/live model");
        check(!readyEntry.sourceSha256.equals(readyEntry.liveModelSha256),"Original and authored hashes allowed to differ");
        check(AssetLibraryCatalog.verifiedRuntimeEntry(readyAssets,readyEntry).id.equals("geralt"),"Selected current role actual-byte hash gate");
        check(!readyAssets.opened.contains(readyEntry.sourcePath),"Activation read raw archive model unnecessarily");
        invalid(catalog(new JSONObject(ready.toString()).put("liveModelSha256","c".repeat(64))),"fake ready hash");
        invalid(catalog(new JSONObject(ready.toString()).put("liveRoleId","unknown")),"fake ready identity");
        invalid(catalog(new JSONObject(ready.toString()).put("type","scene")),"scene activation");
        JSONObject missingHash=new JSONObject(ready.toString());missingHash.remove("liveModelSha256");invalid(catalog(missingHash),"missing ready live hash");
        Assets notVerified=new Assets(catalog(ready));notVerified.files.put("avatars/catalog/catalog.json",
            liveCatalog().toString().replace("device_verified","candidate").getBytes(StandardCharsets.UTF_8));
        rejected(()->AssetLibraryCatalog.read(notVerified),"unverified current role ready claim");
        Assets missingLive=new Assets(catalog(ready));missingLive.files.remove("avatars/catalog/catalog.json");rejected(()->AssetLibraryCatalog.read(missingLive),"missing live catalog");
        readyAssets.files.put("avatars/catalog/geralt/character.glb","changed".getBytes(StandardCharsets.UTF_8));
        rejected(()->AssetLibraryCatalog.verifiedRuntimeEntry(readyAssets,readyEntry),"modified actual live bytes");
        readyAssets.files.put("avatars/catalog/geralt/character.glb",MODEL);readyAssets.files.put("avatars/catalog/geralt/avatar.json",MODEL);
        rejected(()->AssetLibraryCatalog.verifiedRuntimeEntry(readyAssets,readyEntry),"modified actual live manifest");
        Assets previewAssets=new Assets(catalog(entry("ada","head","static_preview")));var previewEntry=AssetLibraryCatalog.read(previewAssets).get(0);
        previewAssets.files.put(previewEntry.previewPath,png(512,512));check(AssetLibraryCatalog.readPreview(previewAssets,previewEntry).length==24,"Selected preview SHA and PNG bounds");
        previewAssets.files.put(previewEntry.previewPath,png(2049,512));rejected(()->AssetLibraryCatalog.readPreview(previewAssets,previewEntry),"changed preview bytes");
        byte[] oversizedPng=png(2049,512);Assets oversized=new Assets(catalog(entry("ada","head","static_preview").put("previewSha256",hash(oversizedPng))));
        var oversizedEntry=AssetLibraryCatalog.read(oversized).get(0);oversized.files.put(oversizedEntry.previewPath,oversizedPng);
        rejected(()->AssetLibraryCatalog.readPreview(oversized,oversizedEntry),"authenticated oversized PNG dimensions");
        byte[] notPng="this is an authenticated non-image file".getBytes(StandardCharsets.UTF_8);
        Assets malformedImage=new Assets(catalog(entry("ada","head","static_preview").put("previewSha256",hash(notPng))));
        var malformedEntry=AssetLibraryCatalog.read(malformedImage).get(0);malformedImage.files.put(malformedEntry.previewPath,notPng);
        rejected(()->AssetLibraryCatalog.readPreview(malformedImage,malformedEntry),"authenticated non-PNG preview");
        previewAssets.files.put(previewEntry.previewPath,new byte[8*1024*1024+1]);rejected(()->AssetLibraryCatalog.readPreview(previewAssets,previewEntry),"huge preview stream");
        rejected(()->AssetLibraryCatalog.verifiedRuntimeEntry(previewAssets,previewEntry),"static preview activation");
        rejected(()->AssetLibraryCatalog.verifiedRuntimeEntry(assets,entries.get(1)),"archive activation");
        Assets current=new Assets(catalog(ready));
        AssetManager hugeLive=new AssetManager(){public InputStream open(String name)throws IOException{
            if(!name.endsWith("character.glb"))return current.open(name);
            return new InputStream(){public int read(){return 0;}public int read(byte[] b,int o,int n){java.util.Arrays.fill(b,o,o+n,(byte)0);return n;}};
        }};
        rejected(()->AssetLibraryCatalog.verifiedRuntimeEntry(hugeLive,readyEntry),"selected live model infinite byte stream");
        Thread.currentThread().interrupt();
        try{rejected(()->AssetLibraryCatalog.verifiedRuntimeEntry(current,readyEntry),"cancelled ready preparation");}
        finally{Thread.interrupted();}
        if(args.length==2){verifyActual(args[0],args[1]);}else if(args.length!=0)throw new IllegalArgumentException("Expected --assets-root PATH or --apk PATH");
        System.out.println("AssetLibraryCatalogTest PASS "+checks+" checks");
    }
    private static void verifyActual(String mode,String location)throws Exception{
        if(!mode.equals("--apk")&&!mode.equals("--assets-root"))throw new IllegalArgumentException("Unknown actual asset mode");
        ZipFile zip=mode.equals("--apk")?new ZipFile(location):null;Path root=Path.of(location);
        AssetManager actual=new AssetManager(){public InputStream open(String name)throws IOException{
            if(zip==null)return Files.newInputStream(root.resolve(name));
            var file=zip.getEntry("assets/"+name);if(file==null)throw new IOException("Missing packaged asset "+name);return zip.getInputStream(file);
        }};
        try{
            var entries=AssetLibraryCatalog.read(actual);check(!entries.isEmpty(),"Actual library empty");
            for(var entry:entries){
                byte[] preview=AssetLibraryCatalog.readPreview(actual,entry);checks++;
                var decoded=javax.imageio.ImageIO.read(new ByteArrayInputStream(preview));
                check(decoded!=null&&decoded.getWidth()<=2048&&decoded.getHeight()<=2048,"Actual preview cannot decode: "+entry.id);
                decoded.flush();
                try(InputStream input=actual.open(entry.sourcePath)){
                    MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] b=new byte[16384];int n;
                    while((n=input.read(b))!=-1)hash.update(b,0,n);
                    check(HexFormat.of().formatHex(hash.digest()).equals(entry.sourceSha256),"Actual source bytes mismatch: "+entry.id);
                }
                if(entry.status.equals("runtime_ready")){AssetLibraryCatalog.verifiedRuntimeEntry(actual,entry);checks++;}
            }
            System.out.println("Actual "+mode+" metadata/source/preview verified: "+entries.size()+" entries");
        }finally{if(zip!=null)zip.close();}
    }
}
