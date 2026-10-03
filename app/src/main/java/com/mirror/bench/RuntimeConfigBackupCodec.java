package com.mirror.bench;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** Pure version-one configuration data codec. No preferences, files, restore or UI. */
final class RuntimeConfigBackupCodec {
    static final int MAX_BYTES=65536;
    private static final int MAX_SOURCE_SETTINGS_SCHEMA=4;
    static final class AvatarReference {
        final String source,packageId,modelSha256,manifestSha256,bundleSha256;
        private AvatarReference(String source,String id,String model,String manifest,String bundle) {
            this.source=source;packageId=id;modelSha256=model;manifestSha256=manifest;bundleSha256=bundle;
            hash(model);hash(manifest);
            if(source.equals("imported")){uuid(id);hash(bundle);}
        }
        static AvatarReference builtin(String model,String manifest) { return new AvatarReference("builtin","",model,manifest,""); }
        static AvatarReference imported(String id,String model,String manifest,String bundle) { return new AvatarReference("imported",id,model,manifest,bundle); }
    }
    static final class Backup {
        final String id,signerSha256;
        final long createdUnixMs,appVersionCode;
        final int sourceSchema;
        final MirrorSettings settings;
        final AvatarReference avatar;
        Backup(String id,long created,long version,String signer,int schema,MirrorSettings settings,AvatarReference avatar) {
            this.id=id;createdUnixMs=created;appVersionCode=version;signerSha256=signer;sourceSchema=schema;this.settings=settings;this.avatar=avatar;
            validate(this);
        }
    }
    static byte[] encode(Backup backup) {
        validate(backup);String payload=payload(backup);
        String json="{\"kind\":\"mirror-runtime-config-backup\",\"format_version\":1,\"package\":\"com.mirror.bench\",\"backup_id\":"+quote(backup.id)
            +",\"created_unix_ms\":"+backup.createdUnixMs+",\"app_version_code\":"+backup.appVersionCode+",\"signer_sha256\":"+quote(backup.signerSha256)
            +",\"source_settings_schema\":"+backup.sourceSchema+",\"payload_sha256\":"+quote(sha(payload))+",\"payload\":"+payload+"}";
        byte[] bytes=json.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES)throw invalid("byte budget");return bytes;
    }
    /** trustedSignerSha256 comes from the caller's installed-app identity, never from this file. */
    static Backup decode(byte[] bytes,String trustedSignerSha256) {
        hash(trustedSignerSha256);
        if(bytes==null||bytes.length==0)throw invalid("required bytes");
        if(bytes.length>MAX_BYTES)throw invalid("byte budget");
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.clone())).toString();
            StrictJson syntax=new StrictJson(text);syntax.check();
            JSONObject root=new JSONObject(text);
            keys(root,"kind","format_version","package","backup_id","created_unix_ms","app_version_code","signer_sha256","source_settings_schema","payload_sha256","payload");
            if(!string(root,"kind").equals("mirror-runtime-config-backup")||integer(syntax,"/format_version",0,Integer.MAX_VALUE)!=1
                ||!string(root,"package").equals("com.mirror.bench"))throw invalid("format or package");
            String signer=string(root,"signer_sha256");hash(signer);
            if(!signer.equals(trustedSignerSha256))throw invalid("signer mismatch");
            JSONObject body=object(root,"payload");keys(body,"runtime","panel","camera","avatar");
            JSONObject runtime=object(body,"runtime"),panel=object(body,"panel"),camera=object(body,"camera"),avatar=object(body,"avatar");
            keys(runtime,"active_fps","view_count","view_preset");
            keys(panel,"pitch","tan","phase","units","order","reverse","origin");
            keys(camera,"id","fingerprint","width","height","rotation_degrees","reflect_input","mirror_interaction","revision");
            float phase=decimal(syntax,"/payload/panel/phase");
            if(phase<0||phase>=1)throw invalid("canonical phase");
            PanelCalibration p=new PanelCalibration(decimal(syntax,"/payload/panel/pitch"),decimal(syntax,"/payload/panel/tan"),
                PanelCalibration.PitchUnits.valueOf(string(panel,"units")),phase,PanelCalibration.SubpixelOrder.valueOf(string(panel,"order")),
                bool(panel,"reverse"),PanelCalibration.YOrigin.valueOf(string(panel,"origin")));
            if(Float.floatToRawIntBits(p.phaseCycles())!=Float.floatToRawIntBits(phase))throw invalid("canonical phase");
            CameraControlSettings c=new CameraControlSettings(string(camera,"id"),string(camera,"fingerprint"),
                integer(syntax,"/payload/camera/width",1,Integer.MAX_VALUE),integer(syntax,"/payload/camera/height",1,Integer.MAX_VALUE),
                integer(syntax,"/payload/camera/rotation_degrees",0,Integer.MAX_VALUE),bool(camera,"reflect_input"),bool(camera,"mirror_interaction"),
                whole(syntax,"/payload/camera/revision",0,Long.MAX_VALUE));
            MirrorSettings settings=MirrorSettings.decode(Map.of()).withProfile(integer(syntax,"/payload/runtime/active_fps",1,Integer.MAX_VALUE),
                string(runtime,"view_preset"),integer(syntax,"/payload/runtime/view_count",1,Integer.MAX_VALUE)).withPanel(p).withCamera(c);
            AvatarReference reference;
            String source=string(avatar,"source");
            if(source.equals("builtin")) {
                keys(avatar,"source","model_sha256","manifest_sha256");
                reference=AvatarReference.builtin(string(avatar,"model_sha256"),string(avatar,"manifest_sha256"));
            }else if(source.equals("imported")) {
                keys(avatar,"source","package_id","model_sha256","manifest_sha256","bundle_sha256");
                reference=AvatarReference.imported(string(avatar,"package_id"),string(avatar,"model_sha256"),string(avatar,"manifest_sha256"),string(avatar,"bundle_sha256"));
            }else throw invalid("avatar source");
            Backup result=new Backup(string(root,"backup_id"),whole(syntax,"/created_unix_ms",1,Long.MAX_VALUE),whole(syntax,"/app_version_code",1,Long.MAX_VALUE),
                signer,integer(syntax,"/source_settings_schema",0,MAX_SOURCE_SETTINGS_SCHEMA),settings,reference);
            String expected=string(root,"payload_sha256");hash(expected);
            if(!sha(payload(result)).equals(expected))throw invalid("payload SHA-256 mismatch");
            return result;
        }catch(CharacterCodingException|JSONException failure){throw invalid("UTF-8 or JSON");}
        catch(RuntimeException failure) {
            // Do not expose Enum/JSON/number exceptions containing supplied source values.
            if(failure instanceof IllegalArgumentException&&failure.getMessage()!=null&&failure.getMessage().startsWith("Invalid configuration backup: "))throw failure;
            throw invalid("configuration values");
        }
    }

    private static void keys(JSONObject object,String... keys) {
        if(object.length()!=keys.length)throw invalid("fields");
        Set<String> allowed=new HashSet<>();for(String key:keys)allowed.add(key);
        for(var iter=object.keys();iter.hasNext();)if(!allowed.contains(iter.next()))throw invalid("fields");
    }
    private static JSONObject object(JSONObject parent,String key)throws JSONException {
        Object value=parent.get(key);if(!(value instanceof JSONObject))throw invalid("object type");return (JSONObject)value;
    }
    private static String string(JSONObject parent,String key)throws JSONException {
        Object value=parent.get(key);if(!(value instanceof String))throw invalid("string type");return (String)value;
    }
    private static boolean bool(JSONObject parent,String key)throws JSONException {
        Object value=parent.get(key);if(!(value instanceof Boolean))throw invalid("Boolean type");return (Boolean)value;
    }
    private static long whole(StrictJson syntax,String path,long min,long max) {
        String token=syntax.numbers.get(path);
        if(token==null||!token.matches("-?(0|[1-9][0-9]*)"))throw invalid("integer lexical type");
        try { long value=Long.parseLong(token);if(value<min||value>max)throw invalid("integer range");return value; }
        catch(NumberFormatException overflow){throw invalid("integer range");}
    }
    private static int integer(StrictJson syntax,String path,int min,int max) { return (int)whole(syntax,path,min,max); }
    private static float decimal(StrictJson syntax,String path) {
        String token=syntax.numbers.get(path);if(token==null)throw invalid("number type");
        try {
            float value=Float.parseFloat(token);if(!Float.isFinite(value))throw invalid("finite float32");
            if(value==0f)for(int i=0;i<token.length();i++) {
                char c=token.charAt(i);if(c=='e'||c=='E')break;
                if(c>='1'&&c<='9')throw invalid("float32 underflow");
            }return value;
        }catch(NumberFormatException failure){throw invalid("float32");}
    }

    private static void validate(Backup b) {
        if(b==null||b.settings==null||b.avatar==null)throw invalid("required data");
        uuid(b.id);hash(b.signerSha256);
        if(b.createdUnixMs<=0||b.appVersionCode<=0||b.sourceSchema<0||b.sourceSchema>MAX_SOURCE_SETTINGS_SCHEMA)throw invalid("envelope");
        MirrorSettings s=b.settings;
        if(!s.writable||!s.warning.isEmpty()||s.panel==null||s.camera==null
            ||(s.activeFps!=10&&s.activeFps!=17&&s.activeFps!=20)||(s.viewCount!=16&&s.viewCount!=20)
            ||!(s.viewPreset.equals("240x720")||s.viewPreset.equals("320x576")||s.viewPreset.equals("400x720")||s.viewPreset.equals("400x640")))throw invalid("runtime settings");
        if(s.camera.width!=640||s.camera.height!=480)throw invalid("camera size");
        if(s.camera.isBound())hash(s.camera.fingerprint);
        hash(b.avatar.modelSha256);hash(b.avatar.manifestSha256);
        if(b.avatar.source.equals("builtin")){
            if(!b.avatar.packageId.isEmpty()||!b.avatar.bundleSha256.isEmpty())throw invalid("builtin reference");
        }else if(b.avatar.source.equals("imported")){uuid(b.avatar.packageId);hash(b.avatar.bundleSha256);}
        else throw invalid("avatar source");
    }
    /** Fixed key order and float32 shortest round-trip decimals define format1 payload SHA. */
    private static String payload(Backup b) {
        MirrorSettings s=b.settings;PanelCalibration p=s.panel;CameraControlSettings c=s.camera;AvatarReference a=b.avatar;
        return "{\"runtime\":{\"active_fps\":"+s.activeFps+",\"view_count\":"+s.viewCount+",\"view_preset\":"+quote(s.viewPreset)+"},"
            +"\"panel\":{\"pitch\":"+Float.toString(p.pitch())+",\"tan\":"+Float.toString(p.tan())+",\"phase\":"+Float.toString(p.phaseCycles())
            +",\"units\":"+quote(p.pitchUnits().name())+",\"order\":"+quote(p.subpixelOrder().name())+",\"reverse\":"+p.reverseViews()+",\"origin\":"+quote(p.yOrigin().name())+"},"
            +"\"camera\":{\"id\":"+quote(c.cameraId)+",\"fingerprint\":"+quote(c.fingerprint)+",\"width\":"+c.width+",\"height\":"+c.height
            +",\"rotation_degrees\":"+c.rotationDegrees+",\"reflect_input\":"+c.reflectInput+",\"mirror_interaction\":"+c.mirrorInteraction+",\"revision\":"+c.revision+"},"
            +"\"avatar\":{\"source\":"+quote(a.source)+(a.source.equals("imported")?",\"package_id\":"+quote(a.packageId):"")
            +",\"model_sha256\":"+quote(a.modelSha256)+",\"manifest_sha256\":"+quote(a.manifestSha256)
            +(a.source.equals("imported")?",\"bundle_sha256\":"+quote(a.bundleSha256):"")+"}}";
    }
    private static String quote(String value) {
        if(value==null)throw invalid("string");unicode(value);
        StringBuilder out=new StringBuilder("\"");
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(c=='"'||c=='\\')out.append('\\').append(c);
            else if(c<32){out.append("\\u00");out.append("0123456789abcdef".charAt(c>>4));out.append("0123456789abcdef".charAt(c&15));}
            else out.append(c);
        }return out.append('"').toString();
    }
    private static void unicode(String value) {
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw invalid("Unicode");}
            else if(Character.isLowSurrogate(c))throw invalid("Unicode");
        }
    }
    private static void hash(String value) { if(value==null||!value.matches("[0-9a-f]{64}"))throw invalid("SHA-256"); }
    private static void uuid(String value) { if(value==null||!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw invalid("UUID"); }
    private static String sha(String value) {
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder hex=new StringBuilder(64);
            for(byte b:bytes)hex.append("0123456789abcdef".charAt((b&255)>>4)).append("0123456789abcdef".charAt(b&15));return hex.toString();
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static IllegalArgumentException invalid(String category) { return new IllegalArgumentException("Invalid configuration backup: "+category); }

    /** Based on the existing package JSON guard, narrowed to 8 levels/256 values/no null.
     * Preserve numeric lexemes because Android and JSON-java choose different Number classes. */
    private static final class StrictJson {
        final String text;final Map<String,String> numbers=new HashMap<>();int at,values;
        StrictJson(String text){this.text=text;}
        void check(){space();if(peek()!='{')throw invalid("JSON object");value(0,"");space();if(at!=text.length())throw invalid("JSON trailing content");}
        void value(int depth,String path) {
            if(depth>8||++values>256)throw invalid("JSON budget");space();char c=peek();
            if(c=='{') {
                at++;space();Set<String> keys=new HashSet<>();if(take('}'))return;
                do {space();String key=string();if(!keys.add(key))throw invalid("duplicate JSON key");space();expect(':');
                    value(depth+1,path+"/"+key.replace("~","~0").replace("/","~1"));space();if(take('}'))return;expect(',');}while(true);
            }else if(c=='[') {
                at++;space();if(take(']'))return;int index=0;
                do {value(depth+1,path+"/"+index++);space();if(take(']'))return;expect(',');}while(true);
            }else if(c=='"')string();
            else if(c=='t')literal("true");else if(c=='f')literal("false");
            else if(c=='n')throw invalid("null value");else numbers.put(path,number());
        }
        String string() {
            expect('"');StringBuilder out=new StringBuilder();
            while(at<text.length()) {
                char c=text.charAt(at++);if(c=='"'){String result=out.toString();unicode(result);return result;}
                if(c<32)throw invalid("JSON control character");
                if(c=='\\') {
                    if(at==text.length())throw invalid("JSON escape");char escaped=text.charAt(at++);
                    switch(escaped) {
                        case '"':case '\\':case '/':c=escaped;break;
                        case 'b':c='\b';break;case 'f':c='\f';break;case 'n':c='\n';break;case 'r':c='\r';break;case 't':c='\t';break;
                        case 'u':int code=0;for(int j=0;j<4;j++) {
                            if(at==text.length())throw invalid("Unicode escape");char h=text.charAt(at++);
                            int digit=h>='0'&&h<='9'?h-'0':h>='a'&&h<='f'?h-'a'+10:h>='A'&&h<='F'?h-'A'+10:-1;
                            if(digit<0)throw invalid("Unicode escape");code=code*16+digit;
                        }c=(char)code;break;
                        default:throw invalid("JSON escape");
                    }
                }out.append(c);
            }throw invalid("JSON string");
        }
        String number() {
            int start=at;take('-');
            if(take('0')){if(digit(peek()))throw invalid("JSON leading zero");}
            else {if(peek()<'1'||peek()>'9')throw invalid("JSON number");while(digit(peek()))at++;}
            if(take('.')){if(!digit(peek()))throw invalid("JSON fraction");while(digit(peek()))at++;}
            if(peek()=='e'||peek()=='E'){at++;if(peek()=='+'||peek()=='-')at++;if(!digit(peek()))throw invalid("JSON exponent");while(digit(peek()))at++;}
            return text.substring(start,at);
        }
        void literal(String value){if(!text.startsWith(value,at))throw invalid("JSON literal");at+=value.length();}
        void space(){while(at<text.length()&&" \t\r\n".indexOf(text.charAt(at))>=0)at++;}
        char peek(){return at<text.length()?text.charAt(at):'\0';}
        boolean take(char c){if(peek()!=c)return false;at++;return true;}
        void expect(char c){if(!take(c))throw invalid("JSON structure");}
        boolean digit(char c){return c>='0'&&c<='9';}
    }
}
