package com.mirror.bench;

import android.content.res.AssetManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Bounded APK catalog metadata. This class never opens or decodes a GLB or thumbnail. */
public final class BundledAvatarCatalog {
    public static final String ASSET_PATH="avatars/catalog/catalog.json";
    public static final int MAX_CATALOG_BYTES=256*1024,MAX_ENTRIES=64;
    private BundledAvatarCatalog(){}

    public static final class Entry {
        public final String id,displayName,directory,modelSha256,manifestSha256,thumbnail,status;
        public final int vertices,triangles,drawCalls;
        public final long decodedBytes;
        private Entry(JSONObject json)throws Exception {
            id=slug(text(json,"id",64));
            displayName=text(json,"displayName",160);
            directory=text(json,"directory",160);
            if(!directory.equals("avatars/catalog/"+id)
                    &&!(id.equals("builtin-guide")&&directory.equals("avatars/builtin-guide")))
                throw invalid("Role directory does not match its stable identity");
            modelSha256=digest(text(json,"modelSha256",64));
            manifestSha256=digest(text(json,"manifestSha256",64));
            thumbnail=text(json,"thumbnail",256);
            String prefix=directory+"/";
            if(!thumbnail.startsWith(prefix)||thumbnail.contains("..")
                    ||!thumbnail.substring(prefix.length()).matches("[a-z0-9][a-z0-9._-]{0,63}\\.(?:png|jpg|jpeg|webp)"))
                throw invalid("Role thumbnail must be one image inside its APK directory");
            status=text(json,"status",160);
            vertices=(int)integer(json,"vertices",1,20_000);
            // Existing reference avatars still use the production loader's 30k ceiling.
            triangles=(int)integer(json,"triangles",1,30_000);
            drawCalls=(int)integer(json,"drawCalls",1,8);
            decodedBytes=integer(json,"decodedBytes",1,80L*1024*1024);
        }
    }

    /** Returned entries are immutable metadata; parse actual model bytes on their runtime owner. */
    public static List<Entry> read(AssetManager assets)throws IOException{return parse(assets).entries;}
    public static String defaultId(AssetManager assets)throws IOException{return parse(assets).defaultId;}
    public static Entry find(List<Entry> entries,String id)throws IOException {
        slug(id);
        if(entries==null)throw invalid("Role catalog is required");
        for(Entry entry:entries)if(entry!=null&&entry.id.equals(id))return entry;
        throw invalid("Selected role is absent from the APK catalog: "+id);
    }

    // Selection reads one metadata snapshot rather than reopening the catalog for its default.
    static final class Parsed {
        final List<Entry> entries;
        final String defaultId;
        Parsed(List<Entry> entries,String defaultId){this.entries=entries;this.defaultId=defaultId;}
    }
    static Parsed parse(AssetManager assets)throws IOException {
        if(assets==null)throw invalid("APK assets are required");
        String source=readText(assets);
        boundNesting(source);
        try {
            JSONTokener tokener=new JSONTokener(source);
            Object value=tokener.nextValue();
            if(!(value instanceof JSONObject)||tokener.nextClean()!=0)throw invalid("Catalog must contain one JSON object");
            JSONObject root=(JSONObject)value;
            if(integer(root,"schemaVersion",1,1)!=1)throw invalid("Unsupported catalog schema");
            String defaultId=slug(text(root,"defaultId",64));
            Object raw=root.get("entries");
            if(!(raw instanceof JSONArray))throw invalid("Role entries must be an array");
            JSONArray array=(JSONArray)raw;
            if(array.length()<1||array.length()>MAX_ENTRIES)throw invalid("APK role catalog requires 1 to 64 entries");
            ArrayList<Entry> entries=new ArrayList<>(array.length());Set<String> seen=new HashSet<>();
            for(int i=0;i<array.length();i++){
                Object item=array.get(i);
                if(!(item instanceof JSONObject))throw invalid("Role entry must be a JSON object");
                Entry entry=new Entry((JSONObject)item);
                if(!seen.add(entry.id))throw invalid("Duplicate role identity: "+entry.id);
                entries.add(entry);
            }
            List<Entry> immutable=Collections.unmodifiableList(entries);
            find(immutable,defaultId);
            return new Parsed(immutable,defaultId);
        }catch(IOException failure){throw failure;}
        catch(Exception failure){throw new IOException("APK role catalog is invalid",failure);}
    }

    private static String readText(AssetManager assets)throws IOException {
        try(InputStream input=assets.open(ASSET_PATH);ByteArrayOutputStream bytes=new ByteArrayOutputStream(4096)) {
            byte[] chunk=new byte[4096];int emptyReads=0;
            while(true){
                int count=input.read(chunk);if(count<0)break;
                if(count==0){if(++emptyReads>8)throw invalid("APK catalog stream did not advance");continue;}
                emptyReads=0;
                if(count>MAX_CATALOG_BYTES-bytes.size())throw invalid("APK role catalog exceeds 256 KiB");
                bytes.write(chunk,0,count);
            }
            if(bytes.size()==0)throw invalid("APK role catalog is empty");
            try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();}
            catch(CharacterCodingException failure){throw new IOException("APK role catalog is not valid UTF-8",failure);}
        }
    }

    /** Bound the real parser's recursive input before JSONTokener allocates nested objects. */
    private static void boundNesting(String source)throws IOException {
        int depth=0;boolean quoted=false,escaped=false;
        for(int i=0;i<source.length();i++){
            char c=source.charAt(i);
            if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;continue;}
            if(c=='"')quoted=true;
            else if(c=='{'||c=='['){if(++depth>8)throw invalid("APK catalog JSON nesting exceeded");}
            else if(c=='}'||c==']'){if(--depth<0)throw invalid("APK catalog JSON nesting is invalid");}
        }
        if(quoted||depth!=0)throw invalid("APK catalog JSON is incomplete");
    }
    private static String slug(String value)throws IOException {
        if(value==null||!value.matches("[a-z0-9][a-z0-9-]{0,63}"))throw invalid("Role identity must be a bounded lowercase slug");
        return value;
    }
    private static String digest(String value)throws IOException {
        if(!value.matches("[0-9a-f]{64}"))throw invalid("Role SHA-256 must use 64 lowercase hexadecimal characters");return value;
    }
    private static String text(JSONObject json,String name,int maximum)throws Exception {
        Object raw=json.get(name);
        if(!(raw instanceof String))throw invalid("Catalog field "+name+" must be text");
        String value=(String)raw;
        if(value.trim().isEmpty()||value.length()>maximum)throw invalid("Catalog field "+name+" is outside its text bound");
        for(int i=0;i<value.length();i++)if(value.charAt(i)<32||value.charAt(i)==127)throw invalid("Catalog text contains control characters");
        return value;
    }
    private static long integer(JSONObject json,String name,long minimum,long maximum)throws Exception {
        Object raw=json.get(name);if(!(raw instanceof Number))throw invalid("Catalog field "+name+" must be numeric");
        double value=((Number)raw).doubleValue();
        if(!Double.isFinite(value)||value!=Math.rint(value)||value<minimum||value>maximum)
            throw invalid("Catalog field "+name+" is outside its integer resource bound");
        return (long)value;
    }
    private static IOException invalid(String message){return new IOException(message);}
}
