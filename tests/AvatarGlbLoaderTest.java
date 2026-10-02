package com.mirror.bench;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real GLB byte fixtures. Runs with real org.json on JVM or from an Android test caller. */
public final class AvatarGlbLoaderTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        run();
        for(String path:args)try(java.security.DigestInputStream input=new java.security.DigestInputStream(
                java.nio.file.Files.newInputStream(java.nio.file.Path.of(path)),java.security.MessageDigest.getInstance("SHA-256"))){
            AvatarAsset a=AvatarGlbLoader.load(input);
            System.out.println("Asset accepted: "+path+"; meshes="+a.meshes().size()+"; nodes="+a.nodes().size()
                    +"; vertices="+a.vertexCount()+"; triangles="+a.triangleCount()+"; decodedBytes="+a.decodedBytes()
                    +"; sha256="+java.util.HexFormat.of().formatHex(input.getMessageDigest().digest()));
            for(AvatarAsset.Mesh m:a.meshes())System.out.println("  mesh="+m.name()+" primitives="+m.primitives().size()+" targets="+m.targetCount()+" named="+m.targetNames().size());
        }
    }
    public static void run() throws Exception {
        Fixture f = new Fixture();
        AvatarAsset a = AvatarGlbLoader.load(f.bytes());
        check(a.meshes().size()==1 && a.nodes().size()==2, "mesh/node count");
        AvatarAsset.Mesh m=a.meshes().get(0);
        AvatarAsset.Primitive p=m.primitives().get(0);
        close(p.positions().get(3), 1, "stride + offset position");
        close(p.normals().get(2), 1, "interleaved normal");
        close(p.morphs().get(0).positions().get(7), .5f, "morph position");
        close(p.morphs().get(0).normals().get(8), -.25f, "morph normal");
        check(m.targetIndex("eyeBlinkLeft")==0 && m.targetIndex("missing")==-1, "name lookup");
        close(a.nodes().get(1).worldMatrix().get(12), 2, "parent translation");
        close(a.nodes().get(1).worldMatrix().get(1), 1, "child rotation");
        close(a.materials().get(0).baseColor().get(0), .2f, "base color");
        check(p.indices().get(2)==2 && a.vertexCount()==3 && a.triangleCount()==1, "geometry counts");
        try { p.positions().put(0, 123); throw new AssertionError("mutable buffer"); }
        catch(ReadOnlyBufferException expected) { assertions++; }
        try { m.primitives().clear(); throw new AssertionError("mutable list"); }
        catch(UnsupportedOperationException expected) { assertions++; }
        p.positions().position(4); check(p.positions().position()==0, "independent buffer cursors");
        byte[] bytes=f.bytes(); AvatarAsset copied=AvatarGlbLoader.load(bytes);
        java.util.Arrays.fill(bytes,(byte)0); close(copied.meshes().get(0).primitives().get(0).positions().get(3),1,"no input alias");
        check(AvatarGlbLoader.load(new ByteArrayInputStream(f.bytes())).vertexCount()==3,"stream entry");

        Fixture sparse=new Fixture();
        JSONObject sparseAccessor=sparse.json.getJSONArray("accessors").getJSONObject(2);
        sparseAccessor.remove("bufferView");
        sparseAccessor.put("sparse",new JSONObject("{\"count\":1,\"indices\":{\"bufferView\":4,\"componentType\":5121},\"values\":{\"bufferView\":5}}"));
        p=AvatarGlbLoader.load(sparse.bytes()).meshes().get(0).primitives().get(0);
        close(p.morphs().get(0).positions().get(7),.5f,"sparse zero base");
        close(p.morphs().get(0).positions().get(0),0,"sparse untouched vertex");
        Fixture unindexed=new Fixture(); unindexed.primitive().remove("indices");
        check(AvatarGlbLoader.load(unindexed.bytes()).meshes().get(0).primitives().get(0).indices().get(2)==2,"indexless triangle");
        Fixture matrix=new Fixture(); JSONObject child=matrix.json.getJSONArray("nodes").getJSONObject(1);
        child.remove("rotation"); child.put("matrix", new JSONArray("[1,0,0,0,0,2,0,0,0,0,1,0,0,3,0,1]"));
        close(AvatarGlbLoader.load(matrix.bytes()).nodes().get(1).worldMatrix().get(13),3,"matrix nonuniform positive scale");
        Fixture unnamed=new Fixture(); unnamed.mesh().remove("extras");
        check(AvatarGlbLoader.load(unnamed.bytes()).meshes().get(0).targetNames().isEmpty(),"unnamed targets retained by index");
        Fixture packed=new Fixture();packed.json.getJSONArray("bufferViews").getJSONObject(0).remove("byteStride");
        packed.json.getJSONArray("accessors").getJSONObject(1).put("bufferView",1).remove("byteOffset");
        for(int i=0;i<3;i++){packed.bin.putFloat(4+i*12,i==1?1:0);packed.bin.putFloat(8+i*12,i==2?1:0);packed.bin.putFloat(12+i*12,0);}
        close(AvatarGlbLoader.load(packed.bytes()).meshes().get(0).primitives().get(0).positions().get(3),1,"packed position");
        Fixture byteIndices=new Fixture();byteIndices.json.getJSONArray("accessors").getJSONObject(4).put("componentType",5121);
        byteIndices.bin.put(148,(byte)0).put(149,(byte)1).put(150,(byte)2);
        check(AvatarGlbLoader.load(byteIndices.bytes()).meshes().get(0).primitives().get(0).indices().get(2)==2,"unsigned byte indices");
        Fixture intIndices=new Fixture();intIndices.json.getJSONArray("accessors").getJSONObject(4).put("componentType",5125);
        intIndices.json.getJSONArray("bufferViews").getJSONObject(3).put("byteLength",12);
        intIndices.bin.putInt(148,0).putInt(152,1).putInt(156,2);
        check(AvatarGlbLoader.load(intIndices.bytes()).meshes().get(0).primitives().get(0).indices().get(2)==2,"unsigned int indices");
        intIndices.bin.putInt(156,0x80000000);rejects("index",()->AvatarGlbLoader.load(intIndices.bytes()));
        Fixture colors=new Fixture();colors.primitive().getJSONObject("attributes").put("COLOR_0",0);
        close(AvatarGlbLoader.load(colors.bytes()).meshes().get(0).primitives().get(0).colors().get(7),1,"VEC3 color implicit alpha");
        Fixture normalizedColor=new Fixture();normalizedColor.json.getJSONArray("accessors").put(new JSONObject("{\"bufferView\":5,\"count\":3,\"type\":\"VEC4\",\"componentType\":5121,\"normalized\":true}"));
        normalizedColor.primitive().getJSONObject("attributes").put("COLOR_0",5);normalizedColor.bin.put(156,(byte)255).put(157,(byte)128);
        close(AvatarGlbLoader.load(normalizedColor.bytes()).meshes().get(0).primitives().get(0).colors().get(1),128f/255,"normalized byte color");
        normalizedColor.json.getJSONArray("bufferViews").getJSONObject(5).put("byteOffset",155);
        rejects("alignment",()->AvatarGlbLoader.load(normalizedColor.bytes()));
        Fixture unlit=new Fixture();unlit.json.getJSONArray("materials").getJSONObject(0).put("extensions",new JSONObject("{\"KHR_materials_unlit\":{}}"));
        unlit.json.put("extensionsUsed",new JSONArray("[\"KHR_materials_unlit\"]"));
        check(AvatarGlbLoader.load(unlit.bytes()).materials().get(0).unlit(),"supported unlit extension");
        Fixture sparseOverride=new Fixture();sparseOverride.json.getJSONArray("accessors").getJSONObject(2).put("sparse",sparseAccessor.getJSONObject("sparse"));
        sparseOverride.bin.putFloat(76,.25f);sparseOverride.bin.putFloat(76+7*4,.1f);
        p=AvatarGlbLoader.load(sparseOverride.bytes()).meshes().get(0).primitives().get(0);
        close(p.morphs().get(0).positions().get(0),.25f,"sparse preserves base");
        close(p.morphs().get(0).positions().get(7),.5f,"sparse overrides base");

        bad("header", b -> b[0]=0);
        bad("version", b -> ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(4,1));
        bad("length", b -> ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(8,Integer.MAX_VALUE));
        bad("chunk", b -> ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(12,-4));
        rejects("budget",()->AvatarGlbLoader.load(f.bytes(),16));
        badJson("stride",j->j.getJSONArray("bufferViews").getJSONObject(0).put("byteStride",8));
        badJson("stride",j->j.getJSONArray("bufferViews").getJSONObject(0).put("byteStride",22));
        badJson("range",j->j.getJSONArray("bufferViews").getJSONObject(0).put("byteLength",60));
        badJson("range",j->j.getJSONArray("accessors").getJSONObject(0).put("byteOffset",2147483647L));
        badJson("finite",j->j.getJSONArray("nodes").getJSONObject(0).put("translation",new JSONArray("[1e100,0,0]")));
        badJson("cycle",j->j.getJSONArray("nodes").getJSONObject(1).put("children",new JSONArray("[0]")));
        badJson("parent",j->j.getJSONArray("nodes").getJSONObject(0).put("children",new JSONArray("[1,1]")));
        badJson("matrix",j->j.getJSONArray("nodes").getJSONObject(1).put("matrix",new JSONArray("[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1]")));
        badJson("rotation",j->j.getJSONArray("nodes").getJSONObject(1).put("rotation",new JSONArray("[0,0,0,0]")));
        badJson("scale",j->j.getJSONArray("nodes").getJSONObject(0).put("scale",new JSONArray("[-1,1,1]")));
        badJson("targetNames",j->j.getJSONArray("meshes").getJSONObject(0).getJSONObject("extras").put("targetNames",new JSONArray("[]")));
        badJson("targets",j->{ JSONObject pr=new JSONObject(j.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives").getJSONObject(0).toString());pr.remove("targets");j.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives").put(pr); });
        badJson("count",j->j.getJSONArray("accessors").getJSONObject(1).put("count",2));
        badJson("count",j->j.getJSONArray("accessors").getJSONObject(0).remove("count"));
        badJson("byteLength",j->j.getJSONArray("bufferViews").getJSONObject(0).remove("byteLength"));
        badJson("count",j->j.getJSONArray("accessors").getJSONObject(0).put("count","3"));
        badJson("count",j->j.getJSONArray("accessors").getJSONObject(0).put("count",3.5));
        badJson("weights",j->j.getJSONArray("meshes").getJSONObject(0).put("weights",new JSONArray("[0,1]")));
        badJson("unsupported",j->j.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives").getJSONObject(0).getJSONObject("attributes").put("JOINTS_0",0));
        badJson("skin",j->j.put("skins",new JSONArray("[{}]")));
        badJson("animation",j->j.put("animations",new JSONArray("[{}]")));
        badJson("URI",j->j.getJSONArray("buffers").getJSONObject(0).put("uri","outside.bin"));
        badJson("extension",j->j.put("extensionsRequired",new JSONArray("[\"KHR_draco_mesh_compression\"]")));
        badJson("texture",j->j.getJSONArray("materials").getJSONObject(0).getJSONObject("pbrMetallicRoughness").put("baseColorTexture",new JSONObject("{\"index\":0}")));
        badJson("TRIANGLES",j->j.getJSONArray("meshes").getJSONObject(0).getJSONArray("primitives").getJSONObject(0).put("mode",5));
        Fixture nonfinite=new Fixture(); nonfinite.bin.putFloat(4,Float.NaN); rejects("finite",()->AvatarGlbLoader.load(nonfinite.bytes()));
        Fixture index=new Fixture(); index.bin.putShort(148,(short)3); rejects("index",()->AvatarGlbLoader.load(index.bytes()));
        Fixture sparseOob=new Fixture(); sparseOob.json=sparse.json; sparseOob.bin.put(154,(byte)3); rejects("sparse",()->AvatarGlbLoader.load(sparseOob.bytes()));
        Fixture sparseStride=new Fixture();sparseStride.json=new JSONObject(sparse.json.toString());sparseStride.json.getJSONArray("bufferViews").getJSONObject(5).put("byteStride",12);
        rejects("sparse",()->AvatarGlbLoader.load(sparseStride.bytes()));
        Fixture sparseDuplicate=new Fixture();sparseDuplicate.json=new JSONObject(sparse.json.toString());
        JSONObject sp=sparseDuplicate.json.getJSONArray("accessors").getJSONObject(2).getJSONObject("sparse");sp.put("count",2);sp.getJSONObject("values").put("bufferView",1);
        sparseDuplicate.json.getJSONArray("bufferViews").getJSONObject(4).put("byteLength",2);sparseDuplicate.bin.put(155,(byte)2);
        rejects("sparse",()->AvatarGlbLoader.load(sparseDuplicate.bytes()));
        Fixture morphOverflow=new Fixture();morphOverflow.mesh().put("weights",new JSONArray("[2]"));morphOverflow.bin.putFloat(76,Float.MAX_VALUE);
        rejects("morph",()->AvatarGlbLoader.load(morphOverflow.bytes()));
        Fixture duplicateTarget=new Fixture();duplicateTarget.primitive().getJSONArray("targets").put(duplicateTarget.primitive().getJSONArray("targets").getJSONObject(0));
        duplicateTarget.mesh().getJSONObject("extras").getJSONArray("targetNames").put("eyeBlinkLeft");rejects("duplicate",()->AvatarGlbLoader.load(duplicateTarget.bytes()));
        Fixture instances=new Fixture();JSONArray nodes=instances.json.getJSONArray("nodes");JSONArray kids=nodes.getJSONObject(0).getJSONArray("children");
        for(int i=2;i<=9;i++){nodes.put(new JSONObject("{\"mesh\":0}"));kids.put(i);}rejects("budget",()->AvatarGlbLoader.load(instances.bytes()));
        byte[] original=f.bytes();java.util.Random random=new java.util.Random(735);
        for(int i=0;i<300;i++){
            byte[] mutated=original.clone();int at=random.nextInt(mutated.length);mutated[at]^=(byte)(1+random.nextInt(255));
            try{AvatarGlbLoader.load(mutated);assertions++;}catch(AvatarGlbLoader.FormatException expected){assertions++;}
            // Other unchecked exceptions are test failures, not accepted malformed-file diagnostics.
        }
        System.out.println("AvatarGlbLoaderTest: "+assertions+" assertions passed");
    }
    interface Mutation { void edit(JSONObject object) throws Exception; }
    interface BytesMutation { void edit(byte[] bytes) throws Exception; }
    interface Attempt { void run() throws Exception; }
    private static void badJson(String word,Mutation mutation)throws Exception {Fixture f=new Fixture();mutation.edit(f.json);rejects(word,()->AvatarGlbLoader.load(f.bytes()));}
    private static void bad(String word,BytesMutation mutation)throws Exception {byte[] b=new Fixture().bytes();mutation.edit(b);rejects(word,()->AvatarGlbLoader.load(b));}
    private static void rejects(String word,Attempt body)throws Exception {
        try {body.run();throw new AssertionError("Expected rejection: "+word);}
        catch(AvatarGlbLoader.FormatException error){check(error.getMessage().toLowerCase().contains(word.toLowerCase()),"diagnostic "+word+": "+error.getMessage());}
    }
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static void close(float actual,float expected,String message){check(Math.abs(actual-expected)<1e-5,message+": "+actual);}
    static final class Fixture {
        JSONObject json;
        final ByteBuffer bin=ByteBuffer.allocate(168).order(ByteOrder.LITTLE_ENDIAN);
        Fixture()throws Exception {
            json=new JSONObject("""
              {"asset":{"version":"2.0"},"buffers":[{"byteLength":168}],
               "bufferViews":[{"buffer":0,"byteOffset":4,"byteLength":72,"byteStride":24},
                 {"buffer":0,"byteOffset":76,"byteLength":36},{"buffer":0,"byteOffset":112,"byteLength":36},
                 {"buffer":0,"byteOffset":148,"byteLength":6},{"buffer":0,"byteOffset":154,"byteLength":1},
                 {"buffer":0,"byteOffset":156,"byteLength":12}],
               "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
                 {"bufferView":0,"byteOffset":12,"componentType":5126,"count":3,"type":"VEC3"},
                 {"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"},
                 {"bufferView":2,"componentType":5126,"count":3,"type":"VEC3"},
                 {"bufferView":3,"componentType":5123,"count":3,"type":"SCALAR"}],
               "materials":[{"pbrMetallicRoughness":{"baseColorFactor":[0.2,0.4,0.6,1],"metallicFactor":0,"roughnessFactor":0.8}}],
               "meshes":[{"name":"Face","extras":{"targetNames":["eyeBlinkLeft"]},"primitives":[{
                 "attributes":{"POSITION":0,"NORMAL":1},"indices":4,"material":0,"targets":[{"POSITION":2,"NORMAL":3}]}]}],
               "nodes":[{"name":"Root","translation":[2,0,0],"children":[1]},
                 {"name":"Head","mesh":0,"rotation":[0,0,0.7071067811865475,0.7071067811865475]}],
               "scenes":[{"nodes":[0]}],"scene":0}
            """);
            for(int i=0;i<3;i++){bin.putFloat(4+i*24,i==1?1:0);bin.putFloat(8+i*24,i==2?1:0);bin.putFloat(24+i*24,1);}
            bin.putFloat(76+7*4,.5f);bin.putFloat(112+8*4,-.25f);
            bin.putShort(148,(short)0);bin.putShort(150,(short)1);bin.putShort(152,(short)2);
            bin.put(154,(byte)2);
            bin.putFloat(160,.5f);
        }
        JSONObject mesh()throws Exception{return json.getJSONArray("meshes").getJSONObject(0);}
        JSONObject primitive()throws Exception{return mesh().getJSONArray("primitives").getJSONObject(0);}
        byte[] bytes(){
            byte[] j=json.toString().getBytes(StandardCharsets.UTF_8);int padded=(j.length+3)&~3;
            ByteBuffer out=ByteBuffer.allocate(12+8+padded+8+bin.capacity()).order(ByteOrder.LITTLE_ENDIAN);
            out.putInt(0x46546c67).putInt(2).putInt(out.capacity()).putInt(padded).putInt(0x4e4f534a).put(j);
            while(out.position()<20+padded)out.put((byte)' ');
            out.putInt(bin.capacity()).putInt(0x004e4942).put(bin.array());return out.array();
        }
    }
}
