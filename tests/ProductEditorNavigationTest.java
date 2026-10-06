package com.mirror.bench;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import java.lang.reflect.*;
import java.util.*;

/** Calls actual compiled MirrorActivity editor entry/Host callbacks; no Android dialog/GL/HAL claim. */
public final class ProductEditorNavigationTest {
    private static int checks;
    private static final sun.misc.Unsafe UNSAFE;
    static {
        try{Field f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);UNSAFE=(sun.misc.Unsafe)f.get(null);}
        catch(Exception error){throw new ExceptionInInitializerError(error);}
    }
    private static void check(boolean yes,String label){checks++;if(!yes)throw new AssertionError(label);}
    private static void set(Object value,String name,Object content)throws Exception{
        Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);f.set(value,content);
    }
    private static Object get(Object value,String name)throws Exception{
        Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    private static void call(Object value,String name)throws Exception{
        Method m=value.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(value);
    }
    private static MirrorActivity activity(boolean fromSettings)throws Exception{
        MirrorActivity a=(MirrorActivity)UNSAFE.allocateInstance(MirrorActivity.class);
        ((Activity)a).hostIntent=new Intent(null,MirrorActivity.class).putExtra(MirrorSettingsActivity.RETURN_TO_SETTINGS,fromSettings);
        set(a,"resumed",true);set(a,"sceneViewSettings",SceneViewSettings.DEFAULT);
        Class<?> options=Class.forName("com.mirror.bench.MirrorActivity$InputOptions");
        Method read=options.getDeclaredMethod("read",android.os.Bundle.class,boolean.class);read.setAccessible(true);
        set(a,"options",read.invoke(null,null,true));
        set(a,"renderer",new InterlaceRenderer(16,.3f,true));
        set(a,"settings",MirrorSettings.decode(Map.of()));
        set(a,"requestedCamera",((MirrorSettings)get(a,"settings")).camera);
        set(a,"interaction",new InteractionController());
        MirrorStartupGate gate=new MirrorStartupGate();gate.resume();gate.markDiagnosticReady();set(a,"avatarStartup",gate);
        set(a,"surface",UNSAFE.allocateInstance(GLSurfaceView.class));set(a,"main",new Handler());
        // This UI-boundary fixture intentionally forbids starting real native workers.
        set(a,"maintenance",true);
        return a;
    }
    private static void frameReady(MirrorActivity a)throws Exception{
        InterlaceRenderer r=(InterlaceRenderer)get(a,"renderer");
        RuntimeGlLifecycle gl=(RuntimeGlLifecycle)get(r,"runtimeGlLifecycle");gl.contextCreated();gl.successfulFrame(gl.frameEpoch());
    }
    private static CameraCalibrationPanel openCamera(MirrorActivity a)throws Exception{
        frameReady(a);call(a,"showCameraCalibration");return (CameraCalibrationPanel)get(a,"cameraPanel");
    }
    private static int resumes(MirrorActivity a)throws Exception{return ((GLSurfaceView)get(a,"surface")).resumes;}
    private static long rendererResumes(MirrorActivity a)throws Exception{return ((Number)get(get(a,"renderer"),"avatarResumeGeneration")).longValue();}
    public static void main(String[] args)throws Exception{
        MirrorActivity settings=activity(true);call(settings,"showSceneView");
        SceneViewPanel.Host closed=SceneViewPanel.last.host;closed.closed();
        check(((Activity)settings).finishes==1,"settings-origin scene close must finish directly to settings");
        check(get(settings,"sceneViewPanel")==null,"closed scene ownership released");
        closed.closed();check(((Activity)settings).finishes==1,"duplicate scene close must not navigate twice");
        MirrorActivity runtime=activity(false);call(runtime,"showSceneView");SceneViewPanel.last.host.closed();
        check(((Activity)runtime).finishes==0,"runtime-origin scene close stays in runtime");
        for(String state:new String[]{"paused","finishing","destroyed"}){
            MirrorActivity a=activity(true);call(a,"showSceneView");
            if(state.equals("paused"))set(a,"resumed",false);
            if(state.equals("finishing"))((Activity)a).hostFinishing=true;
            if(state.equals("destroyed"))((Activity)a).hostDestroyed=true;
            SceneViewPanel.last.host.closed();
            check(((Activity)a).finishes==0,state+" scene close must not finish/restart");
        }
        cameraCloseRoutes();pendingCameraRequest();pausedDismiss();saveFailureGuards();
        System.out.println("ProductEditorNavigationTest: "+checks+" checks passed; real activity callbacks, UI boundary only");
    }
    private static void cameraCloseRoutes()throws Exception{
        for(boolean origin:new boolean[]{true,false})for(boolean saved:new boolean[]{true,false}){
            MirrorActivity a=activity(origin);CameraControlSettings original=(CameraControlSettings)get(a,"requestedCamera");
            CameraCalibrationPanel p=openCamera(a);check(p!=null,"camera editor opened with real first-frame prerequisite");
            CameraControlSettings draft=original.withMirror(true,original.revision+1);
            set(a,"requestedCamera",draft);long generation=rendererResumes(a);
            p.host.closed(p,saved,false);
            check(((Activity)a).finishes==(origin?1:0),"camera close returns to source for saved/cancelled");
            check(resumes(a)==(origin?0:1),"settings camera exit never resumes GL; runtime exit does");
            check(rendererResumes(a)==generation+(origin?0:1),"settings camera exit never resumes pose renderer");
            check(get(a,"requestedCamera")== (saved?draft:original),"camera cancel restores original; saved retains validated draft");
            check(get(a,"cameraPanel")==null,"camera ownership released on close");
            p.host.closed(p,saved,false);
            check(((Activity)a).finishes==(origin?1:0)&&resumes(a)==(origin?0:1),"duplicate camera close inert");
        }
        for(String state:new String[]{"paused","finishing","destroyed"})for(boolean origin:new boolean[]{true,false}){
            MirrorActivity a=activity(origin);CameraCalibrationPanel p=openCamera(a);long before=rendererResumes(a);
            if(state.equals("paused"))set(a,"resumed",false);
            if(state.equals("finishing"))((Activity)a).hostFinishing=true;
            if(state.equals("destroyed"))((Activity)a).hostDestroyed=true;
            p.host.closed(p,false,false);
            check(((Activity)a).finishes==0&&resumes(a)==0&&rendererResumes(a)==before,state+" camera close neither finishes nor resumes");
        }
        MirrorActivity a=activity(false);CameraCalibrationPanel old=openCamera(a);old.host.closed(old,false,false);
        frameReady(a);call(a,"showCameraCalibration");CameraCalibrationPanel active=(CameraCalibrationPanel)get(a,"cameraPanel");
        old.host.closed(old,true,false);check(get(a,"cameraPanel")==active,"stale old close cannot remove a newer camera panel");
    }
    private static void pendingCameraRequest()throws Exception{
        MirrorActivity a=activity(true);((Activity)a).hostIntent.putExtra(MirrorHomeActivity.PRODUCT_ACTION,"camera");
        MirrorStartupGate gate=new MirrorStartupGate();gate.resume();set(a,"avatarStartup",gate);
        call(a,"showRequestedProductPage");check(!(Boolean)get(a,"productActionConsumed"),"camera request retained while role metadata pending");
        gate.markDiagnosticReady();call(a,"showRequestedProductPage");
        check(!(Boolean)get(a,"productActionConsumed")&&get(a,"cameraPanel")==null,"camera request retained until actual first successful GL frame");
        set(a,"resumed",false);frameReady(a);call(a,"showRequestedProductPage");
        check(!(Boolean)get(a,"productActionConsumed"),"paused camera request not consumed");set(a,"resumed",true);
        ((Activity)a).hostFinishing=true;call(a,"showRequestedProductPage");check(!(Boolean)get(a,"productActionConsumed"),"finishing camera request not consumed");
        ((Activity)a).hostFinishing=false;((Activity)a).hostDestroyed=true;call(a,"showRequestedProductPage");check(!(Boolean)get(a,"productActionConsumed"),"destroyed camera request not consumed");
        ((Activity)a).hostDestroyed=false;int before=CameraCalibrationPanel.opened;call(a,"showRequestedProductPage");
        check((Boolean)get(a,"productActionConsumed")&&get(a,"cameraPanel")!=null,"ready camera request consumed exactly when opening");
        call(a,"showRequestedProductPage");check(CameraCalibrationPanel.opened==before+1,"ready request opens only once");
    }
    private static void pausedDismiss()throws Exception{
        MirrorActivity scene=activity(true);call(scene,"showSceneView");call(scene,"onPause");
        check(!(Boolean)get(scene,"resumed")&&((Activity)scene).finishes==0&&get(scene,"sceneViewPanel")==null,"real onPause scene dismissal never finishes");
        MirrorActivity camera=activity(true);openCamera(camera);long before=rendererResumes(camera);call(camera,"onPause");
        check(!(Boolean)get(camera,"resumed")&&((Activity)camera).finishes==0&&get(camera,"cameraPanel")==null,"real onPause camera dismissal never finishes");
        check(resumes(camera)==0&&rendererResumes(camera)==before,"real onPause camera dismissal never resumes GL/worker");
    }
    private static void saveFailureGuards()throws Exception{
        MirrorActivity a=activity(true);CameraCalibrationPanel p=openCamera(a);
        CameraControlSettings original=(CameraControlSettings)get(a,"requestedCamera");
        CameraCalibrationPanel.Input input=new CameraCalibrationPanel.Input(original,"");
        try{p.host.save(p,input,original);throw new AssertionError("missing live input accepted");}
        catch(IllegalStateException expected){checks++;}
        check(((Activity)a).finishes==0&&get(a,"cameraPanel")==p,"failed input validation keeps editor open");
        Class<?> type=Class.forName("com.mirror.bench.MirrorActivity$RuntimeWorker");
        Object worker=UNSAFE.allocateInstance(type);set(a,"worker",worker);set(a,"cameraInput",input);
        Map<String,Object> stored=new HashMap<>(((MirrorSettings)get(a,"settings")).toMap());
        Activity.preferences=(SharedPreferences)Proxy.newProxyInstance(ProductEditorNavigationTest.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,m,values)->{
            if(m.getName().equals("getAll"))return new HashMap<>(stored);
            if(m.getName().equals("edit"))return Proxy.newProxyInstance(ProductEditorNavigationTest.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(editor,op,args)->{
                if(op.getName().startsWith("put"))return editor;
                if(op.getName().equals("commit"))return false;
                throw new AssertionError(op.getName());
            });
            throw new AssertionError(m.getName());
        });
        CameraControlSettings draft=original.withMirror(true,original.revision+1);
        try{p.host.save(p,input,draft);throw new AssertionError("failed disk commit accepted");}
        catch(IllegalStateException expected){checks++;}
        check(get(a,"requestedCamera")==original&&get(a,"cameraPanel")==p&&((Activity)a).finishes==0,"failed save leaves original camera/editor/navigation intact");
        set(worker,"cancelled",true);
        try{p.host.save(p,input,draft);throw new AssertionError("cancelled worker accepted");}
        catch(IllegalStateException expected){checks++;}
        set(a,"worker",null);
    }
}
