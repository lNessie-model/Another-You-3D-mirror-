package com.mirror.bench;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.util.zip.CRC32;
import org.json.JSONObject;
import org.json.JSONArray;

/**
 * Private, offline avatar package storage. All methods perform disk/CPU work: call off the UI thread.
 * The caller owns the SAF InputStream and must close it, including to unblock a provider read.
 * Import validates/publishes a candidate; only activate(ticket), AFTER GPU acceptance, selects it.
 * Import is API30+ because the existing loader/rig uses Java collection APIs introduced there.
 * Older callers keep the APK builtin path. NIO is isolated in a lazily loaded implementation.
 * A private application directory is required; this is not protection against a compromised same UID.
 */
public final class AvatarPackageStore {
    public static final long MAX_ZIP_BYTES=36L*1024*1024, MAX_EXPANDED_BYTES=34L*1024*1024;
    public static final int MIN_ANDROID_API=30;
    private final NioStore implementation;
    private AvatarPackageStore(File root, Faults faults)throws IOException {implementation=new NioStore(root,faults);}
    public static boolean supportsAndroidApi(int api){return api>=MIN_ANDROID_API;}
    public static AvatarPackageStore open(File privateRoot,int androidApi)throws IOException {
        return open(privateRoot,androidApi,point->{});
    }
    static AvatarPackageStore open(File root,int api,Faults faults)throws IOException {
        if(!supportsAndroidApi(api))throw new UnsupportedPlatformException("Avatar import requires Android 11 / API 30 or later (current loader profile)");
        if(root==null||faults==null)throw new IllegalArgumentException("Root and faults are required");
        return new AvatarPackageStore(root,faults);
    }
    public File directory(){return implementation.directory();}
    public LoadedPackage importZip(InputStream input)throws IOException{return implementation.importZip(input);}
    public LoadedPackage load(String packageId)throws IOException{return implementation.load(packageId);}
    /** Null means the application should load its APK builtin. Corruption is an explicit IOException. */
    public LoadedPackage readCurrent()throws IOException{return implementation.readCurrent();}
    public LoadedPackage readPrevious()throws IOException{return implementation.readOther(false);}
    public LoadedPackage readCandidate()throws IOException{return implementation.readOther(true);}
    /** Lightweight stored report summaries only; load the selected package before GPU preview. */
    public Catalog readCatalog()throws IOException{return implementation.readCatalog();}
    public void activate(Ticket ticket)throws IOException{implementation.activate(ticket);}
    public void activateBuiltin()throws IOException{implementation.activateBuiltin();}
    /** Preview readPrevious() first; reject if that exact previous package was superseded meanwhile. */
    public void restorePrevious(Ticket expected)throws IOException{implementation.restorePrevious(expected);}
    public void discard(Ticket candidate)throws IOException{implementation.discard(candidate);}
    public void recover()throws IOException{implementation.recover();}

    public static final class UnsupportedPlatformException extends IOException {
        UnsupportedPlatformException(String message){super(message);}
    }
    public static final class BusyException extends IOException {
        BusyException(){super("Avatar store is busy; retry from the background worker");}
    }
    /** Store-generated identity and exact validated content. Untrusted manifest id never names a path. */
    public static final class Ticket {
        public final String packageId,modelSha256,manifestSha256,bundleSha256;
        private Ticket(String id,String model,String manifest,String bundle){packageId=id;modelSha256=model;manifestSha256=manifest;bundleSha256=bundle;}
    }
    public static final class Summary {
        public final String packageId,displayName,coverage,modelSha256;
        public final int vertices,triangles;
        private Summary(String id,String display,String coverage,String hash,int vertices,int triangles){
            packageId=id;displayName=display;this.coverage=coverage;modelSha256=hash;this.vertices=vertices;this.triangles=triangles;
        }
    }
    public static final class Catalog {
        public final Summary current,previous,candidate;
        private Catalog(Summary current,Summary previous,Summary candidate){this.current=current;this.previous=previous;this.candidate=candidate;}
    }
    /** Asset immutable; rig is mutable and must be handed to exactly one runtime owner. No GL handles. */
    public static final class LoadedPackage {
        public final Ticket ticket;
        public final AvatarAsset asset;
        public final AvatarRig rig;
        public final AvatarFraming framing;
        public final String manifestJson,reportJson;
        public final List<String> cleanupWarnings;
        private LoadedPackage(Ticket t,AvatarAsset a,AvatarRig r,AvatarFraming f,String manifest,String report,List<String> warnings){
            ticket=t;asset=a;rig=r;framing=f;manifestJson=manifest;reportJson=report;cleanupWarnings=java.util.Collections.unmodifiableList(warnings);
        }
    }
    interface Faults {void at(String point)throws IOException;}

    /** Referenced only after the explicit API check above; no NIO file linkage in the facade. */
    private static final class NioStore {
        private static final LinkOption NOFOLLOW=LinkOption.NOFOLLOW_LINKS;
        // One application-private store is expected. Serialize all in-process instances before
        // opening another descriptor: on some systems closing it releases other process locks.
        private static final java.util.concurrent.locks.ReentrantLock PROCESS_LOCK=new java.util.concurrent.locks.ReentrantLock();
        private static final Map<String,Long> FILES;
        static {
            Map<String,Long> files=new java.util.HashMap<>();files.put("character.glb",32L*1024*1024);
            files.put("avatar.json",256L*1024);files.put("README.md",64L*1024);
            files.put("LICENSE.txt",64L*1024);files.put("thumbnail.png",1024L*1024);
            FILES=java.util.Collections.unmodifiableMap(files);
        }
        private static final String REPORT="validation.json";
        private static final int MAX_PARSE_CACHE_ENTRIES=3;
        private static final long MAX_PARSE_CACHE_BYTES=32L*1024*1024;
        // Guard-owned, per Store instance. Never cache a mutable rig/deformer or trust mtime.
        private final java.util.LinkedHashMap<String,Parsed> parseCache=new java.util.LinkedHashMap<>(4,.75f,true);
        private long parseCacheBytes;
        private static final class Parsed {
            final Ticket ticket;final AvatarAsset asset;final AvatarFraming framing;final String manifest,report;
            Parsed(Ticket t,AvatarAsset a,AvatarFraming f,String m,String r){ticket=t;asset=a;framing=f;manifest=m;report=r;}
        }
        private void dropParsed(String id){Parsed old=parseCache.remove(id);if(old!=null)parseCacheBytes-=old.asset.decodedBytes();}
        private void rememberParsed(Parsed parsed){
            dropParsed(parsed.ticket.packageId);long bytes=parsed.asset.decodedBytes();
            if(bytes>MAX_PARSE_CACHE_BYTES)return;
            while(!parseCache.isEmpty()&&(parseCache.size()>=MAX_PARSE_CACHE_ENTRIES||parseCacheBytes>MAX_PARSE_CACHE_BYTES-bytes))
                dropParsed(parseCache.keySet().iterator().next());
            parseCache.put(parsed.ticket.packageId,parsed);parseCacheBytes+=bytes;
        }
        final Path root,packages,stage,archive,stateFile,stateTemp,lockFile;
        final Faults faults;
        File directory(){return root.toFile();}
        NioStore(File directory,Faults faults)throws IOException {
            this.faults=faults;
            Path requested=directory.toPath().toAbsolutePath().normalize();
            if(Files.isSymbolicLink(requested))throw invalid("Store root cannot be a symbolic link");
            Files.createDirectories(requested);root=requested.toRealPath();
            packages=root.resolve("packages");stage=root.resolve("staging");archive=root.resolve("incoming.zip");
            stateFile=root.resolve("state.json");stateTemp=root.resolve("state.tmp");lockFile=root.resolve("store.lock");
            if(Files.exists(packages,NOFOLLOW))directory(packages);else Files.createDirectory(packages);
            // Guard creates/opens the lock file after acquiring the in-process lock.
        }
        private Guard guard()throws IOException {return new Guard();}
        private final class Guard implements AutoCloseable {
            final FileChannel channel;final FileLock lock;
            Guard()throws IOException {
                if(PROCESS_LOCK.isHeldByCurrentThread()||!PROCESS_LOCK.tryLock())throw new BusyException();
                FileChannel opened=null;
                try {
                    if(Files.exists(lockFile,NOFOLLOW))regular(lockFile);
                    opened=FileChannel.open(lockFile,StandardOpenOption.CREATE,StandardOpenOption.WRITE,NOFOLLOW);
                    FileLock acquired=opened.tryLock();if(acquired==null)throw new BusyException();channel=opened;lock=acquired;
                }catch(Throwable failure){
                    if(opened!=null)try{opened.close();}catch(IOException close){failure.addSuppressed(close);}PROCESS_LOCK.unlock();
                    if(failure instanceof OverlappingFileLockException)throw new BusyException();
                    if(failure instanceof IOException)throw (IOException)failure;
                    if(failure instanceof Error)throw (Error)failure;
                    throw new IOException("Cannot lock avatar store",failure);
                }
            }
            public void close()throws IOException{try{try{lock.release();}finally{channel.close();}}finally{PROCESS_LOCK.unlock();}}
        }
        LoadedPackage importZip(InputStream input)throws IOException {
            if(input==null)throw new IllegalArgumentException("Input is required");
            try(Guard ignored=guard()){
                State state=readState();cleanup(state);cancelled();
                // Android's NIO FileStore query can be denied even for an app-private path.
                // StatFs reports application-available bytes (excluding reserved blocks).
                final long available;
                try{available=new android.os.StatFs(root.toString()).getAvailableBytes();}
                catch(RuntimeException failure){throw new IOException("Cannot determine available space for avatar staging",failure);}
                if(available<MAX_ZIP_BYTES+MAX_EXPANDED_BYTES+4L*1024*1024)
                    throw invalid("At least 74 MiB free space is required for bounded avatar staging");
                String id=UUID.randomUUID().toString();Path destination=packages.resolve(id);
                boolean committed=false;
                try {
                    copy(input,archive,MAX_ZIP_BYTES,null);Files.createDirectory(stage);extract();
                    Files.delete(archive);LoadedPackage loaded=validate(stage,id,false);cancelled();
                    writeSynced(stage.resolve(REPORT),loaded.reportJson.getBytes(StandardCharsets.UTF_8));
                    faults.at("before-package-move");atomicMove(stage,destination);faults.at("after-package-move");
                    writeState(new State(state.current,state.previous,id));committed=true;
                    ArrayList<String> warnings=new ArrayList<>();
                    try{cleanup(readState());}catch(IOException e){warnings.add("Published; deferred cleanup: "+e.getMessage());}
                    return new LoadedPackage(loaded.ticket,loaded.asset,loaded.rig,loaded.framing,loaded.manifestJson,loaded.reportJson,warnings);
                }catch(Throwable failure){
                    if(!committed)try{cleanup(readState());}catch(Throwable cleanup){failure.addSuppressed(cleanup);}
                    if(failure instanceof Error)throw (Error)failure;
                    if(failure instanceof IOException)throw (IOException)failure;
                    throw new IOException("Avatar package validation failed: "+failure.getMessage(),failure);
                }
            }
        }
        private void extract()throws IOException {
            try(ZipFile zip=new ZipFile(archive.toFile())){
                if(zip.size()<2||zip.size()>FILES.size())throw invalid("ZIP must contain 2 to 5 flat allowed files");
                HashSet<String> seen=new HashSet<>();long total=0;
                var entries=zip.entries();while(entries.hasMoreElements()){
                    cancelled();ZipEntry entry=entries.nextElement();String name=entry.getName();Long limit=FILES.get(name);
                    if(entry.isDirectory()||limit==null||!seen.add(name))throw invalid("Unexpected/duplicate ZIP entry: "+name);
                    if(entry.getMethod()!=ZipEntry.STORED&&entry.getMethod()!=ZipEntry.DEFLATED)throw invalid("Unsupported ZIP compression");
                    long size=entry.getSize();if(size<0||size>limit||size>MAX_EXPANDED_BYTES-total||entry.getCrc()<0)throw invalid("ZIP entry size/CRC outside profile: "+name);
                    CRC32 crc=new CRC32();long actual;
                    try(InputStream in=zip.getInputStream(entry)){actual=copy(in,stage.resolve(name),Math.min(limit,MAX_EXPANDED_BYTES-total),crc);}
                    if(actual!=size||crc.getValue()!=entry.getCrc())throw invalid("ZIP size/CRC mismatch: "+name);
                    total+=actual;
                }
                if(!seen.contains("character.glb")||!seen.contains("avatar.json"))throw invalid("ZIP requires character.glb and avatar.json");
            }
        }
        LoadedPackage load(String id)throws IOException {try(Guard ignored=guard()){return loadLocked(id,readState());}}
        LoadedPackage readCurrent()throws IOException {try(Guard ignored=guard()){State state=readState();return state.current==null?null:loadLocked(state.current,state);}}
        LoadedPackage readOther(boolean candidate)throws IOException {try(Guard ignored=guard()){
            State state=readState();String id=candidate?state.candidate:state.previous;return id==null?null:loadLocked(id,state);
        }}
        Catalog readCatalog()throws IOException {try(Guard ignored=guard()){
            State state=readState();return new Catalog(summary(state.current),summary(state.previous),summary(state.candidate));
        }}
        private Summary summary(String id)throws IOException {
            if(id==null)return null;packageId(id);directory(packages.resolve(id));
            try {
                String data=text(packages.resolve(id).resolve(REPORT),32*1024);jsonDepth(data);JSONObject json=new JSONObject(data);
                Object display=json.get("display_name"),coverage=json.get("coverage"),hash=json.get("model_sha256");
                if(!id.equals(json.opt("package_id"))||!(display instanceof String)||((String)display).isEmpty()||((String)display).length()>256
                        ||!(hash instanceof String)||!((String)hash).matches("[0-9a-f]{64}")
                        ||(!"complete_sources".equals(coverage)&&!"partial".equals(coverage)))throw invalid("Invalid stored package summary");
                int vertices=summaryCount(json,"vertices",20_000),triangles=summaryCount(json,"triangles",30_000);
                return new Summary(id,(String)display,(String)coverage,(String)hash,vertices,triangles);
            }catch(IOException e){throw e;}catch(Exception e){throw new IOException("Cannot read stored package summary",e);}
        }
        private static int summaryCount(JSONObject json,String key,int maximum)throws Exception {
            Object raw=json.get(key);if(!(raw instanceof Number))throw invalid("Invalid summary count");double number=((Number)raw).doubleValue();
            if(number<0||number>maximum||number!=Math.rint(number))throw invalid("Invalid summary count");return (int)number;
        }
        private LoadedPackage loadLocked(String id,State state)throws IOException {
            packageId(id);if(!state.contains(id))throw invalid("Package is no longer retained");return validate(packages.resolve(id),id,true);
        }
        void activate(Ticket ticket)throws IOException {try(Guard ignored=guard()){activateLocked(ticket,readState());}}
        private void activateLocked(Ticket ticket,State state)throws IOException {
            if(ticket==null)throw invalid("Validated ticket required");packageId(ticket.packageId);
            if(!state.contains(ticket.packageId))throw invalid("Stale avatar candidate; reload before activation");
            var hashes=hashes(packages.resolve(ticket.packageId),true);
            if(!ticket.bundleSha256.equals(bundleHash(hashes))||!ticket.modelSha256.equals(hashes.get("character.glb"))
                    ||!ticket.manifestSha256.equals(hashes.get("avatar.json")))throw invalid("Package changed after validation");
            if(ticket.packageId.equals(state.current))return;
            writeState(new State(ticket.packageId,state.current,ticket.packageId.equals(state.candidate)?null:state.candidate));
            // Cleanup is performed by recover/import; selection never fails AFTER its atomic commit.
        }
        void activateBuiltin()throws IOException {try(Guard ignored=guard()){
            State state=readState();if(state.current!=null)writeState(new State(null,state.current,state.candidate));
        }}
        void restorePrevious(Ticket expected)throws IOException {try(Guard ignored=guard()){
            State state=readState();if(expected==null||!expected.packageId.equals(state.previous))throw invalid("Previous package changed; reload before restoring");
            activateLocked(expected,state);
        }}
        void discard(Ticket ticket)throws IOException {try(Guard ignored=guard()){
            State state=readState();if(ticket==null||!ticket.packageId.equals(state.candidate))throw invalid("Only the current candidate may be discarded");
            if(ticket.packageId.equals(state.current)||ticket.packageId.equals(state.previous))throw invalid("Cannot discard current/previous");
            writeState(new State(state.current,state.previous,null));
        }}
        void recover()throws IOException {try(Guard ignored=guard()){cleanup(readState());}}
        private LoadedPackage validate(Path folder,String id,boolean published)throws IOException {
            try {
                cancelled();var hashes=hashes(folder,published);String manifest=text(folder.resolve("avatar.json"),256*1024);jsonDepth(manifest);
                JSONObject json=new JSONObject(manifest);
                if(!"character.glb".equals(json.opt("model")))throw invalid("Manifest model must be character.glb");
                Object hash=json.opt("modelSha256");if(!(hash instanceof String)||!((String)hash).matches("[0-9a-fA-F]{64}")
                        ||!hashes.get("character.glb").equalsIgnoreCase((String)hash))throw invalid("Manifest model SHA-256 mismatch");
                if(json.has("license")){
                    var license=json.getJSONObject("license");if(!"LICENSE.txt".equals(license.opt("file"))||!hashes.containsKey("LICENSE.txt"))throw invalid("License must reference included LICENSE.txt");
                }
                String bundle=bundleHash(hashes);
                if(published){String saved=text(folder.resolve(REPORT),32*1024);jsonDepth(saved);var report=new JSONObject(saved);
                    if(!id.equals(report.opt("package_id"))||!bundle.equals(report.opt("bundle_sha256")))throw invalid("Published package integrity report mismatch");}
                // Integrity and metadata checks above ALWAYS precede reuse, including validation.json.
                Parsed cached=parseCache.get(id);
                if(cached!=null&&cached.ticket.bundleSha256.equals(bundle)
                        &&cached.ticket.modelSha256.equals(hashes.get("character.glb"))
                        &&cached.ticket.manifestSha256.equals(hashes.get("avatar.json"))&&cached.manifest.equals(manifest)){
                    AvatarRig rig=new AvatarRig(cached.asset,manifest);cancelled();
                    return new LoadedPackage(cached.ticket,cached.asset,rig,cached.framing,manifest,cached.report,new ArrayList<>());
                }
                dropParsed(id);faults.at("before-avatar-parse");
                AvatarAsset asset;try(InputStream in=Files.newInputStream(folder.resolve("character.glb"),NOFOLLOW)){asset=AvatarGlbLoader.load(in);}
                AvatarRig rig=new AvatarRig(asset,manifest);cancelled();
                AvatarDeformer.NormalPolicy policy=AvatarDeformer.NormalPolicy.fromManifest(rig.normalPolicy());
                AvatarDeformer deformer=new AvatarDeformer(asset,policy);
                for(int i=0;i<asset.meshes().size();i++){float[] weights=new float[asset.meshes().get(i).targetCount()];rig.copyMeshWeights(i,weights);deformer.updateMesh(i,weights);cancelled();}
                AvatarFraming framing=AvatarGeometryBounds.fromAsset(asset,rig);
                JSONObject report=new JSONObject();report.put("schema_version",1).put("package_id",id).put("bundle_sha256",bundle).put("display_name",rig.displayName())
                        .put("model_sha256",hashes.get("character.glb")).put("manifest_sha256",hashes.get("avatar.json"))
                        .put("profile",asset.albedoAtlas()==null?"morph-rigid-factor-only-v1":"morph-rigid-albedo-atlas-v1").put("coverage",rig.completeSourceCoverage()?"complete_sources":"partial")
                        .put("missing_sources",new JSONArray(rig.missingSources())).put("vertices",asset.vertexCount()).put("triangles",asset.triangleCount())
                        .put("decoded_geometry_bytes",asset.decodedBytes()).put("deformer_source_cache_bytes",deformer.sourceCacheBytes())
                        .put("loader_rig_validated",true).put("neutral_deformation_validated",true).put("framing_validated",true)
                        .put("gpu_validated",false).put("artwork_validated",false).put("texture_supported",asset.albedoAtlas()!=null).put("skin_supported",false)
                        .put("thumbnail_decoded",false).put("file_sha256",new JSONObject(hashes));
                Ticket ticket=new Ticket(id,hashes.get("character.glb"),hashes.get("avatar.json"),bundle);String generated=report.toString(2);
                rememberParsed(new Parsed(ticket,asset,framing,manifest,generated));
                return new LoadedPackage(ticket,asset,rig,framing,manifest,generated,new ArrayList<>());
            }catch(IOException e){dropParsed(id);throw e;}catch(Exception e){dropParsed(id);throw new IOException("Avatar validation failed: "+e.getMessage(),e);}
        }
        private SortedMap<String,String> hashes(Path folder,boolean published)throws IOException {
            directory(folder);SortedMap<String,String> result=new TreeMap<>();long total=0;int count=0;
            try(var entries=Files.newDirectoryStream(folder)){for(var file:entries){
                if(++count>6)throw invalid("Too many package files");String name=file.getFileName().toString();regular(file);
                if(published&&name.equals(REPORT)){if(Files.size(file)>32*1024)throw invalid("Oversized report");continue;}
                Long max=FILES.get(name);long size=Files.size(file);
                if(max==null||size>max||size>MAX_EXPANDED_BYTES-total)throw invalid("Unexpected/oversized package file");total+=size;
                var digest=digest();byte[] buffer=new byte[16384];try(InputStream in=Files.newInputStream(file,NOFOLLOW)){
                    int n;long actual=0;while((n=in.read(buffer))!=-1){cancelled();actual+=n;if(actual>max)throw invalid("Package changed while reading");digest.update(buffer,0,n);}
                    if(actual!=size)throw invalid("Package size changed while reading");}
                result.put(name,hex(digest.digest()));
            }}
            if(!result.containsKey("character.glb")||!result.containsKey("avatar.json"))throw invalid("Missing required package files");
            if(published)regular(folder.resolve(REPORT));return result;
        }
        private static String bundleHash(SortedMap<String,String> hashes){var digest=digest();for(var e:hashes.entrySet()){
            digest.update((e.getKey()+"\0"+e.getValue()+"\n").getBytes(StandardCharsets.UTF_8));}return hex(digest.digest());}
        private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
        private static String hex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);for(byte b:bytes)out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return out.toString();}
        private static long copy(InputStream in,Path path,long maximum,CRC32 crc)throws IOException {
            try(FileChannel out=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,NOFOLLOW)){
                byte[] buffer=new byte[16384];long total=0;int n;
                while(true){cancelled();n=in.read(buffer);if(n==-1)break;if(n==0)throw invalid("Input made no progress");
                    if(n>maximum-total)throw invalid("Input exceeds byte budget");total+=n;if(crc!=null)crc.update(buffer,0,n);
                    ByteBuffer bytes=ByteBuffer.wrap(buffer,0,n);while(bytes.hasRemaining())out.write(bytes);}
                cancelled();out.force(true);return total;
            }
        }
        private static String text(Path file,int max)throws IOException {
            regular(file);long size=Files.size(file);if(size<=0||size>max)throw invalid("Missing/oversized JSON");
            byte[] bytes=new byte[(int)size];try(InputStream in=Files.newInputStream(file,NOFOLLOW)){
                int at=0,n;while(at<bytes.length&&(n=in.read(bytes,at,bytes.length-at))>0)at+=n;if(at!=bytes.length||in.read()!=-1)throw invalid("JSON changed while reading");}
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        }
        /** Reject org.json's nonstandard syntax BEFORE its recursive parser (Android and host differ). */
        private static void jsonDepth(String text)throws IOException {new StrictJson(text).check();}
        private static final class StrictJson {
            final String text;int at,values;
            StrictJson(String text){this.text=text;}
            void check()throws IOException {space();if(peek()!='{')throw invalid("JSON object required");value(0);space();if(at!=text.length())throw invalid("Trailing JSON content");}
            void value(int depth)throws IOException {
                if(depth>=48||++values>30000)throw invalid("JSON nesting/value budget");space();char c=peek();
                if(c=='{'){
                    at++;space();HashSet<String> keys=new HashSet<>();if(take('}'))return;
                    do{space();String key=string();if(!keys.add(key))throw invalid("Duplicate JSON key");space();expect(':');value(depth+1);space();if(take('}'))return;expect(',');}while(true);
                }else if(c=='['){
                    at++;space();if(take(']'))return;
                    do{value(depth+1);space();if(take(']'))return;expect(',');}while(true);
                }else if(c=='"')string();
                else if(c=='t')literal("true");else if(c=='f')literal("false");else if(c=='n')literal("null");
                else number();
            }
            String string()throws IOException {
                expect('"');StringBuilder out=new StringBuilder();
                while(at<text.length()){
                    char c=text.charAt(at++);if(c=='"')return out.toString();if(c<0x20)throw invalid("JSON string control character");
                    if(c=='\\'){
                        if(at==text.length())throw invalid("Truncated JSON escape");char e=text.charAt(at++);
                        switch(e){case '"':case '\\':case '/':c=e;break;
                            case 'b':c='\b';break;case 'f':c='\f';break;case 'n':c='\n';break;case 'r':c='\r';break;case 't':c='\t';break;
                            case 'u':int code=0;for(int j=0;j<4;j++){if(at==text.length())throw invalid("Truncated Unicode escape");char h=text.charAt(at++);int digit=h>='0'&&h<='9'?h-'0':h>='a'&&h<='f'?h-'a'+10:h>='A'&&h<='F'?h-'A'+10:-1;if(digit<0)throw invalid("Invalid Unicode escape");code=code*16+digit;}c=(char)code;break;
                            default:throw invalid("Nonstandard JSON escape");}
                    }out.append(c);
                }throw invalid("Unclosed JSON string");
            }
            void number()throws IOException {
                take('-');if(take('0')){if(digit(peek()))throw invalid("Leading zero in JSON number");}
                else {if(peek()<'1'||peek()>'9')throw invalid("Invalid JSON value");while(digit(peek()))at++;}
                if(take('.')){if(!digit(peek()))throw invalid("Invalid JSON fraction");while(digit(peek()))at++;}
                if(peek()=='e'||peek()=='E'){at++;if(peek()=='+'||peek()=='-')at++;if(!digit(peek()))throw invalid("Invalid JSON exponent");while(digit(peek()))at++;}
            }
            void literal(String word)throws IOException {if(!text.startsWith(word,at))throw invalid("Invalid JSON literal");at+=word.length();}
            void space(){while(at<text.length()){char c=text.charAt(at);if(c!=' '&&c!='\n'&&c!='\r'&&c!='\t')break;at++;}}
            char peek(){return at<text.length()?text.charAt(at):'\0';}
            boolean take(char c){if(peek()!=c)return false;at++;return true;}
            void expect(char c)throws IOException {if(!take(c))throw invalid("Invalid JSON structure");}
            boolean digit(char c){return c>='0'&&c<='9';}
        }
        private State readState()throws IOException {
            if(!Files.exists(stateFile,NOFOLLOW))return new State(null,null,null);
            try{String text=text(stateFile,4096);jsonDepth(text);var json=new JSONObject(text);
                if(json.length()!=4||!(json.get("version") instanceof Number)||((Number)json.get("version")).doubleValue()!=1)throw invalid("Unsupported package catalog");
                State state=new State(nullableId(json,"current"),nullableId(json,"previous"),nullableId(json,"candidate"));
                if((state.current!=null&&(state.current.equals(state.previous)||state.current.equals(state.candidate)))
                        ||(state.previous!=null&&state.previous.equals(state.candidate)))throw invalid("Overlapping package catalog roles");
                return state;
            }catch(IOException e){throw e;}catch(Exception e){throw new IOException("Corrupt avatar catalog; preserved for recovery",e);}
        }
        private static String nullableId(JSONObject json,String key)throws Exception {
            Object value=json.get(key);if(value==JSONObject.NULL)return null;if(!(value instanceof String))throw invalid("Catalog id type");packageId((String)value);return (String)value;
        }
        private void writeState(State state)throws IOException {
            try{var json=new JSONObject();json.put("version",1).put("current",state.current==null?JSONObject.NULL:state.current)
                    .put("previous",state.previous==null?JSONObject.NULL:state.previous).put("candidate",state.candidate==null?JSONObject.NULL:state.candidate);
                remove(stateTemp);writeSynced(stateTemp,json.toString().getBytes(StandardCharsets.UTF_8));cancelled();
                faults.at("before-state-move");cancelled();atomicMove(stateTemp,stateFile);
            }catch(IOException e){throw e;}catch(Exception e){throw new IOException("Cannot publish avatar selection",e);}
        }
        private static void writeSynced(Path file,byte[] bytes)throws IOException {copy(new java.io.ByteArrayInputStream(bytes),file,bytes.length,null);}
        private static void atomicMove(Path from,Path to)throws IOException {
            // Fail closed on providers without atomic rename. Never emulate using copy/delete.
            Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }
        private void cleanup(State state)throws IOException {
            remove(archive);remove(stage);remove(stateTemp);directory(packages);int count=0;
            try(var entries=Files.newDirectoryStream(packages)){for(var entry:entries){
                if(++count>8)throw invalid("Unexpected number of retained package directories");String id=entry.getFileName().toString();packageId(id);
                if(!state.contains(id))remove(entry);
            }}
            for(String id:new ArrayList<>(parseCache.keySet()))if(!state.contains(id))dropParsed(id);
        }
        private void remove(Path path)throws IOException {
            Path absolute=path.toAbsolutePath().normalize();if(absolute.equals(root)||!absolute.startsWith(root))throw invalid("Cleanup escaped store root");
            if(!Files.exists(path,NOFOLLOW))return;
            // Only flat store-owned directories are supported. Links are unlinked, never followed.
            if(Files.isDirectory(path,NOFOLLOW))try(var entries=Files.newDirectoryStream(path)){
                int count=0;for(var entry:entries){if(++count>8||Files.isDirectory(entry,NOFOLLOW))throw invalid("Unexpected nested cleanup content");Files.delete(entry);}
            }
            Files.delete(path);
        }
        private static void packageId(String id)throws IOException {if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid("Invalid store package id");}
        private static void regular(Path file)throws IOException {if(!Files.isRegularFile(file,NOFOLLOW))throw invalid("Expected private regular file: "+file.getFileName());}
        private static void directory(Path file)throws IOException {if(!Files.isDirectory(file,NOFOLLOW))throw invalid("Expected private directory: "+file.getFileName());}
        private static void cancelled()throws IOException {if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Avatar import cancelled");}
        private static IOException invalid(String message){return new IOException(message);}
        private static final class State {
            final String current,previous,candidate;
            State(String c,String p,String n){current=c;previous=p;candidate=n;}
            boolean contains(String id){return id!=null&&(id.equals(current)||id.equals(previous)||id.equals(candidate));}
        }
    }
}
