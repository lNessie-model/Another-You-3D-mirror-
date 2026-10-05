package com.mirror.bench;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

final class BundledCatalogTestSupport {
    private BundledCatalogTestSupport(){}
    static JSONObject entry(String id) {
        String directory=id.equals("builtin-guide")?"avatars/builtin-guide":"avatars/catalog/"+id;
        return new JSONObject().put("id",id).put("displayName",id.equals("geralt")?"杰洛特":"内置向导")
            .put("directory",directory).put("modelSha256","a".repeat(64)).put("manifestSha256","b".repeat(64))
            .put("thumbnail",directory+"/thumbnail.png").put("status","GPU_VERIFIED_CANDIDATE / LIVE_CAMERA_PENDING")
            .put("vertices",19000).put("triangles",19000).put("drawCalls",7).put("decodedBytes",32L*1024*1024);
    }
    static JSONObject catalog() {
        return new JSONObject().put("schemaVersion",1).put("defaultId","geralt")
            .put("entries",new JSONArray().put(entry("geralt")).put(entry("builtin-guide")));
    }
    static final class Assets extends AssetManager {
        byte[] catalog;
        int opened,closed;
        Assets(String json){this(json.getBytes(StandardCharsets.UTF_8));}
        Assets(byte[] json){catalog=json;}
        @Override public InputStream open(String name)throws IOException {
            if(!name.equals("avatars/catalog/catalog.json"))throw new AssertionError("Catalog read model/thumbnail: "+name);
            opened++;
            return new ByteArrayInputStream(catalog){@Override public void close()throws IOException{closed++;super.close();}};
        }
    }
    static final class Preferences implements SharedPreferences {
        final Map<String,String> memory=new HashMap<>(),disk=new HashMap<>();
        int failedCommits,commits;
        @Override public String getString(String key,String fallback){return memory.getOrDefault(key,fallback);}
        @Override public boolean contains(String key){return memory.containsKey(key);}
        @Override public Editor edit(){
            Map<String,String> change=new HashMap<>(memory);
            return new Editor(){
                @Override public Editor putString(String key,String value){if(value==null)change.remove(key);else change.put(key,value);return this;}
                @Override public Editor remove(String key){change.remove(key);return this;}
                @Override public boolean commit(){
                    // Android SharedPreferences updates its memory before the disk-write result.
                    memory.clear();memory.putAll(change);commits++;
                    if(failedCommits>0){failedCommits--;return false;}
                    disk.clear();disk.putAll(change);return true;
                }
            };
        }
    }
    static final class TestContext extends Context {
        final Assets assets;
        final Map<String,Preferences> stores=new HashMap<>();
        TestContext(){assets=new Assets(catalog().toString());}
        @Override public AssetManager getAssets(){return assets;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){
            if(mode!=MODE_PRIVATE)throw new AssertionError("Selection preferences are not private");
            return stores.computeIfAbsent(name,key->new Preferences());
        }
        Preferences selection(){return (Preferences)getSharedPreferences("bundled_avatar_selection_v1",MODE_PRIVATE);}
    }
}
