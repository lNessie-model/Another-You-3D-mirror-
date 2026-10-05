package com.mirror.bench;

import android.content.res.AssetManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Own-source APK library metadata. Reads no model or preview while listing entries. */
public final class AssetLibraryCatalog {
    public static final String ASSET_PATH="library/catalog.json";
    public static final int MAX_CATALOG_BYTES=256*1024,MAX_ENTRIES=128,MAX_PREVIEW_BYTES=8*1024*1024;
    private static final Set<String> FIELDS=Set.of("id","displayName","type","status","sourceLabel","notes",
        "sourcePath","sourceSha256","previewPath","previewSha256","liveRoleId","liveModelSha256");
    private AssetLibraryCatalog(){}
    public static final class Entry {
        public final String id,displayName,type,status,sourceLabel,notes,sourcePath,sourceSha256,previewPath,previewSha256;
        public final String liveRoleId,liveModelSha256;
        private Entry(JSONObject row)throws Exception{
            keys(row,FIELDS);id=slug(text(row,"id",64));displayName=text(row,"displayName",80);
            type=text(row,"type",16);status=text(row,"status",32);
            if(!Set.of("head","scene","image").contains(type))throw invalid("Unknown library type");
            if(!Set.of("static_preview","archive","runtime_ready").contains(status))throw invalid("Unknown library status");
            sourceLabel=text(row,"sourceLabel",160);notes=text(row,"notes",1200);
            sourcePath=path(text(row,"sourcePath",180),id,type.equals("image")?"png":"glb");
            previewPath=path(text(row,"previewPath",180),id,"png");
            sourceSha256=digest(text(row,"sourceSha256",64));previewSha256=digest(text(row,"previewSha256",64));
            if(status.equals("runtime_ready")){
                if(!type.equals("head"))throw invalid("Only a verified head can activate a live role");
                liveRoleId=slug(text(row,"liveRoleId",64));liveModelSha256=digest(text(row,"liveModelSha256",64));
            }else{
                if(row.has("liveRoleId")||row.has("liveModelSha256"))throw invalid("Preview/archive entries cannot contain live activation fields");
                liveRoleId=null;liveModelSha256=null;
            }
        }
    }
    public static List<Entry> read(AssetManager assets)throws IOException{
        if(assets==null)throw invalid("APK assets required");
        String source;
        try(InputStream input=assets.open(ASSET_PATH)){
            byte[] bytes=bounded(input,MAX_CATALOG_BYTES);
            try{source=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
            catch(Exception failure){throw new IOException("Library catalog is not UTF-8",failure);}
        }
        boundJson(source);
        try{
            JSONTokener parser=new JSONTokener(source);Object value=parser.nextValue();
            if(!(value instanceof JSONObject)||parser.nextClean()!=0)throw invalid("Library requires one JSON object");
            JSONObject root=(JSONObject)value;keys(root,Set.of("schemaVersion","entries"));
            Object schema=root.get("schemaVersion");if(!(schema instanceof Number)||((Number)schema).doubleValue()!=1)throw invalid("Unsupported library schema");
            Object raw=root.get("entries");if(!(raw instanceof JSONArray))throw invalid("Library entries must be an array");
            JSONArray array=(JSONArray)raw;if(array.length()<1||array.length()>MAX_ENTRIES)throw invalid("Library requires 1 to 128 entries");
            ArrayList<Entry> entries=new ArrayList<>(array.length());Set<String> seen=new HashSet<>();boolean hasLive=false;
            for(int i=0;i<array.length();i++){
                if(!(array.get(i) instanceof JSONObject))throw invalid("Library entry must be an object");
                Entry entry=new Entry(array.getJSONObject(i));if(!seen.add(entry.id))throw invalid("Duplicate library identity");
                hasLive|=entry.status.equals("runtime_ready");entries.add(entry);
            }
            if(hasLive){
                List<BundledAvatarCatalog.Entry> live=BundledAvatarCatalog.read(assets);
                for(Entry entry:entries)if(entry.status.equals("runtime_ready"))matchingLive(live,entry);
            }
            return Collections.unmodifiableList(entries);
        }catch(IOException failure){throw failure;}
        catch(Exception failure){throw new IOException("APK library catalog is invalid",failure);}
    }
    public static Entry find(List<Entry> entries,String id)throws IOException{
        slug(id);if(entries==null)throw invalid("Library entries required");
        for(Entry entry:entries)if(entry!=null&&entry.id.equals(id))return entry;
        throw invalid("Library identity is absent");
    }
    /** Verify the selected current role only, streaming bytes; never decodes any GLB here. */
    public static BundledAvatarCatalog.Entry verifiedRuntimeEntry(AssetManager assets,Entry entry)throws IOException{
        if(entry==null||!entry.status.equals("runtime_ready"))throw invalid("This asset is a static preview or archive");
        BundledAvatarCatalog.Entry live=matchingLive(BundledAvatarCatalog.read(assets),entry);
        if(!hashAsset(assets,live.directory+"/character.glb",32*1024*1024).equals(live.modelSha256))throw invalid("Current role model bytes changed");
        if(!hashAsset(assets,live.directory+"/avatar.json",256*1024).equals(live.manifestSha256))throw invalid("Current role manifest bytes changed");
        return live;
    }
    private static BundledAvatarCatalog.Entry matchingLive(List<BundledAvatarCatalog.Entry> entries,Entry entry)throws IOException{
        BundledAvatarCatalog.Entry live=BundledAvatarCatalog.find(entries,entry.liveRoleId);
        if(!live.modelSha256.equals(entry.liveModelSha256)
            ||!(live.status.equals("device_verified")||live.status.equals("legacy_reference")))throw invalid("Library ready state does not match a verified current role");
        return live;
    }
    /** Open exactly one selected preview; authenticate bytes and bound PNG dimensions before decoding. */
    public static byte[] readPreview(AssetManager assets,Entry entry)throws IOException{
        if(assets==null||entry==null)throw invalid("Selected preview required");
        byte[] bytes;try(InputStream input=assets.open(entry.previewPath)){bytes=bounded(input,MAX_PREVIEW_BYTES);}
        if(!hex(messageDigest().digest(bytes)).equals(entry.previewSha256))throw invalid("Own-source preview bytes changed");
        byte[] signature={(byte)137,80,78,71,13,10,26,10};
        if(bytes.length<24)throw invalid("Preview must contain a PNG header");
        for(int i=0;i<signature.length;i++)if(bytes[i]!=signature[i])throw invalid("Preview is not PNG");
        ByteBuffer header=ByteBuffer.wrap(bytes);
        if(header.getInt(8)!=13||header.getInt(12)!=0x49484452)throw invalid("Invalid PNG image header");
        int width=header.getInt(16),height=header.getInt(20);
        if(width<1||height<1||width>2048||height>2048)throw invalid("Preview dimensions exceed library bound");
        return bytes;
    }
    private static String hashAsset(AssetManager assets,String path,int maximum)throws IOException{
        MessageDigest digest=messageDigest();int total=0,empty=0;
        try(InputStream input=assets.open(path)){
            byte[] chunk=new byte[16384];int count;
            while((count=input.read(chunk))!=-1){
                interrupted();
                if(count==0){if(++empty>8)throw invalid("Asset stream did not advance");continue;}
                empty=0;if(count>maximum-total)throw invalid("Selected live asset exceeds its byte bound");
                total+=count;digest.update(chunk,0,count);
            }
        }
        if(total==0)throw invalid("Selected live asset is empty");return hex(digest.digest());
    }
    private static byte[] bounded(InputStream input,int maximum)throws IOException{
        ByteArrayOutputStream output=new ByteArrayOutputStream(4096);byte[] chunk=new byte[4096];int empty=0,count;
        while((count=input.read(chunk))!=-1){
            interrupted();
            if(count==0){if(++empty>8)throw invalid("Asset stream did not advance");continue;}
            empty=0;if(count>maximum-output.size())throw invalid("Library asset exceeds byte bound");output.write(chunk,0,count);
        }
        if(output.size()==0)throw invalid("Library asset is empty");return output.toByteArray();
    }
    private static MessageDigest messageDigest()throws IOException{
        try{return MessageDigest.getInstance("SHA-256");}catch(Exception failure){throw new IOException("SHA-256 unavailable",failure);}
    }
    private static void interrupted()throws InterruptedIOException{
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Library operation cancelled");
    }
    private static String hex(byte[] bytes){char[] text=new char[bytes.length*2];String digits="0123456789abcdef";
        for(int i=0;i<bytes.length;i++){int b=bytes[i]&255;text[i*2]=digits.charAt(b>>>4);text[i*2+1]=digits.charAt(b&15);}return new String(text);}
    private static void keys(JSONObject row,Set<String> allowed)throws IOException{
        var keys=row.keys();while(keys.hasNext())if(!allowed.contains(keys.next()))throw invalid("Unknown library field");
    }
    private static String path(String value,String id,String extension)throws IOException{
        String prefix="library/"+id+"/";
        if(!value.startsWith(prefix)||value.contains("..")||!value.substring(prefix.length()).matches("[a-z0-9][a-z0-9_-]{0,63}\\."+extension))
            throw invalid("Library asset must stay inside its own APK directory");return value;
    }
    private static String slug(String value)throws IOException{
        if(value==null||!value.matches("[a-z0-9][a-z0-9-]{0,63}"))throw invalid("Invalid library identity");return value;
    }
    private static String digest(String value)throws IOException{
        if(!value.matches("[0-9a-f]{64}"))throw invalid("Invalid library SHA-256");return value;
    }
    private static String text(JSONObject row,String field,int bound)throws Exception{
        Object raw=row.get(field);if(!(raw instanceof String))throw invalid("Library field must be text");String value=(String)raw;
        if(value.trim().isEmpty()||value.length()>bound)throw invalid("Library text exceeds its bound");
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);if(c<32||c==127)throw invalid("Library text contains controls");
            if(Character.isHighSurrogate(c)){if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(++i)))throw invalid("Invalid Unicode text");}
            else if(Character.isLowSurrogate(c))throw invalid("Invalid Unicode text");
        }
        return value;
    }
    private static void boundJson(String source)throws IOException{
        int depth=0;boolean quoted=false,escaped=false;
        for(int i=0;i<source.length();i++){
            char c=source.charAt(i);
            if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;continue;}
            if(c=='"')quoted=true;
            else if(c=='{'||c=='['){if(++depth>8)throw invalid("Library JSON nesting exceeded");}
            else if(c=='}'||c==']'){if(--depth<0)throw invalid("Library JSON is incomplete");}
            else if(!Character.isWhitespace(c)&&",:0123456789.-+eE".indexOf(c)<0)throw invalid("Library must use quoted JSON text");
        }
        if(quoted||depth!=0)throw invalid("Library JSON is incomplete");
    }
    private static IOException invalid(String message){return new IOException(message);}
}
