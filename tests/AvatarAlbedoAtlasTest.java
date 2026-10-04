package com.mirror.bench;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises the embedded atlas boundary with actual PNG and GLB bytes, no Android mocks. */
public final class AvatarAlbedoAtlasTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        Fixture valid=new Fixture();
        AvatarAsset asset=AvatarGlbLoader.load(valid.bytes());
        check(asset.vertexCount()==3,"geometry preserved");
        check(asset.meshes().get(0).primitives().get(0).texCoords().get(2)==1,"UV preserved");
        check(asset.albedoAtlas().width()==2&&asset.albedoAtlas().height()==1,"atlas dimensions");
        check(asset.materials().get(0).textured(),"textured material flag");
        AvatarBatchLayout packed=new AvatarBatchLayout(asset,new boolean[]{true,true});
        check(packed.uvs().isReadOnly()&&packed.uvs().get(2)==1,"static batch UVs preserved read-only");
        check(new AvatarBatchLayout(AvatarGlbLoader.load(new AvatarGlbLoaderTest.Fixture().bytes()),new boolean[]{true,true}).uvs()==null,"legacy has no UV allocation");
        check(AvatarGlbLoader.load(new AvatarGlbLoaderTest.Fixture().bytes()).albedoAtlas()==null,"legacy has no atlas");
        byte[] encoded=valid.bytes();AvatarAsset owned=AvatarGlbLoader.load(encoded);Arrays.fill(encoded,(byte)0);
        check(Arrays.equals(owned.albedoAtlas().openStream().readAllBytes(),valid.png),"atlas owns bytes independently");
        owned.albedoAtlas().openStream().read();check(owned.albedoAtlas().openStream().read()==137,"independent stream cursor");
        long base=AvatarGlbLoader.load(new AvatarGlbLoaderTest.Fixture().bytes()).decodedBytes();
        check(asset.decodedBytes()>=base+24+valid.png.length+8,"encoded and RGBA texture memory charged");
        rejects("decoded",()->AvatarGlbLoader.load(valid.bytes(),base+24+valid.png.length+7));
        bad("texture",f->f.json.remove("extras"));
        bad("profile",f->f.json.getJSONObject("extras").put("mirrorAlbedoAtlas",2));
        bad("URI",f->f.json.getJSONArray("images").getJSONObject(0).put("uri","file:///ignored.png"));
        bad("exactly one",f->f.json.getJSONArray("images").put(new JSONObject("{\"bufferView\":7,\"mimeType\":\"image/png\"}")));
        bad("sampler",f->f.json.put("samplers",new JSONArray("[{}]")));
        bad("texCoord",f->f.texture().put("texCoord",1));
        bad("UV",f->f.primitive().getJSONObject("attributes").remove("TEXCOORD_0"));
        bad("texture",f->f.json.getJSONArray("textures").getJSONObject(0).put("source",1));
        bad("texture",f->f.texture().put("index",1));
        bad("extension",f->f.texture().put("extensions",new JSONObject("{\"KHR_texture_transform\":{}}")));
        bad("PNG",f->f.png[0]=0);
        bad("CRC",f->f.png[29]^=1);
        bad("RGB",f->f.png=png(2,1,6,new byte[]{0,1,2,3,4,5,6,7,8}));
        bad("dimensions",f->f.png=png(2049,1,2,new byte[]{0,1,2,3,4,5,6}));
        bad("inflate",f->f.png=png(2,1,2,new byte[]{0,1,2,3}));
        bad("filter",f->f.png=png(2,1,2,new byte[]{5,1,2,3,4,5,6}));
        bad("chunk",f->f.png=join(Arrays.copyOf(f.png,f.png.length-12),chunk("acTL",new byte[8]),chunk("IEND",new byte[0])));
        bad("trailing",f->f.png=join(f.png,new byte[4]));
        System.out.println("AvatarAlbedoAtlasTest: "+checks+" checks passed");
    }
    interface Edit {void apply(Fixture f)throws Exception;}
    interface Attempt {void run()throws Exception;}
    private static void bad(String word,Edit edit)throws Exception {Fixture f=new Fixture();edit.apply(f);rejects(word,()->AvatarGlbLoader.load(f.bytes()));}
    private static void rejects(String word,Attempt attempt)throws Exception {
        try{attempt.run();throw new AssertionError("expected "+word);}
        catch(AvatarGlbLoader.FormatException e){check(e.getMessage().toLowerCase().contains(word.toLowerCase()),e.getMessage());}
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static byte[] join(byte[]... parts)throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] p:parts)out.write(p);return out.toByteArray();}
    private static byte[] chunk(String type,byte[] data){
        byte[] name=type.getBytes(StandardCharsets.US_ASCII);CRC32 crc=new CRC32();crc.update(name);crc.update(data);
        return ByteBuffer.allocate(data.length+12).order(ByteOrder.BIG_ENDIAN).putInt(data.length).put(name).put(data).putInt((int)crc.getValue()).array();
    }
    static byte[] png(int width,int height,int type,byte[] raw)throws Exception {
        byte[] hdr=ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN).putInt(width).putInt(height).put((byte)8).put((byte)type).put(new byte[3]).array();
        Deflater deflater=new Deflater();deflater.setInput(raw);deflater.finish();byte[] encoded=new byte[raw.length+128];int n=deflater.deflate(encoded);deflater.end();
        return join(new byte[]{(byte)137,80,78,71,13,10,26,10},chunk("IHDR",hdr),chunk("IDAT",Arrays.copyOf(encoded,n)),chunk("IEND",new byte[0]));
    }
    static final class Fixture {
        final AvatarGlbLoaderTest.Fixture original=new AvatarGlbLoaderTest.Fixture();
        final JSONObject json=original.json;
        byte[] png=png(2,1,2,new byte[]{0,(byte)255,0,0,0,(byte)255,0});
        Fixture()throws Exception {
            json.put("extras",new JSONObject("{\"mirrorAlbedoAtlas\":1}"));
            json.put("images",new JSONArray("[{\"bufferView\":7,\"mimeType\":\"image/png\"}]"));
            json.put("textures",new JSONArray("[{\"source\":0}]"));
            json.getJSONArray("bufferViews").put(new JSONObject("{\"buffer\":0,\"byteOffset\":168,\"byteLength\":24}"));
            json.getJSONArray("accessors").put(new JSONObject("{\"bufferView\":6,\"componentType\":5126,\"count\":3,\"type\":\"VEC2\"}"));
            primitive().getJSONObject("attributes").put("TEXCOORD_0",5);
            json.getJSONArray("materials").getJSONObject(0).getJSONObject("pbrMetallicRoughness").put("baseColorTexture",new JSONObject("{\"index\":0,\"texCoord\":0}"));
        }
        JSONObject primitive()throws Exception{return original.primitive();}
        JSONObject texture()throws Exception{return json.getJSONArray("materials").getJSONObject(0).getJSONObject("pbrMetallicRoughness").getJSONObject("baseColorTexture");}
        byte[] bytes()throws Exception {
            int logical=192+png.length,padded=(logical+3)&~3;
            json.getJSONArray("buffers").getJSONObject(0).put("byteLength",logical);
            json.getJSONArray("bufferViews").put(7,new JSONObject().put("buffer",0).put("byteOffset",192).put("byteLength",png.length));
            byte[] text=json.toString().getBytes(StandardCharsets.UTF_8);int jp=(text.length+3)&~3;
            ByteBuffer b=ByteBuffer.allocate(12+8+jp+8+padded).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(0x46546c67).putInt(2).putInt(b.capacity()).putInt(jp).putInt(0x4e4f534a).put(text);
            while(b.position()<20+jp)b.put((byte)' ');
            b.putInt(padded).putInt(0x004e4942).put(original.bin.array());
            b.putFloat(0).putFloat(0).putFloat(1).putFloat(0).putFloat(0).putFloat(1).put(png);return b.array();
        }
    }
}
