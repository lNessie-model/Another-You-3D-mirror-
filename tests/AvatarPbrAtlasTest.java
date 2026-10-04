package com.mirror.bench;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real embedded maps; rejects undeclared profiles, mismatched map indices and corrupt data. */
public final class AvatarPbrAtlasTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        Fixture f=new Fixture();AvatarAsset a=AvatarGlbLoader.load(f.bytes());
        check(a.vertexCount()==3,"geometry preserved");
        check(a.decodedBytes()>=AvatarGlbLoader.load(new AvatarAlbedoAtlasTest.Fixture().bytes()).decodedBytes()+2*(f.base.png.length+8),"all three maps charged");
        check(a.normalMap().width()==2&&a.ormMap().height()==1,"map dimensions validated");
        check(a.materials().get(0).pbrMaps()&&a.materials().get(0).normalScale()==.65f&&a.materials().get(0).occlusionStrength()==.5f,"material controls retained");
        byte[] owned=f.bytes();AvatarAsset ownedAsset=AvatarGlbLoader.load(owned);Arrays.fill(owned,(byte)0);
        check(Arrays.equals(ownedAsset.normalMap().openStream().readAllBytes(),f.normal),"normal owns encoded bytes");
        check(Arrays.equals(ownedAsset.ormMap().openStream().readAllBytes(),f.orm),"ORM owns encoded bytes");
        bad("profile",x->x.base.json.getJSONObject("extras").put("mirrorPbrAtlas",2));
        bad("exactly three",x->x.base.json.getJSONArray("images").remove(2));
        bad("index",x->x.material().getJSONObject("normalTexture").put("index",0));
        bad("paired",x->x.material().getJSONObject("pbrMetallicRoughness").remove("metallicRoughnessTexture"));
        bad("texCoord",x->x.material().getJSONObject("normalTexture").put("texCoord",1));
        bad("scale",x->x.material().getJSONObject("normalTexture").put("scale",5));
        bad("CRC",x->x.normal[29]^=1);
        bad("URI",x->x.base.json.getJSONArray("images").getJSONObject(2).put("uri","https://example.invalid/map.png"));
        bad("unlit",x->x.material().put("extensions",new JSONObject("{\"KHR_materials_unlit\":{}}")));
        System.out.println("AvatarPbrAtlasTest: "+checks+" checks passed");
    }
    interface Edit{void apply(Fixture f)throws Exception;}
    private static void bad(String word,Edit edit)throws Exception{Fixture f=new Fixture();edit.apply(f);try{AvatarGlbLoader.load(f.bytes());throw new AssertionError("expected "+word);}catch(AvatarGlbLoader.FormatException e){check(e.getMessage().toLowerCase().contains(word.toLowerCase()),e.getMessage());}}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    static final class Fixture{
        final AvatarAlbedoAtlasTest.Fixture base=new AvatarAlbedoAtlasTest.Fixture();
        byte[] normal=base.png.clone(),orm=base.png.clone();
        Fixture()throws Exception{
            base.json.getJSONObject("extras").put("mirrorPbrAtlas",1);
            base.json.put("images",new JSONArray("[{\"bufferView\":7,\"mimeType\":\"image/png\"},{\"bufferView\":8,\"mimeType\":\"image/png\"},{\"bufferView\":9,\"mimeType\":\"image/png\"}]"));
            base.json.put("textures",new JSONArray("[{\"source\":0},{\"source\":1},{\"source\":2}]"));
            material().put("normalTexture",new JSONObject("{\"index\":1,\"scale\":0.65}"));
            material().getJSONObject("pbrMetallicRoughness").put("metallicRoughnessTexture",new JSONObject("{\"index\":2}"));
            material().put("occlusionTexture",new JSONObject("{\"index\":2,\"strength\":0.5}"));
        }
        JSONObject material()throws Exception{return base.json.getJSONArray("materials").getJSONObject(0);}
        byte[] bytes()throws Exception{
            byte[] original=base.bytes();int jsonSize=ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN).getInt(12);
            byte[] binary=Arrays.copyOfRange(original,28+jsonSize,original.length);
            int logical=192+base.png.length;
            for(int i=1;i<=2;i++){
                byte[] png=i==1?normal:orm;int offset=(logical+3)&~3;
                binary=Arrays.copyOf(binary,offset+png.length);System.arraycopy(png,0,binary,offset,png.length);
                base.json.getJSONArray("bufferViews").put(7+i,new JSONObject().put("buffer",0).put("byteOffset",offset).put("byteLength",png.length));logical=binary.length;
            }
            base.json.getJSONArray("buffers").getJSONObject(0).put("byteLength",logical);
            byte[] text=base.json.toString().getBytes(StandardCharsets.UTF_8);int jp=(text.length+3)&~3,bp=(logical+3)&~3;
            ByteBuffer b=ByteBuffer.allocate(28+jp+bp).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(0x46546c67).putInt(2).putInt(b.capacity()).putInt(jp).putInt(0x4e4f534a).put(text);
            while(b.position()<20+jp)b.put((byte)' ');
            b.putInt(bp).putInt(0x004e4942).put(binary);return b.array();
        }
    }
}
