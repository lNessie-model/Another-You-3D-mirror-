package com.mirror.bench;

import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Actual Store/ZIP/GLB/rig/hash tests; no wall-time assertion substitutes for correctness. */
public final class AvatarCacheTest {
    static int checks; static Path suite; static byte[] realZip;
    static final String ZERO="0".repeat(64), ONE="1".repeat(64);
    public static void main(String[] args)throws Exception {
        suite=Path.of(args[0]).resolve(UUID.randomUUID().toString());Files.createDirectories(suite);
        realZip=Files.readAllBytes(Path.of(args[1]));
        repeatActualAsset(); hashChecksBeforeHit(); changedValidContent(); boundedImports(); decodedByteBound(); parseReuseAndIdentity(); failedParseNeverCached();
        System.out.println("AvatarCacheTest "+checks+" checks GREEN; evidence="+suite);
    }
    static AvatarPackageStore store(String name)throws Exception{return AvatarPackageStore.open(suite.resolve(name).resolve("avatars").toFile(),30);}
    static AvatarPackageStore.LoadedPackage add(AvatarPackageStore s,byte[] zip)throws Exception{return s.importZip(new ByteArrayInputStream(zip));}
    static void repeatActualAsset()throws Exception {
        var s=store("actual-repeat");var imported=add(s,realZip);s.activate(imported.ticket);var first=s.readCurrent();var second=s.readCurrent();
        check(imported.asset==first.asset&&first.asset==second.asset,"unchanged fully validated actual GLB reuses immutable asset");
        check(first.asset.vertexCount()==15085&&first.asset.triangleCount()==29482,"real full model geometry retained");
        check(first.rig!=second.rig&&first.cleanupWarnings!=second.cleanupWarnings,"mutable owners never cached");
        float[] values=new float[52];values[25]=1;first.rig.update(values,new float[]{.2f,.1f,0});
        var third=s.readCurrent();float[] actual=new float[third.asset.meshes().get(0).targetCount()];third.rig.copyMeshWeights(0,actual);
        check(Arrays.equals(actual,new float[actual.length]),"a caller's animated rig cannot contaminate later neutral rig");
        check(third.rig.asset()==third.asset&&third.framing==first.framing,"rig belongs to immutable cached source and framing");
        try{third.asset.meshes().get(0).primitives().get(0).positions().put(0,22);throw new AssertionError("mutable asset");}catch(ReadOnlyBufferException expected){checks++;}
        try{first.cleanupWarnings.add("caller-only");throw new AssertionError("mutable warnings");}catch(UnsupportedOperationException expected){checks++;}
        check(third.cleanupWarnings.isEmpty(),"warnings remain immutable and empty");
        var reopened=AvatarPackageStore.open(s.directory(),30).readCurrent();check(reopened.asset!=third.asset,"cache has Store-instance lifetime, never process-global");
    }
    static void hashChecksBeforeHit()throws Exception {
        var s=store("tamper");var item=add(s,small("A"));s.activate(item.ticket);s.readCurrent();
        Path p=packagePath(s,item);String[] names={"character.glb","avatar.json","validation.json"};
        for(String name:names){Path file=p.resolve(name);byte[] old=Files.readAllBytes(file);FileTime stamp=Files.getLastModifiedTime(file);byte[] changed=old.clone();changed[0]^=1;
            Files.write(file,changed);Files.setLastModifiedTime(file,stamp);rejects(s::readCurrent,"same-length/time corrupt "+name);
            Files.write(file,old);check(s.readCurrent().ticket.bundleSha256.equals(item.ticket.bundleSha256),"restored bytes revalidate "+name);
        }
        Path manifest=p.resolve("avatar.json");byte[] oldManifest=Files.readAllBytes(manifest);JSONObject m=new JSONObject(new String(oldManifest,StandardCharsets.UTF_8));m.put("displayName","B");Files.writeString(manifest,m.toString());
        rejects(s::readCurrent,"valid but changed manifest cannot hit old bundle");Files.write(manifest,oldManifest);
        Path extra=p.resolve("LICENSE.txt");Files.writeString(extra,"new unhashed file");rejects(s::readCurrent,"new allowed file changes complete bundle");Files.delete(extra);
        Files.writeString(p.resolve("unexpected.bin"),"bad");rejects(s::readCurrent,"unknown file rejected before cache");Files.delete(p.resolve("unexpected.bin"));
        Path report=p.resolve("validation.json");byte[] oldReport=Files.readAllBytes(report);JSONObject r=new JSONObject(new String(oldReport,StandardCharsets.UTF_8));r.put("bundle_sha256",ZERO);Files.writeString(report,r.toString());
        rejects(s::readCurrent,"report exact bundle still checked on hit");r.put("bundle_sha256",item.ticket.bundleSha256).put("package_id",UUID.randomUUID().toString());Files.writeString(report,r.toString());
        rejects(s::readCurrent,"report exact package still checked on hit");Files.write(report,oldReport);
        Files.delete(manifest);rejects(s::readCurrent,"missing required file rejected despite cached model");Files.write(manifest,oldManifest);
        check(s.readCurrent().ticket.packageId.equals(item.ticket.packageId),"all recovered input restored without changing pointer");
    }
    static void changedValidContent()throws Exception {
        var s=store("valid-change");var a=add(s,small("A"));s.activate(a.ticket);var before=s.readCurrent();Path folder=packagePath(s,a);
        byte[] model=Files.readAllBytes(folder.resolve("character.glb"));ByteBuffer b=ByteBuffer.wrap(model).order(ByteOrder.LITTLE_ENDIAN);int json=b.getInt(12);b.putFloat(20+json+8,0.125f);
        replaceContent(folder,model,"changed valid manifest");var changed=s.readCurrent();
        check(changed.asset!=before.asset,"changed model with consistent new metadata forces fresh parse");
        check(changed.asset.meshes().get(0).primitives().get(0).positions().get(0)==.125f,"new parsed geometry is observed");
        check(changed.rig.displayName().equals("changed valid manifest"),"new manifest is used");rejects(()->s.activate(before.ticket),"old full ticket never benefits from cache");
        check(s.readCurrent().asset==changed.asset,"new complete content can subsequently be reused");
    }
    static void boundedImports()throws Exception {
        var s=store("bounded");var a=add(s,small("A"));s.activate(a.ticket);var b=add(s,small("B"));s.activate(b.ticket);
        var old=add(s,small("old"));s.readCurrent();s.readPrevious();s.readCandidate();
        for(int i=0;i<12;i++){
            var next=add(s,small("next-"+i));check(cache(s).size()<=3,"entry count remains bounded after import "+i);
            var catalog=s.readCatalog();Set<String> allowed=new HashSet<>(Arrays.asList(catalog.current.packageId,catalog.previous.packageId,catalog.candidate.packageId));
            check(allowed.containsAll(cache(s).keySet()),"normal cleanup purges cache for dropped slots "+i);
            check(s.readCandidate().asset==next.asset,"latest candidate may reuse its verified import asset");
        }
        rejects(()->s.load(old.ticket.packageId),"evicted and unretained id is still inaccessible");
        var active=s.readCurrent();s.activateBuiltin();s.recover();check(!cache(s).containsKey(a.ticket.packageId),"builtin switch cleanup purges obsolete previous");
        check(s.readPrevious().asset==active.asset,"still-retained previous remains coherent across slot changes");
        var c=s.readCandidate();s.discard(c.ticket);s.recover();check(!cache(s).containsKey(c.ticket.packageId),"discard and cleanup release cache reference");
    }
    static void decodedByteBound()throws Exception {
        var s=store("decoded-budget");byte[] large=largeArchive();var a=add(s,large);s.activate(a.ticket);var b=add(s,large);s.activate(b.ticket);
        check(a.asset.decodedBytes()>16L*1024*1024&&b.asset.decodedBytes()>16L*1024*1024,"real large fixtures exceed half cache budget");
        check(cacheBytes(s)<=32L*1024*1024,"decoded cache bytes remain bounded independently of entry count");
        check(!cache(s).containsKey(a.ticket.packageId),"older retained large asset is evicted, not rejected");
        var reloaded=s.readPrevious();check(reloaded.asset!=a.asset&&reloaded.ticket.modelSha256.equals(a.ticket.modelSha256),"eviction reparses valid retained package with same ticket");
        check(cacheBytes(s)<=32L*1024*1024&&cache(s).size()<=3,"reload also respects both bounds");
    }
    static void parseReuseAndIdentity()throws Exception {
        final int[] parses={0};var s=AvatarPackageStore.open(suite.resolve("parse-count").toFile(),30,p->{if(p.equals("before-avatar-parse"))parses[0]++;});
        var a=add(s,small("same content"));s.activate(a.ticket);var b=add(s,small("same content"));s.activate(b.ticket);var c=add(s,small("same content"));
        check(parses[0]==3,"imports parse exactly once each");
        check(a.ticket.modelSha256.equals(b.ticket.modelSha256)&&a.asset!=b.asset,"identical model bytes under distinct package IDs do not alias ticket owners");
        for(int i=0;i<3;i++){s.readCurrent();s.readPrevious();s.readCandidate();}
        check(parses[0]==3,"repeated three-slot reads use parsed cache only after hash checks");
        rejects(()->s.load(a.ticket.packageId+"/../"+b.ticket.packageId),"path-like cache alias remains invalid");
        rejects(()->s.load("./"+a.ticket.packageId),"dot cache alias remains invalid");
        check(s.readPrevious().ticket.packageId.equals(a.ticket.packageId),"previous retains exact package identity");
    }
    static void failedParseNeverCached()throws Exception {
        var s=store("failed-parse");var a=add(s,small("A"));s.activate(a.ticket);Path p=packagePath(s,a);byte[] oldModel=Files.readAllBytes(p.resolve("character.glb")),oldManifest=Files.readAllBytes(p.resolve("avatar.json")),oldReport=Files.readAllBytes(p.resolve("validation.json"));
        byte[] invalid=oldModel.clone();invalid[0]=0;replaceContent(p,invalid,"hash-consistent bad GLB");rejects(s::readCurrent,"matching disk SHA cannot bypass GLB semantic validation on changed bytes");
        check(!cache(s).containsKey(a.ticket.packageId),"failed changed validation leaves no stale cache entry");
        Files.write(p.resolve("character.glb"),oldModel);Files.write(p.resolve("avatar.json"),oldManifest);Files.write(p.resolve("validation.json"),oldReport);
        check(s.readCurrent().asset!=a.asset,"restored content revalidates after failed attempt");
    }
    static Path packagePath(AvatarPackageStore s,AvatarPackageStore.LoadedPackage p){return s.directory().toPath().resolve("packages").resolve(p.ticket.packageId);}
    static void replaceContent(Path folder,byte[] model,String name)throws Exception {
        Files.write(folder.resolve("character.glb"),model);JSONObject manifest=new JSONObject(Files.readString(folder.resolve("avatar.json")));manifest.put("modelSha256",sha(model)).put("displayName",name);Files.writeString(folder.resolve("avatar.json"),manifest.toString());
        TreeMap<String,String> hashes=new TreeMap<>();try(var entries=Files.list(folder)){for(Path path:entries.toList())if(!path.getFileName().toString().equals("validation.json"))hashes.put(path.getFileName().toString(),sha(Files.readAllBytes(path)));}
        var digest=MessageDigest.getInstance("SHA-256");for(var e:hashes.entrySet())digest.update((e.getKey()+"\0"+e.getValue()+"\n").getBytes(StandardCharsets.UTF_8));
        JSONObject report=new JSONObject(Files.readString(folder.resolve("validation.json")));report.put("bundle_sha256",HexFormat.of().formatHex(digest.digest()));Files.writeString(folder.resolve("validation.json"),report.toString());
    }
    static byte[] small(String name)throws Exception {Method m=AvatarPackageStoreTest.class.getDeclaredMethod("archive",String.class);m.setAccessible(true);return (byte[])m.invoke(null,name);}
    @SuppressWarnings("unchecked") static Map<String,?> cache(AvatarPackageStore s)throws Exception {
        Field implementation=AvatarPackageStore.class.getDeclaredField("implementation");implementation.setAccessible(true);Object impl=implementation.get(s);
        Field cache=impl.getClass().getDeclaredField("parseCache");cache.setAccessible(true);return (Map<String,?>)cache.get(impl);
    }
    static long cacheBytes(AvatarPackageStore s)throws Exception {long total=0;for(Object v:cache(s).values()){Field f=v.getClass().getDeclaredField("asset");f.setAccessible(true);total+=((AvatarAsset)f.get(v)).decodedBytes();}return total;}
    @SuppressWarnings("unchecked") static byte[] largeArchive()throws Exception {
        Method entries=AvatarPackageStoreTest.class.getDeclaredMethod("entries",String.class);entries.setAccessible(true);var files=(LinkedHashMap<String,byte[]>)entries.invoke(null,"large-decoded");
        byte[] tiny=files.get("character.glb");ByteBuffer old=ByteBuffer.wrap(tiny).order(ByteOrder.LITTLE_ENDIAN);JSONObject gltf=new JSONObject(new String(tiny,20,old.getInt(12),StandardCharsets.UTF_8));
        int vertices=19998, block=vertices*12;ByteBuffer bin=ByteBuffer.allocate(block*3).order(ByteOrder.LITTLE_ENDIAN);
        for(int v=0;v<vertices;v++)bin.putFloat(v%3==1?1:0).putFloat(v%3==2?1:0).putFloat(0);
        for(int v=0;v<vertices;v++)bin.putFloat(0).putFloat(0).putFloat(1);
        JSONArray views=new JSONArray();for(int i=0;i<3;i++)views.put(new JSONObject().put("buffer",0).put("byteOffset",block*i).put("byteLength",block));
        JSONArray accessors=new JSONArray();for(int i=0;i<82;i++)accessors.put(new JSONObject().put("bufferView",i<2?i:2).put("componentType",5126).put("count",vertices).put("type","VEC3"));
        JSONArray targets=new JSONArray(),names=new JSONArray();String[] first={"eyeBlinkLeft","eyeBlinkRight","jawOpen"};for(int i=0;i<40;i++){targets.put(new JSONObject().put("POSITION",2+2*i).put("NORMAL",3+2*i));names.put(i<3?first[i]:"unused"+i);}
        var mesh=gltf.getJSONArray("meshes").getJSONObject(0);mesh.getJSONObject("extras").put("targetNames",names);var primitive=mesh.getJSONArray("primitives").getJSONObject(0);primitive.put("targets",targets).getJSONObject("attributes").put("NORMAL",1);
        gltf.put("accessors",accessors).put("bufferViews",views).getJSONArray("buffers").getJSONObject(0).put("byteLength",bin.capacity());
        byte[] json=gltf.toString().getBytes(StandardCharsets.UTF_8);int pad=(json.length+3)&~3;ByteBuffer model=ByteBuffer.allocate(28+pad+bin.capacity()).order(ByteOrder.LITTLE_ENDIAN);model.putInt(0x46546c67).putInt(2).putInt(model.capacity()).putInt(pad).putInt(0x4e4f534a).put(json);while(model.position()<20+pad)model.put((byte)' ');model.putInt(bin.capacity()).putInt(0x004e4942).put(bin.array());
        files.put("character.glb",model.array());JSONObject manifest=new JSONObject(new String(files.get("avatar.json"),StandardCharsets.UTF_8));manifest.put("modelSha256",sha(model.array()));files.put("avatar.json",manifest.toString().getBytes(StandardCharsets.UTF_8));
        Method zip=AvatarPackageStoreTest.class.getDeclaredMethod("zip",Map.class);zip.setAccessible(true);return(byte[])zip.invoke(null,files);
    }
    static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    interface Checked{void run()throws Exception;}static void rejects(Checked f,String why)throws Exception{try{f.run();}catch(IOException expected){checks++;return;}throw new AssertionError("Expected rejection: "+why);}static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
}
