package com.mirror.bench;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONObject;

/** Real codec and real JSON-java; fixed external payload, hostile inputs and exact typed round trips. */
public final class RuntimeConfigBackupCodecTest {
    private static int checks;
    private static final String ID="11111111-1111-4111-8111-111111111111";
    private static final String AVATAR_ID="22222222-2222-4222-8222-222222222222";
    private static final String SIGNER="a".repeat(64),MODEL="b".repeat(64),MANIFEST="c".repeat(64),BUNDLE="d".repeat(64);
    private static final String PAYLOAD="{\"runtime\":{\"active_fps\":17,\"view_count\":16,\"view_preset\":\"400x640\"},"
        +"\"panel\":{\"pitch\":10.0,\"tan\":0.2777777,\"phase\":0.0,\"units\":\"SUBPIXELS\",\"order\":\"RGB\",\"reverse\":false,\"origin\":\"BOTTOM\"},"
        +"\"camera\":{\"id\":\"\",\"fingerprint\":\"\",\"width\":640,\"height\":480,\"rotation_degrees\":0,\"reflect_input\":false,\"mirror_interaction\":false,\"revision\":0},"
        +"\"avatar\":{\"source\":\"builtin\",\"model_sha256\":\""+MODEL+"\",\"manifest_sha256\":\""+MANIFEST+"\"}}";
    private static void check(boolean value,String message) { checks++;if(!value)throw new AssertionError(message); }
    private static String sha(String text)throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
    private static String fixture()throws Exception {
        return "{\"kind\":\"mirror-runtime-config-backup\",\"format_version\":1,\"package\":\"com.mirror.bench\",\"backup_id\":\""+ID
            +"\",\"created_unix_ms\":1700000000123,\"app_version_code\":18,\"signer_sha256\":\""+SIGNER+"\",\"source_settings_schema\":4,\"payload_sha256\":\""+sha(PAYLOAD)+"\",\"payload\":"+PAYLOAD+"}";
    }
    private static RuntimeConfigBackupCodec.Backup backup(MirrorSettings settings,RuntimeConfigBackupCodec.AvatarReference avatar) {
        return new RuntimeConfigBackupCodec.Backup(ID,1700000000123L,18,SIGNER,4,settings,avatar);
    }
    private static RuntimeConfigBackupCodec.AvatarReference builtin() { return RuntimeConfigBackupCodec.AvatarReference.builtin(MODEL,MANIFEST); }
    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void rejects(Runnable operation) {
        try { operation.run();throw new AssertionError("Invalid backup accepted"); }
        catch(IllegalArgumentException expected) { checks++;check(!expected.getMessage().contains(SIGNER)&&!expected.getMessage().contains("raw_face"),"no arbitrary source echo"); }
    }
    private static void rejectText(String text) { rejects(()->RuntimeConfigBackupCodec.decode(utf8(text),SIGNER)); }
    private static void budget(String text) {
        try { RuntimeConfigBackupCodec.decode(utf8(text),SIGNER);throw new AssertionError("Budget accepted"); }
        catch(IllegalArgumentException expected) { check(expected.getMessage().contains("budget"),"bounded scanner runs before DOM"); }
    }
    private static String change(String text,String old,String value) { check(text.contains(old),"mutation has real target");return text.replace(old,value); }
    private static void equivalent(MirrorSettings a,MirrorSettings b) {
        check(a.toMap().equals(b.toMap()),"all19 setting values/type roundtrip");
        check(Float.floatToRawIntBits(a.panel.pitch())==Float.floatToRawIntBits(b.panel.pitch()),"pitch exact float bits");
        check(Float.floatToRawIntBits(a.panel.tan())==Float.floatToRawIntBits(b.panel.tan()),"tan exact float bits including negative zero");
        check(Float.floatToRawIntBits(a.panel.phaseCycles())==Float.floatToRawIntBits(b.panel.phaseCycles()),"canonical phase exact float bits");
    }
    public static void main(String[] args)throws Exception {
        MirrorSettings defaults=MirrorSettings.decode(Map.of());
        byte[] encoded=RuntimeConfigBackupCodec.encode(backup(defaults,builtin()));
        check(Arrays.equals(encoded,utf8(fixture())),"fixed independently hashed canonical fixture");
        RuntimeConfigBackupCodec.Backup decoded=RuntimeConfigBackupCodec.decode(utf8(fixture()),SIGNER);
        equivalent(defaults,decoded.settings);
        check(decoded.id.equals(ID)&&decoded.createdUnixMs==1700000000123L&&decoded.appVersionCode==18&&decoded.sourceSchema==4,"envelope exact data");
        check(decoded.avatar.source.equals("builtin")&&decoded.avatar.packageId.isEmpty()&&decoded.avatar.modelSha256.equals(MODEL)&&decoded.avatar.manifestSha256.equals(MANIFEST)&&decoded.avatar.bundleSha256.isEmpty(),"builtin reference only");
        check(Arrays.equals(encoded,RuntimeConfigBackupCodec.encode(decoded)),"canonical encode stable");
        String good=fixture();
        // org.json key iteration/number representation must not change the hash contract.
        JSONObject reordered=new JSONObject(good);
        RuntimeConfigBackupCodec.Backup re=RuntimeConfigBackupCodec.decode(utf8(" \n"+reordered.toString()+"\t"),SIGNER);
        equivalent(defaults,re.settings);
        rejects(()->RuntimeConfigBackupCodec.decode(encoded,MODEL));
        rejects(()->RuntimeConfigBackupCodec.decode(encoded,"not-a-hash"));
        for(String[] x:new String[][]{
            {"\"format_version\":1","\"format_version\":2"},{"\"source_settings_schema\":4","\"source_settings_schema\":5"},
            {"\"package\":\"com.mirror.bench\"","\"package\":\"other.app\""},{"mirror-runtime-config-backup","different-kind"},
            {ID,"../arbitrary"},{SIGNER,"A".repeat(64)},{MODEL,"x".repeat(64)},
            {"\"active_fps\":17","\"active_fps\":18"},{"\"view_count\":16","\"view_count\":17"},{"400x640","400x641"},
            {"\"width\":640","\"width\":641"},{"\"height\":480","\"height\":720"},{"\"rotation_degrees\":0","\"rotation_degrees\":45"},
            {"\"pitch\":10.0","\"pitch\":0.5"},{"\"tan\":0.2777777","\"tan\":4.1"},{"\"phase\":0.0","\"phase\":1.25"},
            {"SUBPIXELS","INCHES"},{"RGB","RBG"},{"BOTTOM","CENTER"},
            {"\"revision\":0","\"revision\":-1"},{"\"revision\":0","\"revision\":9223372036854775808"},
            {"\"created_unix_ms\":1700000000123","\"created_unix_ms\":0"},{"\"app_version_code\":18","\"app_version_code\":-1"},
            {"\"id\":\"\"","\"id\":\"0\""},{"\"fingerprint\":\"\"","\"fingerprint\":\"wrong\""},
            {"\"payload_sha256\":\""+sha(PAYLOAD),"\"payload_sha256\":\""+"e".repeat(64)},
            {"\"view_count\":16","\"view_count\":20"}
        })rejectText(change(good,x[0],x[1]));
        for(String key:new String[]{"format_version","source_settings_schema","created_unix_ms","app_version_code","active_fps","view_count","width","height","rotation_degrees","revision"}){
            String old="\""+key+"\":"+(key.equals("format_version")?"1":key.equals("source_settings_schema")?"4":key.equals("created_unix_ms")?"1700000000123":key.equals("app_version_code")?"18":key.equals("active_fps")?"17":key.equals("view_count")?"16":key.equals("width")?"640":key.equals("height")?"480":"0");
            String token=old.substring(old.indexOf(':')+1);
            for(String replacement:new String[]{"\""+token+"\"",token+".0",token+"e0","true","null","[]"})rejectText(change(good,old,"\""+key+"\":"+replacement));
        }
        for(String token:new String[]{"0","1","\"false\"","null"})rejectText(change(good,"\"reverse\":false","\"reverse\":"+token));
        for(String token:new String[]{"\"10\"","null","1e9999","1e-9999","NaN","Infinity"})rejectText(change(good,"\"pitch\":10.0","\"pitch\":"+token));
        for(String extra:new String[]{"raw_face","coefficients","model_bytes","path"})rejectText(change(good,"\"runtime\":{","\"runtime\":{\""+extra+"\":0,"));
        rejectText(change(good,"\"runtime\":{","\"runtime\":null,\"unknown\":{"));
        rejectText(change(good,"\"active_fps\":17","\"active_fps\":17,\"active_fps\":17"));
        rejectText(change(good,"\"active_fps\":17","\"active_fps\":17,\"active_\\u0066ps\":17"));
        rejectText(change(good,"\"backup_id\"","\"extra\":null,\"backup_id\""));
        for(String bad:new String[]{good+"{}",good.replace('"','\''),good.replace("\"view_count\":16","\"view_count\":016"),good.replace("\"view_count\":16","\"view_count\":+16"),good.replace("\"view_count\":16","view_count:16"),good.replace("\"view_count\":16","\"view_count\":16;"),good.replace("\"runtime\":{","\"runtime\":{/*comment*/"),good.substring(0,good.length()-1)+",}","\ufeff"+good})rejectText(bad);
        rejectText(change(good,"\"id\":\"\"","\"id\":\"\\uD800\""));
        rejectText(change(good,"\"id\":\"\"","\"id\":\"\\u0000\""));
        rejects(()->RuntimeConfigBackupCodec.decode(new byte[]{(byte)0xc0,(byte)0xaf},SIGNER));
        rejects(()->RuntimeConfigBackupCodec.decode(new byte[]{(byte)0xed,(byte)0xa0,(byte)0x80},SIGNER));
        rejects(()->RuntimeConfigBackupCodec.decode(null,SIGNER));
        byte[] exact=new byte[65536];Arrays.fill(exact,(byte)' ');System.arraycopy(encoded,0,exact,0,encoded.length);
        equivalent(defaults,RuntimeConfigBackupCodec.decode(exact,SIGNER).settings);
        rejects(()->RuntimeConfigBackupCodec.decode(Arrays.copyOf(exact,65537),SIGNER));
        budget("{\"extra\":"+"[".repeat(9)+"0"+"]".repeat(9)+"}");
        budget("{\"extra\":["+"0,".repeat(256)+"0]}");
        // Exact long and float32 preservation over every supported profile and signs/subnormals.
        float[] tans={-0f,0f,Float.MIN_VALUE,-Float.MIN_VALUE,-4f,4f,.2777777f};
        long[] revisions={0,9007199254740993L,Long.MAX_VALUE};
        for(int count:new int[]{16,20})for(int fps:MirrorSettings.ANALYSIS_RATES)for(String preset:MirrorSettings.VIEW_PRESETS)for(int i=0;i<tans.length;i++) {
            PanelCalibration p=new PanelCalibration(i==0?4096f:Float.intBitsToFloat(0x41200001),tans[i],PanelCalibration.PitchUnits.PIXELS,.875f,PanelCalibration.SubpixelOrder.BGR,true,PanelCalibration.YOrigin.TOP);
            CameraControlSettings camera=new CameraControlSettings("安装/相机😀",SIGNER,640,480,(i%4)*90,true,true,revisions[i%3]);
            MirrorSettings settings=defaults.withProfile(fps,preset,count).withPanel(p).withCamera(camera);
            var source=backup(settings,RuntimeConfigBackupCodec.AvatarReference.imported(AVATAR_ID,MODEL,MANIFEST,BUNDLE));
            byte[] raw=RuntimeConfigBackupCodec.encode(source);var result=RuntimeConfigBackupCodec.decode(raw,SIGNER);
            equivalent(settings,result.settings);
            check(result.settings.camera.revision==camera.revision,"int64 beyonddouble exact");
            check(result.avatar.packageId.equals(AVATAR_ID)&&result.avatar.bundleSha256.equals(BUNDLE),"imported exact reference");
            check(Arrays.equals(raw,RuntimeConfigBackupCodec.encode(result)),"all profile canonical bytes stable");
        }
        for(int schema=0;schema<=4;schema++) {
            var x=new RuntimeConfigBackupCodec.Backup(ID,Long.MAX_VALUE,Long.MAX_VALUE,SIGNER,schema,defaults,builtin());
            var y=RuntimeConfigBackupCodec.decode(RuntimeConfigBackupCodec.encode(x),SIGNER);
            check(y.sourceSchema==schema&&y.createdUnixMs==Long.MAX_VALUE&&y.appVersionCode==Long.MAX_VALUE,"source/evidence exact int64");
        }
        for(MirrorSettings invalid:new MirrorSettings[]{MirrorSettings.decode(Map.of("schema_version",5)),MirrorSettings.decode(Map.of("schema_version",4)),MirrorSettings.decode(Map.of("active_fps",99))})rejects(()->RuntimeConfigBackupCodec.encode(backup(invalid,builtin())));
        rejects(()->RuntimeConfigBackupCodec.encode(backup(defaults,RuntimeConfigBackupCodec.AvatarReference.imported("../bad",MODEL,MANIFEST,BUNDLE))));
        rejects(()->RuntimeConfigBackupCodec.encode(backup(defaults,RuntimeConfigBackupCodec.AvatarReference.builtin("bad",MANIFEST))));
        rejects(()->RuntimeConfigBackupCodec.encode(backup(defaults.withCamera(new CameraControlSettings("0","wrong",640,480,0,false,false,0)),builtin())));
        rejects(()->RuntimeConfigBackupCodec.encode(backup(defaults.withCamera(new CameraControlSettings("0",SIGNER,640,720,0,false,false,0)),builtin())));
        rejects(()->RuntimeConfigBackupCodec.encode(null));
        System.out.println("RuntimeConfigBackupCodecTest: "+checks+" checks passed (pure host codec; no storage/UI/restore/device)");
    }
}
