package com.mirror.bench;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Real filesystem/ZIP/production loader tests; only Android StatFs is a host boundary stub. */
public final class AvatarPackageStoreTest {
    private static int checks;
    private static Path suite;
    public static void main(String[] args)throws Exception {
        suite=Path.of(args[0]).resolve(UUID.randomUUID().toString());Files.createDirectories(suite);
        unsupportedSdk(); storageSpaceBudget(); publicationAndRollback(); invalidArchivesPreserveCurrent(); manifestValidation(); interruptedCopy();
        injectedFailuresAndRecovery(); cancelledSelection(); boundedRetention(); lockIsNonblocking(); concurrentImportIsNonblocking(); tamperingRejected();
        if(args.length>1)actualAsset(Path.of(args[1]));
        System.out.println("AvatarPackageStoreTest: "+checks+" checks passed; test files: "+suite);
    }
    private static void unsupportedSdk()throws Exception {
        Path root=suite.resolve("unsupported-sdk");
        for(int api:new int[]{0,24,25,26,29})rejects(()->AvatarPackageStore.open(root.toFile(),api),"SDK "+api);
        check(!Files.exists(root),"unsupported SDK does not touch disk");
        check(AvatarPackageStore.supportsAndroidApi(30)&&AvatarPackageStore.supportsAndroidApi(35),"API capability");
        try(InputStream in=AvatarPackageStore.class.getResourceAsStream("AvatarPackageStore.class")){
            check(!new String(in.readAllBytes(),StandardCharsets.ISO_8859_1).contains("java/nio/file/"),"API facade constant pool has no NIO file linkage");
        }
    }
    private static void publicationAndRollback()throws Exception {
        AvatarPackageStore store=store("publication");
        check(store.readCurrent()==null,"initial builtin fallback");
        var a=store.importZip(stream(archive("A")));check(store.readCurrent()==null,"import does not select");
        var catalog=store.readCatalog();check(catalog.current==null&&catalog.previous==null&&catalog.candidate.displayName.equals("A"),"lightweight candidate catalog");
        check(sha(a.manifestJson.getBytes(StandardCharsets.UTF_8)).equals(a.ticket.manifestSha256),"validated manifest string is SHA-bound");
        check(a.asset.vertexCount()==3&&!a.rig.completeSourceCoverage(),"real loader/partial coverage");
        check(a.reportJson.contains("partial")&&!a.reportJson.contains("artwork_validated\":true"),"truthful report");
        store.activate(a.ticket);check(store.readCurrent().ticket.packageId.equals(a.ticket.packageId),"activate A");
        check(store.readCatalog().candidate==null&&store.readCatalog().current.packageId.equals(a.ticket.packageId),"catalog follows activation");
        var b=store.importZip(stream(archive("B")));store.activate(b.ticket);
        check(store.readCurrent().rig.displayName().equals("B"),"B selected");
        store.restorePrevious(store.readPrevious().ticket);check(store.readCurrent().rig.displayName().equals("A"),"previous restored");
        store.activateBuiltin();check(store.readCurrent()==null,"builtin selection");
        store.restorePrevious(store.readPrevious().ticket);check(store.readCurrent().rig.displayName().equals("A"),"builtin rollback");
        var stale=store.importZip(stream(archive("stale")));var newer=store.importZip(stream(archive("newer")));
        check(store.readCandidate().ticket.packageId.equals(newer.ticket.packageId),"candidate survives re-read");
        rejects(()->store.activate(stale.ticket),"superseded ticket");
        rejects(()->store.restorePrevious(stale.ticket),"rollback requires exact previous ticket");
        check(store.readCurrent().rig.displayName().equals("A"),"stale keeps active");
        store.discard(newer.ticket);rejects(()->store.load(newer.ticket.packageId),"discard candidate");
        rejects(()->store.discard(a.ticket),"cannot discard current");
    }
    private static void storageSpaceBudget()throws Exception {
        AvatarPackageStore store=store("storage-space");
        var active=store.importZip(stream(archive("active")));store.activate(active.ticket);
        var pending=store.importZip(stream(archive("pending")));
        byte[] data=archive("new");
        long required=74L*1024*1024;
        try {
            for(long bytes:new long[]{required-1,0,-1}){
                android.os.StatFs.availableOverride=bytes;
                rejectsBeforeReading(store,data,"available="+bytes);
                check(store.readCurrent().ticket.packageId.equals(active.ticket.packageId),"low space preserves current");
                check(store.readCandidate().ticket.packageId.equals(pending.ticket.packageId),"low space preserves candidate");
            }
            for(RuntimeException failure:new RuntimeException[]{new SecurityException("denied statvfs"),new IllegalArgumentException("bad filesystem")}){
                android.os.StatFs.failure=failure;
                final int[] reads={0};
                InputStream in=new ByteArrayInputStream(data){@Override public synchronized int read(byte[] b,int off,int len){reads[0]++;return super.read(b,off,len);}};
                try{store.importZip(in);throw new AssertionError("Expected stat failure");}
                catch(IOException expected){check(expected.getCause()==failure,"stat failure retains original cause");}
                check(reads[0]==0,"stat failure does not consume provider input");
                Path root=store.directory().toPath();
                check(!Files.exists(root.resolve("incoming.zip"))&&!Files.exists(root.resolve("staging")),"stat failure creates no temporary files");
                check(store.readCurrent().ticket.packageId.equals(active.ticket.packageId),"failed stat preserves current");
                check(store.readCandidate().ticket.packageId.equals(pending.ticket.packageId),"failed stat preserves candidate");
                android.os.StatFs.failure=null;
            }
            android.os.StatFs.availableOverride=required;
            var exact=store.importZip(stream(data));
            check(exact.rig.displayName().equals("new"),"exact 74 MiB passes space budget and real validation");
            check(Path.of(android.os.StatFs.lastPath).equals(store.directory().toPath()),"stat checks actual canonical store filesystem");
            android.os.StatFs.availableOverride=3L*1024*1024*1024;
            check(store.importZip(stream(data)).asset.vertexCount()==3,"available bytes remain 64-bit above 2 GiB");
        }finally{android.os.StatFs.reset();}
    }
    private static void rejectsBeforeReading(AvatarPackageStore store,byte[] data,String why)throws Exception {
        final int[] reads={0};
        InputStream in=new ByteArrayInputStream(data){@Override public synchronized int read(byte[] b,int off,int len){reads[0]++;return super.read(b,off,len);}};
        rejects(()->store.importZip(in),"storage check "+why);
        check(reads[0]==0,"storage rejection does not consume provider input");
        Path root=store.directory().toPath();
        check(!Files.exists(root.resolve("incoming.zip"))&&!Files.exists(root.resolve("staging")),"storage rejection creates no temporary files");
    }
    private static void invalidArchivesPreserveCurrent()throws Exception {
        AvatarPackageStore store=store("archives");var current=store.importZip(stream(archive("current")));store.activate(current.ticket);
        var candidate=store.importZip(stream(archive("pending")));
        for(String name:List.of("../avatar.json","/avatar.json","x/avatar.json","x\\avatar.json","C:avatar.json","AVATAR.JSON","notes.exe","folder/","avatar.json ","./avatar.json")) {
            var entries=entries("bad");entries.put(name,new byte[]{1});
            rejects(()->store.importZip(stream(zip(entries))),"bad entry "+name);
            check(store.readCurrent().ticket.packageId.equals(current.ticket.packageId),"active preserved "+name);
        }
        var missing=entries("missing");missing.remove("character.glb");rejects(()->store.importZip(stream(zip(missing))),"missing model");
        byte[] complete=archive("truncated");rejects(()->store.importZip(stream(Arrays.copyOf(complete,complete.length-30))),"missing central directory");
        byte[] corrupted=storedArchive(entries("crc"));corrupted[localPayload(corrupted)]^=1;
        rejects(()->store.importZip(stream(corrupted)),"CRC mismatch");
        var large=entries("large");large.put("README.md",new byte[65*1024]);rejects(()->store.importZip(stream(zip(large))),"expanded entry bound");
        // Same-length names in both central and local headers yield a valid duplicated central entry.
        var dupeEntries=entries("dup");dupeEntries.put("README.md",new byte[]{1});dupeEntries.put("README.tx",new byte[]{2});
        final byte[] duplicateFinal=replace(zip(dupeEntries),"README.tx","README.md");
        rejects(()->store.importZip(stream(duplicateFinal)),"duplicate entry");
        InputStream oversized=new InputStream(){long left=AvatarPackageStore.MAX_ZIP_BYTES+1;
            public int read(){return left-->0?0:-1;}
            public int read(byte[] b,int off,int len){if(left<=0)return -1;int n=(int)Math.min(left,len);Arrays.fill(b,off,off+n,(byte)0);left-=n;return n;}};
        rejects(()->store.importZip(oversized),"compressed input bound");
        check(!Files.exists(store.directory().toPath().resolve("incoming.zip")),"failed archive cleanup");
        check(!Files.exists(store.directory().toPath().resolve("staging")),"failed staging cleanup");
        check(store.readCandidate().ticket.packageId.equals(candidate.ticket.packageId),"bad imports preserve existing candidate too");
    }
    private static void manifestValidation()throws Exception {
        AvatarPackageStore store=store("manifest");
        for(String mode:List.of("hash","path","rig","deep","utf8","license","unsupportedTexture","trailing","singleQuoteDepth","comment","duplicateKey")) {
            var e=entries(mode);JSONObject m=new JSONObject(new String(e.get("avatar.json"),StandardCharsets.UTF_8));
            switch(mode){
                case "hash":m.put("modelSha256","0".repeat(64));break;
                case "path":m.put("model","../character.glb");break;
                case "rig":m.getJSONArray("bindings").getJSONObject(0).put("source","unknown");break;
                case "deep":m.put("extra",new JSONArray("[".repeat(60)+"0"+"]".repeat(60)));break;
                case "license":m.put("license",new JSONObject().put("name","x").put("file","../../secret"));break;
                case "unsupportedTexture":e.put("character.glb",glb(true));m.put("modelSha256",sha(e.get("character.glb")));break;
            }
            e.put("avatar.json",m.toString().getBytes(StandardCharsets.UTF_8));
            if(mode.equals("utf8"))e.put("avatar.json",new byte[]{'{','"',(byte)0xff,'"',':','1','}'});
            if(mode.equals("trailing"))e.put("avatar.json",(m+" {}").getBytes(StandardCharsets.UTF_8));
            if(mode.equals("singleQuoteDepth"))e.put("avatar.json",(m.toString().replaceFirst("}$","")+",'prefix':'\"','nested':"+"[".repeat(2000)+"0"+"]".repeat(2000)+",'suffix':'\"'}").getBytes(StandardCharsets.UTF_8));
            if(mode.equals("comment"))e.put("avatar.json",m.toString().replace("{","{/* \" */").getBytes(StandardCharsets.UTF_8));
            if(mode.equals("duplicateKey"))e.put("avatar.json",m.toString().replaceFirst("\\{","{\"model\":\"character.glb\",").getBytes(StandardCharsets.UTF_8));
            rejects(()->store.importZip(stream(zip(e))),"manifest "+mode);
        }
    }
    private static void interruptedCopy()throws Exception {
        AvatarPackageStore store=store("cancel");byte[] data=archive("cancel");
        InputStream failing=new ByteArrayInputStream(data){@Override public synchronized int read(byte[] b,int off,int len){Thread.currentThread().interrupt();return super.read(b,off,len);}};
        rejects(()->store.importZip(failing),"thread interruption");check(Thread.interrupted(),"interrupt preserved");
        check(!Files.exists(store.directory().toPath().resolve("incoming.zip")),"cancel copy cleanup");
        final boolean[] closed={false};InputStream callerOwned=new ByteArrayInputStream(data){@Override public void close(){closed[0]=true;}};
        store.importZip(callerOwned);check(!closed[0],"caller owns SAF stream");
    }
    private static void injectedFailuresAndRecovery()throws Exception {
        for(String point:List.of("before-package-move","after-package-move","before-state-move")){
            Path path=suite.resolve("fault-"+point);AvatarPackageStore healthy=AvatarPackageStore.open(path.toFile(),30);
            var active=healthy.importZip(stream(archive("old")));healthy.activate(active.ticket);
            AvatarPackageStore failing=AvatarPackageStore.open(path.toFile(),30,p->{if(p.equals(point))throw new IOException("injected "+point);});
            rejects(()->failing.importZip(stream(archive("new"))),"publication failure "+point);
            AvatarPackageStore recovered=AvatarPackageStore.open(path.toFile(),30);recovered.recover();
            check(recovered.readCurrent().rig.displayName().equals("old"),"recovery retains old "+point);
            check(packageCount(path)==1,"unpublished orphan removed "+point);
        }
        Path path=suite.resolve("activation-fault");AvatarPackageStore good=AvatarPackageStore.open(path.toFile(),30);
        var a=good.importZip(stream(archive("A")));good.activate(a.ticket);var b=good.importZip(stream(archive("B")));
        AvatarPackageStore bad=AvatarPackageStore.open(path.toFile(),30,p->{if(p.equals("before-state-move"))throw new IOException("pointer write");});
        rejects(()->bad.activate(b.ticket),"activation failure");check(good.readCurrent().rig.displayName().equals("A"),"active unaffected by pointer failure");
        // Files left by process death before publish are cleaned, while the active directory remains.
        Files.createDirectory(path.resolve("staging"));Files.write(path.resolve("staging/character.glb"),new byte[]{1});
        Files.write(path.resolve("incoming.zip"),new byte[]{1});Files.write(path.resolve("state.tmp"),new byte[]{1});
        Path orphan=path.resolve("packages").resolve(UUID.randomUUID().toString());Files.createDirectory(orphan);Files.write(orphan.resolve("character.glb"),glb(false));
        good.recover();check(!Files.exists(path.resolve("staging"))&&!Files.exists(path.resolve("incoming.zip")),"crash temps removed");
        check(!Files.exists(orphan)&&good.readCurrent().rig.displayName().equals("A"),"renamed but unreferenced crash candidate removed");
    }
    private static void boundedRetention()throws Exception {
        AvatarPackageStore store=store("retention");
        for(int i=0;i<7;i++){var p=store.importZip(stream(archive("P"+i)));if(i%2==0)store.activate(p.ticket);check(packageCount(store.directory().toPath())<=3,"three retained maximum");}
        Files.writeString(store.directory().toPath().resolve("state.json"),"{broken");
        long before=packageCount(store.directory().toPath());rejects(store::recover,"corrupt catalog fail closed");check(packageCount(store.directory().toPath())==before,"corrupt catalog deletes no packages");
    }
    private static void cancelledSelection()throws Exception {
        AvatarPackageStore store=store("selection-cancel");var a=store.importZip(stream(archive("A")));store.activate(a.ticket);
        var b=store.importZip(stream(archive("B")));
        AvatarPackageStore cancelled=AvatarPackageStore.open(store.directory(),30,p->{if(p.equals("before-state-move"))Thread.currentThread().interrupt();});
        try{rejects(()->cancelled.activate(b.ticket),"cancel immediately before selection rename");}finally{check(Thread.interrupted(),"commit cancellation retains interrupt");}
        check(store.readCurrent().rig.displayName().equals("A"),"cancelled activation keeps current");store.activate(b.ticket);
        var previous=store.readPrevious();
        try{rejects(()->cancelled.restorePrevious(previous.ticket),"cancel previous restore");}finally{Thread.interrupted();}
        check(store.readCurrent().rig.displayName().equals("B"),"cancelled rollback keeps current");
        try{rejects(cancelled::activateBuiltin,"cancel builtin selection");}finally{Thread.interrupted();}
        check(store.readCurrent().rig.displayName().equals("B"),"cancelled builtin keeps current");
    }
    private static void lockIsNonblocking()throws Exception {
        AvatarPackageStore store=store("lock");
        store.recover(); // Create the lock through the guarded public operation.
        try(FileChannel c=FileChannel.open(store.directory().toPath().resolve("store.lock"),StandardOpenOption.WRITE);FileLock held=c.lock()){
            long start=System.nanoTime();rejects(store::recover,"busy store");check(System.nanoTime()-start<1_000_000_000L,"nonblocking busy");
        }
    }
    private static void concurrentImportIsNonblocking()throws Exception {
        AvatarPackageStore store=store("concurrent");
        var entered=new java.util.concurrent.CountDownLatch(1);var resume=new java.util.concurrent.CountDownLatch(1);
        var error=new java.util.concurrent.atomic.AtomicReference<Throwable>();byte[] zip=archive("held");
        InputStream input=new ByteArrayInputStream(zip){boolean paused;
            @Override public synchronized int read(byte[] b,int off,int len){if(!paused){paused=true;entered.countDown();try{resume.await();}catch(InterruptedException e){Thread.currentThread().interrupt();return -1;}}return super.read(b,off,len);}};
        Thread worker=new Thread(()->{try{store.importZip(input);}catch(Throwable e){error.set(e);}});worker.setDaemon(true);worker.start();
        try {
            check(entered.await(5,java.util.concurrent.TimeUnit.SECONDS),"import entered guarded read");
            AvatarPackageStore second=AvatarPackageStore.open(store.directory(),30);
            long start=System.nanoTime();rejects(second::recover,"another instance busy");check(System.nanoTime()-start<1_000_000_000L,"in-process lock immediately rejects");
        }finally{resume.countDown();worker.join(5000);}
        check(!worker.isAlive()&&error.get()==null,"owning import completes");store.recover();
    }
    private static void tamperingRejected()throws Exception {
        AvatarPackageStore store=store("tamper");var a=store.importZip(stream(archive("A")));store.activate(a.ticket);
        var b=store.importZip(stream(archive("B")));Path manifest=store.directory().toPath().resolve("packages").resolve(b.ticket.packageId).resolve("avatar.json");
        Files.writeString(manifest,Files.readString(manifest).replace("\"B\"","\"C\""));
        rejects(()->store.activate(b.ticket),"ticket content changed");check(store.readCurrent().rig.displayName().equals("A"),"tamper preserves active");
        rejects(()->store.load("../../outside"),"unsafe package id");
    }
    private static void actualAsset(Path glb)throws Exception {
        var e=new LinkedHashMap<String,byte[]>();for(String name:List.of("character.glb","avatar.json","LICENSE.txt","README.md","thumbnail.png"))e.put(name,Files.readAllBytes(glb.resolveSibling(name)));
        AvatarPackageStore store=store("actual");var loaded=store.importZip(stream(zip(e)));
        check(loaded.asset.vertexCount()==15085&&loaded.asset.triangleCount()==29482,"actual exported asset geometry");
        check(loaded.rig.completeSourceCoverage(),"actual rig coverage");store.activate(loaded.ticket);
        check(store.readCurrent().ticket.modelSha256.equals(sha(e.get("character.glb"))),"actual checksum survives storage");
    }
    private static AvatarPackageStore store(String name)throws Exception{return AvatarPackageStore.open(suite.resolve(name).toFile(),30);}
    private static long packageCount(Path root)throws Exception{try(var s=Files.list(root.resolve("packages"))){return s.count();}}
    private static ByteArrayInputStream stream(byte[] b){return new ByteArrayInputStream(b);}
    private static byte[] archive(String name)throws Exception{return zip(entries(name));}
    private static LinkedHashMap<String,byte[]> entries(String name)throws Exception {
        byte[] model=glb(false);JSONObject m=new JSONObject("""
        {"schemaVersion":1,"id":"test","displayName":"test","model":"character.glb","inputSchema":"mediapipe-face-blendshapes-v1","normalPolicy":"recompute-deformed",
        "coordinates":{"up":"+Y","forward":"+Z","subjectLeft":"+X","units":"meters","rootScale":1,"headPivot":[0,0,0]},
        "rig":{"headNode":"Head","jawMode":"morph","jawAttachmentNode":"JawAttachments","jawAxis":[1,0,0],"jawOpenDegrees":22,"jawLateralMeters":0.008,"jawForwardMeters":0.007,"leftEyeNode":"EyeLeft","rightEyeNode":"EyeRight","gazeMode":"joint","gazeYawDegrees":18,"gazePitchDegrees":15},
        "bindings":[{"source":"eyeBlinkLeft","mesh":"Face","target":"eyeBlinkLeft"},{"source":"eyeBlinkRight","mesh":"Face","target":"eyeBlinkRight"},{"source":"jawOpen","mesh":"Face","target":"jawOpen"}],"ignoredSources":["_neutral"]}
        """);m.put("displayName",name).put("modelSha256",sha(model));
        var result=new LinkedHashMap<String,byte[]>();result.put("character.glb",model);result.put("avatar.json",m.toString().getBytes(StandardCharsets.UTF_8));return result;
    }
    private static byte[] glb(boolean textures)throws Exception {
        JSONObject json=new JSONObject("""
        {"asset":{"version":"2.0"},"buffers":[{"byteLength":72}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":36}],
        "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},{"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"}],
        "materials":[{"pbrMetallicRoughness":{"metallicFactor":0}}],"meshes":[{"name":"Face","extras":{"targetNames":["eyeBlinkLeft","eyeBlinkRight","jawOpen"]},"primitives":[{"attributes":{"POSITION":0},"material":0,"targets":[{"POSITION":1},{"POSITION":1},{"POSITION":1}]}]}],
        "nodes":[{"name":"Head","children":[1,2,3,4]},{"name":"Face","mesh":0},{"name":"EyeLeft"},{"name":"EyeRight"},{"name":"JawAttachments"}],"scenes":[{"nodes":[0]}],"scene":0}
        """);if(textures)json.put("textures",new JSONArray().put(new JSONObject()));
        byte[] text=json.toString().getBytes(StandardCharsets.UTF_8);int padded=(text.length+3)&~3;ByteBuffer b=ByteBuffer.allocate(12+8+padded+8+72).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46546c67).putInt(2).putInt(b.capacity()).putInt(padded).putInt(0x4e4f534a).put(text);while(b.position()<20+padded)b.put((byte)' ');
        b.putInt(72).putInt(0x004e4942);for(float v:new float[]{0,0,0,1,0,0,0,1,0})b.putFloat(v);return b.array();
    }
    private static byte[] zip(Map<String,byte[]> entries)throws Exception{return archive(entries,false);}
    private static byte[] storedArchive(Map<String,byte[]> entries)throws Exception{return archive(entries,true);}
    private static byte[] archive(Map<String,byte[]> entries,boolean stored)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){for(var e:entries.entrySet()){
            ZipEntry z=new ZipEntry(e.getKey());if(stored){CRC32 crc=new CRC32();crc.update(e.getValue());z.setMethod(ZipEntry.STORED);z.setSize(e.getValue().length);z.setCrc(crc.getValue());}
            zip.putNextEntry(z);zip.write(e.getValue());zip.closeEntry();}}return bytes.toByteArray();
    }
    private static int localPayload(byte[] zip){ByteBuffer b=ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN);return 30+Short.toUnsignedInt(b.getShort(26))+Short.toUnsignedInt(b.getShort(28));}
    private static byte[] replace(byte[] bytes,String from,String to){byte[] a=from.getBytes(StandardCharsets.UTF_8),b=to.getBytes(StandardCharsets.UTF_8);for(int i=0;i<=bytes.length-a.length;i++){boolean match=true;for(int j=0;j<a.length;j++)match&=bytes[i+j]==a[j];if(match)System.arraycopy(b,0,bytes,i,b.length);}return bytes;}
    private static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    private static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
    interface Checked{void run()throws Exception;}
    private static void rejects(Checked f,String why)throws Exception{try{f.run();}catch(IOException expected){checks++;return;}throw new AssertionError("Expected rejection: "+why);}
}
