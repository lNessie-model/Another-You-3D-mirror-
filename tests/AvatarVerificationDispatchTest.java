package com.mirror.bench;

import android.os.Bundle;
import java.lang.reflect.*;
import java.nio.file.*;
import org.json.JSONObject;

/** Real Activity dispatch/publication with explicit host substitutes; never calls GL. */
public final class AvatarVerificationDispatchTest {
    private static int checks;
    private static sun.misc.Unsafe unsafe;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    private static void set(Object value,String name,Object data)throws Exception {
        Field f=AvatarPreviewActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(value,data);
    }
    private static Object invoke(Object value,String name,Class<?>[] types,Object...args)throws Exception {
        Method m=AvatarPreviewActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(value,args);
    }
    private static Object call(Object value,String name)throws Exception{return invoke(value,name,new Class<?>[0]);}
    private static AvatarPreviewActivity activity(String route)throws Exception {
        AvatarPreviewActivity a=(AvatarPreviewActivity)unsafe.allocateInstance(AvatarPreviewActivity.class);
        set(a,"verificationViewCount",20);set(a,"verificationViewHeight",720);set(a,"error","");set(a,"runId","fixture-run");
        if(!route.equals("none"))set(a,route,true);
        return a;
    }
    private static void configure(AvatarPreviewActivity a,Bundle extras,boolean debug)throws Exception {
        invoke(a,"readVerificationViewCount",new Class<?>[]{Bundle.class,boolean.class},extras,debug);
    }
    private static JSONObject read(Path path)throws Exception{return new JSONObject(Files.readString(path));}
    private static void rejected(AvatarPreviewActivity a,Bundle extras,boolean debug)throws Exception {
        try{configure(a,extras,debug);throw new AssertionError("invalid override accepted");}
        catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalArgumentException);}
    }
    public static void main(String[] args)throws Exception {
        Field u=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");u.setAccessible(true);unsafe=(sun.misc.Unsafe)u.get(null);
        Path directory=Path.of(args[0]);Files.createDirectories(directory);android.app.Activity.files=directory.toFile();
        for(String route:new String[]{"verifyPersistentFbos","verifyCachedCameraVp"}) {
            for(Integer requested:new Integer[]{null,16,20}) {
                var a=activity(route);Bundle extras=new Bundle();if(requested!=null)extras.putInt("test_view_count",requested);
                configure(a,extras,true);int views=requested==null?20:requested;
                String stem=route.equals("verifyPersistentFbos")?"avatar-persistent-fbo":"avatar-camera-vp";
                String filename=stem+(views==16?"16":"")+"-check.json";
                check(call(a,"verificationFile").equals(filename));
                check(((String)call(a,"verificationProgressMessage")).contains(views+"视点"));
                check(((String)invoke(a,"verificationFinishedMessage",new Class<?>[]{boolean.class},true)).contains(views+"视点"));
                check(((String)invoke(a,"verificationFinishedMessage",new Class<?>[]{boolean.class},false)).contains(views+"视点"));
                Path report=directory.resolve(filename);Path legacy=directory.resolve(stem+"-check.json");
                Path other=directory.resolve(route.equals("verifyPersistentFbos")?"avatar-camera-vp16-check.json":"avatar-persistent-fbo16-check.json");
                Files.writeString(other,"other route sentinel");
                if(views==16)Files.writeString(legacy,"legacy sentinel");
                android.app.Activity.intent=new android.content.Intent(null,AvatarPreviewActivity.class);
                if(requested!=null)android.app.Activity.intent.putExtra("test_view_count",requested);
                android.app.Activity.info=(android.content.pm.ApplicationInfo)unsafe.allocateInstance(android.content.pm.ApplicationInfo.class);
                android.app.Activity.info.flags=android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE;
                AvatarGpuScene.failure=null;
                AvatarGpuScene.created=()->{try{JSONObject running=read(report);check(running.getBoolean("running"));check(!running.getBoolean("passed"));check(running.getInt("requested_view_count")==views);}catch(Exception e){throw new AssertionError(e);}};
                a.onSurfaceCreated(null,null);AvatarGpuScene.created=null;
                call(a,"runVerification");JSONObject result=read(report);
                check(VerificationCalls.views==views);check(VerificationCalls.width==400&&VerificationCalls.height==720);
                check(VerificationCalls.route.equals(route.equals("verifyPersistentFbos")?"persistent":"cached"));
                check(VerificationCalls.aspect==1200f/1920f);
                if(route.equals("verifyCachedCameraVp"))check(VerificationCalls.physicalWidth==1200&&VerificationCalls.physicalHeight==1920);
                check(!result.getBoolean("running")&&result.getBoolean("passed"));check(result.getInt("requested_view_count")==views);
                if(views==16)check(Files.readString(legacy).equals("legacy sentinel"));
                check(Files.readString(other).equals("other route sentinel"));
                AvatarGpuScene.failure=new IllegalStateException("fixture scene load failure");a.onSurfaceCreated(null,null);
                result=read(report);check(!result.getBoolean("running")&&!result.getBoolean("passed"));check(result.getString("error").contains("fixture scene"));
                if(views==16)check(Files.readString(legacy).equals("legacy sentinel"));
                check(Files.readString(other).equals("other route sentinel"));
                AvatarGpuScene.failure=null;
                VerificationCalls.during=()->{try{set(a,"cancelled",true);}catch(Exception e){throw new AssertionError(e);}};
                call(a,"runVerification");VerificationCalls.during=null;result=read(report);
                check(result.getBoolean("cancelled")&&!result.getBoolean("passed"));check(VerificationCalls.cancellation.getAsBoolean());
                check(Files.readString(other).equals("other route sentinel"));
            }
            var a=activity(route);Bundle b=new Bundle();
            for(int n:new int[]{16,20}){b.putInt("test_view_count",n);rejected(a,b,false);}
            for(int n:new int[]{0,15,17,21,Integer.MAX_VALUE}){b.putInt("test_view_count",n);rejected(a,b,true);}
            b.putString("test_view_count","16");rejected(a,b,true);b.putString("test_view_count",null);rejected(a,b,true);
            b.putLong("test_view_count",16L);rejected(a,b,true);b.putBoolean("test_view_count",true);rejected(a,b,true);
            b.putFloat("test_view_count",16f);rejected(a,b,true);
            configure(a,null,false);check(call(a,"verificationViews").equals(20));
        }
        for(String route:new String[]{"verifyMultiview16","verifyBatch","verifyMultiview","none"}){
            var a=activity(route);Bundle b=new Bundle();b.putInt("test_view_count",16);configure(a,b,false);
            check(call(a,"verificationViews").equals(route.equals("verifyMultiview16")?16:20));
            if(!route.equals("none")){call(a,"runVerification");check(VerificationCalls.views==(route.equals("verifyMultiview16")?16:20));}
            if(route.equals("verifyMultiview16")){b.putInt("test_view_count",20);configure(a,b,true);check(call(a,"verificationViews").equals(16));check(call(a,"verificationFile").equals("avatar-multiview16-check.json"));}
            if(route.equals("verifyBatch"))check(call(a,"verificationFile").equals("avatar-batch-check.json"));
            if(route.equals("verifyMultiview"))check(call(a,"verificationFile").equals("avatar-multiview-check.json"));
        }
        // A rejected ambiguous 16-view request must not overwrite a completed legacy 20 report.
        var ambiguous=activity("verifyPersistentFbos");set(ambiguous,"verifyCachedCameraVp",true);
        android.app.Activity.intent=new android.content.Intent(null,AvatarPreviewActivity.class).putExtra("test_view_count",16);
        Path oldTwenty=directory.resolve("avatar-camera-vp-check.json");Files.writeString(oldTwenty,"legacy ambiguous sentinel");
        ambiguous.onSurfaceCreated(null,null);
        check(Files.readString(oldTwenty).equals("legacy ambiguous sentinel"));
        JSONObject failure=read(directory.resolve("avatar-camera-vp16-check.json"));
        check(!failure.getBoolean("passed")&&!failure.getBoolean("running"));
        check(failure.getInt("requested_view_count")==16);check(failure.getString("error").contains("Select one"));
        for(String route:new String[]{"verifyMultiview16","verifyPersistentFbos","verifyCachedCameraVp"}){
            var a=activity(route);Bundle b=new Bundle();b.putInt("test_view_count",16);b.putString("test_view_preset","400x640");
            invoke(a,"readVerificationConfiguration",new Class<?>[]{Bundle.class,boolean.class},b,true);
            String stem=route.equals("verifyMultiview16")?"avatar-multiview16":route.equals("verifyPersistentFbos")?"avatar-persistent-fbo16":"avatar-camera-vp16";
            Path old=directory.resolve(stem+"-check.json"),report=directory.resolve(stem+"-400640-check.json");Files.writeString(old,"old 720 sentinel");
            check(call(a,"verificationFile").equals(report.getFileName().toString()));
            android.app.Activity.intent=new android.content.Intent(null,AvatarPreviewActivity.class).putExtra("test_view_count",16).putExtra("test_view_preset","400x640");
            AvatarGpuScene.failure=null;a.onSurfaceCreated(null,null);JSONObject data=read(report);
            check(data.getBoolean("running")&&data.getInt("requested_view_width")==400&&data.getInt("requested_view_height")==640);
            call(a,"runVerification");data=read(report);
            check(VerificationCalls.views==16&&VerificationCalls.width==400&&VerificationCalls.height==640);
            check(data.getBoolean("passed")&&!data.getBoolean("running")&&data.getInt("requested_view_height")==640);
            check(((String)call(a,"verificationProgressMessage")).contains("400×640"));
            AvatarGpuScene.failure=new IllegalStateException("640 fixture failure");a.onSurfaceCreated(null,null);
            check(!read(report).getBoolean("passed")&&read(report).getString("error").contains("640 fixture"));AvatarGpuScene.failure=null;
            VerificationCalls.during=()->{try{set(a,"cancelled",true);}catch(Exception e){throw new AssertionError(e);}};
            call(a,"runVerification");VerificationCalls.during=null;
            check(read(report).getBoolean("cancelled")&&!read(report).getBoolean("passed"));
            check(Files.readString(old).equals("old 720 sentinel"));
            for(Object bad:new Object[]{null,640,true,"400x641","320x576"}){
                Bundle invalid=new Bundle();invalid.putInt("test_view_count",16);
                if(bad instanceof Integer)invalid.putInt("test_view_preset",(Integer)bad);
                else if(bad instanceof Boolean)invalid.putBoolean("test_view_preset",(Boolean)bad);
                else invalid.putString("test_view_preset",(String)bad);
                try{invoke(a,"readVerificationConfiguration",new Class<?>[]{Bundle.class,boolean.class},invalid,true);throw new AssertionError("bad preset accepted");}
                catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalArgumentException);}
            }
            try{invoke(a,"readVerificationConfiguration",new Class<?>[]{Bundle.class,boolean.class},b,false);throw new AssertionError("non-debug preset accepted");}
            catch(InvocationTargetException expected){check(expected.getCause() instanceof IllegalArgumentException);}
        }
        for(Object bad:new Object[]{"400x641",null,640}){
            var a=activity("verifyPersistentFbos");
            android.content.Intent invalid=new android.content.Intent(null,AvatarPreviewActivity.class).putExtra("test_view_count",16);
            if(bad instanceof Integer)invalid.putExtra("test_view_preset",(Integer)bad);else invalid.putExtra("test_view_preset",(String)bad);
            android.app.Activity.intent=invalid;
            Path old16=directory.resolve("avatar-persistent-fbo16-check.json"),old20=directory.resolve("avatar-persistent-fbo-check.json");
            Files.writeString(old16,"valid 16 720 sentinel");Files.writeString(old20,"valid 20 720 sentinel");
            a.onSurfaceCreated(null,null);
            check(Files.readString(old16).equals("valid 16 720 sentinel")&&Files.readString(old20).equals("valid 20 720 sentinel"));
            JSONObject invalidReport=read(directory.resolve("avatar-verification-config-error.json"));
            check(!invalidReport.getBoolean("passed")&&!invalidReport.getBoolean("running"));
            check(!invalidReport.has("requested_view_count")&&!invalidReport.has("requested_view_height"));
            check(invalidReport.getString("error").contains("preset requires"));
        }
        var wrongCount=activity("verifyPersistentFbos");
        android.app.Activity.intent=new android.content.Intent(null,AvatarPreviewActivity.class).putExtra("test_view_count",20).putExtra("test_view_preset","400x640");
        Path twenty=directory.resolve("avatar-persistent-fbo-check.json");Files.writeString(twenty,"old 20 sentinel");
        wrongCount.onSurfaceCreated(null,null);
        JSONObject wrong=read(directory.resolve("avatar-verification-config-error.json"));
        check(!wrong.getBoolean("passed")&&wrong.getString("error").contains("sixteen views"));
        check(Files.readString(twenty).equals("old 20 sentinel"));
        System.out.println("AvatarVerificationDispatchTest: "+checks+" checks passed; SDK linkage and host dispatch/file I/O only, no GL/pixel claim");
    }
}
