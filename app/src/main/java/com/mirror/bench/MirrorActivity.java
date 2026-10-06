package com.mirror.bench;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.provider.Settings;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ScrollView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import org.json.JSONArray;
import org.json.JSONObject;

/** Visible, offline interaction entry point with the replaceable built-in GLB character. */
public final class MirrorActivity extends Activity {
    private static final int CAMERA_PERMISSION = 42;
    private static final int PANEL_CALIBRATION = 43;
    private static final int AVATAR_MANAGEMENT = 44;
    private static final int WIDTH = 640, HEIGHT = 480, CAPTURE_FPS = 25;
    private static final int[] RETRY_SECONDS = {1, 2, 4, 8, 30};
    // Also serializes native resources across Activity recreation; never acquired on the UI thread.
    private static final Semaphore HARDWARE_OWNER = new Semaphore(1);
    private static final RuntimeInputStop.FailureLatch INPUT_STOP_FAILURES = new RuntimeInputStop.FailureLatch();
    private static final Object STATUS_FILE_LOCK = new Object();
    private static final RuntimeStatusOrder STATUS_ORDER = new RuntimeStatusOrder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final InteractionController interaction = new InteractionController();
    private final String activityInstanceId=java.util.UUID.randomUUID().toString();
    private boolean preserveEglOnPauseActual;
    private final ArrayDeque<JSONObject> events = new ArrayDeque<>();
    private GLSurfaceView surface;
    private FrameLayout runtimeRoot;
    private InterlaceRenderer renderer;
    private MirrorRuntimeControls runtimeControls;
    private Dialog productSettings;
    private boolean productActionConsumed;
    private final MirrorStartupGate avatarStartup=new MirrorStartupGate();
    private AvatarChoice pendingAvatarChoice;
    private BundledAvatarCatalog.Entry configuredBundledEntry;
    private record AvatarChoice(BundledAvatarCatalog.Entry entry,boolean explicit,String warning) {}
    private volatile MirrorSettings settings;
    private SceneViewSettings sceneViewSettings=SceneViewSettings.DEFAULT;
    private SceneViewPanel sceneViewPanel;
    private volatile CameraControlSettings requestedCamera;
    private volatile CameraCalibrationPanel cameraPanel;
    private volatile CameraCalibrationPanel.Input cameraInput;
    private volatile CameraObservation cameraObservation;
    private record CameraObservation(CameraCalibrationPanel.Input input,FaceFrame frame) {}
    private int runtimeViewWidth,runtimeViewHeight;
    private InputOptions options;
    private RuntimeWorker worker;
    private volatile InputStopRecord lastInputStop;
    private record InputStopRecord(Thread worker,RuntimeInputStop.Receipt receipt) {}
    private boolean resumed, maintenance, permissionPrompted, restarting, calibrationOpen;
    private volatile InteractionController.Snapshot snapshot;
    private volatile String runtimeError = "";
    private volatile boolean renderFault;
    private volatile String renderError = "";
    private volatile RuntimeProgressWatchdog.Fault progressFault;
    private String configurationError = "";
    private String reportedPreflightError = "";
    private volatile long recoveries;
    private volatile Stats stats;
    private volatile RuntimeStatusOrder.Epoch statusEpoch;
    private long lastUiTextNs;
    private InteractionController.State lastEventState;

    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        settings = MirrorSettings.load(this);
        sceneViewSettings=SceneViewPreferences.load(this).value;
        requestedCamera=settings.camera;
        permissionPrompted = savedState != null && savedState.getBoolean("permission_prompted", false);
        productActionConsumed=savedState!=null&&savedState.getBoolean("product_action_consumed",false);
        try { options = InputOptions.read(getIntent().getExtras(),
                (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0,settings.viewCount); }
        catch (IllegalArgumentException error) {
            configurationError = error.getMessage();
            options = InputOptions.defaults(settings.viewCount);
            interaction.setError();
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        renderer = createRuntimeRenderer();
        renderer.setPipeline(true);
        renderer.setCull(true, false);
        renderer.setPreblend(true, false);
        renderer.setMultiview(true, false);
        renderer.setExplicitLod(true);
        renderer.setDiscardDepth(true, false);
        renderer.setRuntimeAvatar(getAssets(),new File(getFilesDir(),"avatars"),android.os.Build.VERSION.SDK_INT);
        if(options.privateHead)renderer.setPrivateHeadDiagnostic(getFilesDir());
        renderer.setAvatarAsynchronous(!options.synchronousAvatar);
        renderer.setAvatarBatched(options.batchedAvatar);
        renderer.setPersistentMultiviewFbos(options.persistentFbos);
        renderer.setCachedCameraVp(options.cachedCameraVp);
        renderer.setGpuProfile(options.gpuProfile);
        renderer.setOrmRg8(options.ormRg8);
        renderer.setStaticBackgroundCache(options.staticBackgroundCache);
        surface = new GLSurfaceView(this);
        surface.setEGLContextClientVersion(3);
        surface.setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        surface.setPreserveEGLContextOnPause(!options.releaseGlOnPause);
        preserveEglOnPauseActual=surface.getPreserveEGLContextOnPause();
        runtimeRoot = new FrameLayout(this);
        runtimeControls=new MirrorRuntimeControls(this,new MirrorRuntimeControls.Host(){
            public void roles(){openRoles();}
            public void scene(){showSceneView();}
            public void camera(){showCameraCalibration();}
            public void settings(){showProductSettings();}
            public void home(){returnHome();}
            public void recover(MirrorUiState.Action action){if(action==MirrorUiState.Action.HOME)returnHome();else retryRuntime();}
        });
        runtimeRoot.addView(runtimeControls.view(),new FrameLayout.LayoutParams(-1,-1));
        surface.setOnClickListener(v->runtimeControls.showMenu());
        setContentView(runtimeRoot);
        if(options.privateHead){attachConfiguredSurface();avatarStartup.markDiagnosticReady();}
    }

    private void prepareBundledSelection(){
        if(isFinishing()||isDestroyed())return;
        final int token=avatarStartup.beginRead();if(token<0)return;
        // Re-read after a pause: a role may have been changed in the management page.
        pendingAvatarChoice=null;
        new Thread(()->{
            BundledAvatarCatalog.Entry entry=null;boolean explicit=false;String failure="";
            try{
                var catalog=BundledAvatarCatalog.read(getAssets());
                entry=BundledAvatarCatalog.find(catalog,BundledAvatarSelection.load(this));
                explicit=BundledAvatarSelection.hasSelection(this);
            }catch(Exception error){failure="所选角色无法读取，请返回角色页重新选择。";Log.e("MirrorRuntime","Bundled selection unavailable",error);}
            final var choice=new AvatarChoice(entry,explicit,failure);
            main.post(()->{
                if(isFinishing()||isDestroyed())return;
                pendingAvatarChoice=choice;
                switch(avatarStartup.complete(token)){
                    case DEFER -> { return; }
                    case REREAD -> {prepareBundledSelection();return;}
                    case IGNORE -> {pendingAvatarChoice=null;return;}
                    case APPLY -> {pendingAvatarChoice=null;finishAvatarSelection(choice,token);}
                }
            });
        },"MirrorRoleMetadata").start();
    }
    private void finishAvatarSelection(AvatarChoice choice,int token){
        if(!avatarStartup.canApply(token)||isFinishing()||isDestroyed())return;
        var entry=choice.entry;
        if(!choice.warning.isEmpty()){
            configurationError=choice.warning;runtimeError=choice.warning;interaction.setError();runtimeControls.showMenu();return;
        }
        if(entry!=null){renderer.setBundledRuntimeAvatar(getAssets(),entry.directory,entry.id,entry.modelSha256,entry.manifestSha256,choice.explicit);configuredBundledEntry=entry;}
        attachConfiguredSurface();avatarStartup.markReady(token);
        renderer.resumeRuntimeAvatar();surface.onResume();startIfReady(true);
    }
    private void attachConfiguredSurface(){
        // Register the GL thread before attaching: an already-created holder would miss
        // its initial surfaceCreated callback if setRenderer were delayed until afterward.
        surface.setRenderer(renderer);
        runtimeRoot.addView(surface,0,new FrameLayout.LayoutParams(-1,-1));
    }

    private InterlaceRenderer createRuntimeRenderer() {
        InterlaceRenderer configured = new InterlaceRenderer(options.viewCount, .3f, true);
        configured.setRuntimeMode(true);
        configured.setTargetFps(options.activeTargetFps);
        if(options.viewPreset==null) {runtimeViewWidth=settings.viewWidth;runtimeViewHeight=settings.viewHeight;}
        else {
            String[] size=options.viewPreset.split("x");
            runtimeViewWidth=Integer.parseInt(size[0]);runtimeViewHeight=Integer.parseInt(size[1]);
        }
        configured.setViewSize(runtimeViewWidth,runtimeViewHeight);
        configured.setPanelCalibration(settings.panel);
        configured.setSceneView(sceneViewSettings);
        return configured;
    }
    private void showSceneView(){
        if(sceneViewPanel!=null)return;
        sceneViewPanel=new SceneViewPanel(this,sceneViewSettings,new SceneViewPanel.Host(){
            public void preview(SceneViewSettings value){sceneViewSettings=value;renderer.setSceneView(value);}
            public void closed(){sceneViewPanel=null;}
        },options.viewCount);sceneViewPanel.show();
    }
    private void saveRuntimeProfile(int fps,String preset,int views) {
        boolean changed=fps!=settings.activeFps||!preset.equals(settings.viewPreset)||views!=settings.viewCount;
        MirrorSettings.save(this,fps,preset,views);
        // Leave the current settings/options/renderer intact until recreation after successful commit.
        if(changed){restarting=true;recreate();}
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true;avatarStartup.resume();renderer.resumeRuntimeAvatar();
        beginStatusSession();
        if(avatarStartup.ready())surface.onResume();
        else prepareBundledSelection();
        main.removeCallbacks(tick); main.post(tick);
        startIfReady(true);
        main.post(()->{
            if(!resumed||productActionConsumed)return;
            productActionConsumed=true;
            String action=getIntent().getStringExtra(MirrorHomeActivity.PRODUCT_ACTION);
            if("scene".equals(action))showSceneView();
            else if("settings".equals(action))showProductSettings();
        });
    }
    @Override protected void onPause() {
        resumed = false;
        avatarStartup.pause();
        if(sceneViewPanel!=null)sceneViewPanel.dismiss();
        if(productSettings!=null)productSettings.dismiss();
        renderer.pauseRuntimeAvatar();
        if(cameraPanel!=null)cameraPanel.dismiss();
        main.removeCallbacks(tick);
        cancelWorker();
        renderer.setInteractiveFace(new float[4], FaceFrame.identity(), false);
        if(avatarStartup.ready())surface.onPause();
        super.onPause();
    }
    @Override protected void onDestroy() {
        avatarStartup.destroy();pendingAvatarChoice=null;main.removeCallbacks(tick); cancelWorker(); renderer.closeRuntimeAvatar(); super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("permission_prompted", permissionPrompted);
        state.putBoolean("product_action_consumed",productActionConsumed);
        super.onSaveInstanceState(state);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CAMERA_PERMISSION) {
            if (hasCameraPermission() && !renderFault && progressFault == null) { runtimeError = ""; interaction.clearError(); startIfReady(false); }
            else if (renderFault || progressFault != null) { interaction.setError(); }
            else { runtimeError = "摄像头未授权，请在维护页面重试"; interaction.setError(); }
        }
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==AVATAR_MANAGEMENT) {
            // A commit may finish just as the manager pauses, before its success
            // callback. Reload the authoritative selection even after Back/cancel.
            calibrationOpen=false;restarting=true;recreate();
        } else if(requestCode==PANEL_CALIBRATION) {
            calibrationOpen=false;
            if(resultCode==RESULT_OK) {restarting=true;recreate();}
        }
    }

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }
    /** Register/prepare in memory on UI; filesystem publication is asynchronous and ordered. */
    private void beginStatusSession() {
        long now=SystemClock.elapsedRealtimeNanos();
        statusEpoch=STATUS_ORDER.begin(now);
        synchronized(events){events.clear();}
        lastEventState=null;reportedPreflightError="";
        synchronized(interaction) {
            interaction.setCalibration(FaceControlCalibration.defaults());
            interaction.reset();
            if(!inputStartAllowed())runtimeError=inputStopFailureMessage();
            else if(!configurationError.isEmpty())runtimeError=configurationError;
            else if(options.needsCamera()&&!hasCameraPermission())runtimeError="摄像头未授权，请在维护页面重试";
            else if(!renderFault&&progressFault==null)runtimeError="";
            if(!inputStartAllowed()||renderFault||progressFault!=null||!configurationError.isEmpty()
                    ||(options.needsCamera()&&!hasCameraPermission()))interaction.setError();
            snapshot=interaction.sample(SystemClock::elapsedRealtimeNanos);
        }
        lastEventState=snapshot.state();recordEvent(SystemClock.elapsedRealtimeNanos(),"state",snapshot.state().name());
        RuntimeStatusOrder.Ticket ticket=statusTicket(statusEpoch);
        try {
            JSONObject initial=basicStatus(ticket).put("face_present",false)
                    .put("runtime_stage",snapshot.state()==InteractionController.State.ERROR?"PRECHECK_FAILED":"WAITING_FOR_GL");
            new Thread(()->{
                try{atomicStatus(ticket,initial);}
                catch(Exception failure){Log.e("MirrorRuntime","Cannot publish initial runtime status",failure);}
            },"MirrorResumeStatus").start();
        } catch(Exception failure){Log.e("MirrorRuntime","Cannot prepare initial runtime status",failure);}
    }
    private void startIfReady(boolean mayPrompt) {
        if (!avatarStartup.ready() || !resumed || maintenance || calibrationOpen || restarting || renderFault || progressFault != null || isFinishing() || isDestroyed()
                || worker != null) return;
        if(!inputStartAllowed()){reportPreflightFault(inputStopFailureMessage());return;}
        if (!configurationError.isEmpty()) {
            reportPreflightFault(configurationError);
            return;
        }
        if (options.needsCamera() && !hasCameraPermission()) {
            reportPreflightFault("摄像头未授权，请在维护页面重试");
            if (mayPrompt && !permissionPrompted) {
                permissionPrompted = true;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            }
            return;
        }
        // Load/display the character before starting native inference. Until then the product is
        // initializing, not interacting; this also avoids competing CPU-heavy startup work.
        if (!renderer.hasRuntimeFrame()) return;
        reportedPreflightError = "";
        worker = new RuntimeWorker(statusEpoch);
        cameraInput=null;cameraObservation=null;
        worker.start();
    }
    private void reportPreflightFault(String error) {
        runtimeError = error; interaction.setError(); recordFaultState();
        if (error.equals(reportedPreflightError)) return;
        reportedPreflightError = error;
        // No input worker exists in these cases, so it cannot produce the usual status file.
        queueFaultStatus("MirrorPreflightFaultReport");
    }
    private void cancelWorker() { synchronized(interaction){if (worker != null) worker.cancel();cameraInput=null;cameraObservation=null;} }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            long now = SystemClock.elapsedRealtimeNanos();
            boolean updateText = now - lastUiTextNs >= 500_000_000L;
            JSONObject uiGl=null;
            if (updateText && !renderFault) {
                try {
                    uiGl=renderer.runtimeStatus();String error = uiGl.optString("error", "");
                    if (!error.isEmpty()) latchRenderFault(error);
                } catch (Exception error) { latchRenderFault(concise(error)); }
            }
            if (updateText && !maintenance && progressFault == null && worker != null && !worker.cancelled) {
                RuntimeProgressWatchdog.Fault fault = worker.watchdog.poll(SystemClock.elapsedRealtimeNanos());
                if (fault != null) latchProgressFault(fault);
            }
            if (renderFault || progressFault != null) interaction.setError();
            else startIfReady(false);
            snapshot = interaction.sample(SystemClock::elapsedRealtimeNanos);
            CameraCalibrationPanel panel=cameraPanel;
            if(panel!=null){CameraObservation observation=cameraObservation;
                panel.tick(cameraInput,observation==null?null:observation.frame(),observation==null||observation.input()==null?null:observation.input().token,snapshot);}
            else if(snapshot.state()==InteractionController.State.WAITING&&lastEventState!=InteractionController.State.WAITING){
                FaceControlCalibration previous=interaction.calibration();
                if(previous.hasNeutralPose()||previous.personalBaseline()!=null)
                    interaction.setCalibration(new FaceControlCalibration(previous.revision()+1,previous.mirror(),null,null));
            }
            now = SystemClock.elapsedRealtimeNanos();
            boolean active = !maintenance && !renderFault && progressFault == null && worker != null && !worker.cancelled
                    && snapshot.state() == InteractionController.State.INTERACTIVE && snapshot.facePresent();
            renderer.setInteractiveSnapshot(snapshot, active);
            if(panel!=null)panel.preview(snapshot,active);
            renderer.setTargetFps(!maintenance && !renderFault && progressFault == null && worker != null && !worker.cancelled
                    && (snapshot.state() == InteractionController.State.INTERACTIVE
                    || snapshot.state() == InteractionController.State.GRACE) ? options.activeTargetFps : 10);
            if (snapshot.state() != lastEventState) {
                lastEventState = snapshot.state();
                recordEvent(now, "state", snapshot.state().name());
            }
            if (updateText) {
                lastUiTextNs = now;
                String error = configurationError.isEmpty() ? runtimeError : configurationError;
                runtimeControls.update(MirrorUiState.describe(new MirrorUiState.Sample(snapshot.state().name(),snapshot.facePresent(),
                        renderer.hasRuntimeFrame(),hasCameraPermission(),options.needsCamera(),renderFault,progressFault!=null,
                        worker!=null&&(worker.cancelled||worker.waitingForHardware),error,renderer.avatarWarning())));
                runtimeControls.updateRole(loadedRoleName(uiGl));
            }
            main.postDelayed(this, 33);
        }
    };

    private String loadedRoleName(JSONObject gl){
        if(gl==null||!gl.optBoolean("runtime_gl_frame_ready"))return "";
        JSONObject avatar=gl.optJSONObject("avatar");if(avatar==null)return "";
        String source=gl.optString("avatar_source"),id=gl.optString("avatar_package_id");
        if("bundled".equals(source)&&configuredBundledEntry!=null&&id.equals(configuredBundledEntry.id))
            return configuredBundledEntry.displayName+("legacy_reference".equals(configuredBundledEntry.status)?" · 旧版参考":"");
        String name=avatar.optString("display_name","");
        if(name.isEmpty())return "";
        return name+("imported".equals(source)?" · 本地导入":("builtin".equals(source)?" · 参考角色":""));
    }

    private void latchRenderFault(String error) {
        if (renderFault) return;
        renderError = error; renderFault = true;
        runtimeError = "显示故障，请在维护中重试";
        interaction.setError(); cancelWorker();
        recordEvent(SystemClock.elapsedRealtimeNanos(), "render_fault", error);
        recordFaultState();
        // Native inference may be stuck: the fault report must not depend on that worker returning.
        queueFaultStatus("MirrorRenderFaultReport");
    }

    private void latchProgressFault(RuntimeProgressWatchdog.Fault fault) {
        if (progressFault != null) return;
        progressFault = fault;
        runtimeError = "处理超时（" + fault.stage().name() + "），请在维护中重试";
        interaction.setError(); cancelWorker();
        recordEvent(fault.observedNs(), "processing_timeout", fault.stage().name() + ": " + fault.stalledMs()
                + " ms >= " + fault.timeoutMs() + " ms");
        recordFaultState();
        queueFaultStatus("MirrorProgressFaultReport");
    }

    private void recordFaultState() {
        // Publish the transition before the independent report; a stuck worker may never report again.
        if (lastEventState != InteractionController.State.ERROR) {
            lastEventState = InteractionController.State.ERROR;
            recordEvent(SystemClock.elapsedRealtimeNanos(), "state", "ERROR");
        }
    }

    private void showMaintenance() {
        if (maintenance) return;
        maintenance = true; cancelWorker(); interaction.setError();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView details = new TextView(this);
        details.setText(options.viewCount+"视点，原生竖屏输出。\n"
                + "pitch="+settings.panel.pitch()+" "+settings.panel.pitchUnits()+", tan="+settings.panel.tan()
                + ", phase="+settings.panel.phaseCycles()+"；实屏光学未确认。\n"+settings.warning+"\n"
                + "输入：" + options.label() + "\n" + maintenanceMetrics()
                + (renderer.avatarWarning().isEmpty()?"":"\n角色提示："+renderer.avatarWarning()));
        panel.addView(details);
        panel.addView(label("互动面捕目标帧率"));
        Spinner rates = spinner(new String[]{"10 FPS", "17 FPS", "20 FPS"});
        for (int i = 0; i < MirrorSettings.ANALYSIS_RATES.length; i++)
            if (MirrorSettings.ANALYSIS_RATES[i] == settings.activeFps) rates.setSelection(i);
        panel.addView(rates);
        panel.addView(label("每视点图像尺寸"));
        Spinner views = spinner(MirrorSettings.VIEW_PRESETS);
        for (int i = 0; i < MirrorSettings.VIEW_PRESETS.length; i++)
            if (MirrorSettings.VIEW_PRESETS[i].equals(settings.viewPreset)) views.setSelection(i);
        panel.addView(views);
        panel.addView(label("运行视点数（保存后重启生效）"));
        Spinner counts=spinner(new String[]{"16 视点","20 视点"});
        for(int i=0;i<MirrorSettings.VIEW_COUNTS.length;i++)if(MirrorSettings.VIEW_COUNTS[i]==settings.viewCount)counts.setSelection(i);
        panel.addView(counts);
        Button defaultsButton=new Button(this);defaultsButton.setText("恢复运行默认（仅修改草稿）");panel.addView(defaultsButton);
        defaultsButton.setOnClickListener(v->{
            try {
                MirrorSettings draft=settings.withDefaultProfile();
                for(int i=0;i<MirrorSettings.ANALYSIS_RATES.length;i++)if(MirrorSettings.ANALYSIS_RATES[i]==draft.activeFps)rates.setSelection(i);
                for(int i=0;i<MirrorSettings.VIEW_PRESETS.length;i++)if(MirrorSettings.VIEW_PRESETS[i].equals(draft.viewPreset))views.setSelection(i);
                for(int i=0;i<MirrorSettings.VIEW_COUNTS.length;i++)if(MirrorSettings.VIEW_COUNTS[i]==draft.viewCount)counts.setSelection(i);
                android.widget.Toast.makeText(this,"草稿为16视点 / 17 FPS / 400×640；保存后生效",android.widget.Toast.LENGTH_SHORT).show();
            } catch(RuntimeException invalid) {
                android.widget.Toast.makeText(this,"未修改草稿："+invalid.getMessage(),android.widget.Toast.LENGTH_LONG).show();
            }
        });
        Button calibrationButton=new Button(this);calibrationButton.setText("屏幕校准 / "+options.viewCount+"视点测试图");panel.addView(calibrationButton);
        Button avatarButton=new Button(this);avatarButton.setText("角色管理 / 导入与预览");panel.addView(avatarButton);
        Button cameraButton=new Button(this);cameraButton.setText("相机与动作校准");panel.addView(cameraButton);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("魔镜维护").setView(panel)
                .setPositiveButton("保存并运行", (d, which) -> {
                    int fps = MirrorSettings.ANALYSIS_RATES[rates.getSelectedItemPosition()];
                    String preset = MirrorSettings.VIEW_PRESETS[views.getSelectedItemPosition()];
                    int count=MirrorSettings.VIEW_COUNTS[counts.getSelectedItemPosition()];
                    try {saveRuntimeProfile(fps,preset,count);}
                    catch(RuntimeException invalid) {
                        android.widget.Toast.makeText(this,"未保存："+invalid.getMessage(),android.widget.Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("返回", null)
                .setNeutralButton("授权 / 重试", (d, which) -> {
                    if (renderFault||!renderer.avatarWarning().isEmpty()) {
                        restarting = true;
                        recreate();
                    } else if (progressFault != null) {
                        if (worker == null) {
                            progressFault = null; runtimeError = ""; interaction.clearError();
                        } else {
                            runtimeError = "停止中，原处理尚未释放；请稍后重试";
                        }
                    } else if (options.needsCamera() && !hasCameraPermission()) {
                        permissionPrompted = true;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
                    } else { runtimeError = ""; interaction.clearError(); }
                }).create();
        dialog.setOnDismissListener(ignored -> { maintenance = false; startIfReady(false); });
        calibrationButton.setOnClickListener(ignored -> {
            calibrationOpen=true;
            startActivityForResult(CalibrationActivity.intent(this,options.viewCount,
                    (getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0),PANEL_CALIBRATION);
            dialog.dismiss();
        });
        avatarButton.setOnClickListener(ignored -> {
            calibrationOpen=true;
            startActivityForResult(new Intent(this,AvatarManagementActivity.class),AVATAR_MANAGEMENT);
            dialog.dismiss();
        });
        cameraButton.setOnClickListener(ignored->{dialog.dismiss();showCameraCalibration();});
        dialog.show();MirrorTheme.safeDialog(this,dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(settings.writable);
    }

    private void openRoles(){
        calibrationOpen=true;
        startActivityForResult(new Intent(this,MirrorRolesActivity.class).putExtra("from_mirror",true),AVATAR_MANAGEMENT);
    }
    private void returnHome(){
        startActivity(new Intent(this,MirrorHomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
    @Override public void onBackPressed(){
        if(runtimeControls.hideMenu())return;
        returnHome();
    }
    private void retryRuntime(){
        if(!inputStartAllowed()){runtimeError=inputStopFailureMessage();interaction.setError();return;}
        if(options.needsCamera()&&!hasCameraPermission()){
            if(permissionPrompted&&!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA))
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));
            else {permissionPrompted=true;requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION);}
            return;
        }
        if(renderFault||!renderer.avatarWarning().isEmpty()){restarting=true;recreate();return;}
        if(progressFault!=null){
            if(worker!=null){cancelWorker();return;}
            progressFault=null;
        }
        runtimeError="";interaction.clearError();cancelWorker();startIfReady(false);
    }
    private void showProductSettings(){
        if(productSettings!=null)return;
        Dialog dialog=new Dialog(this);productSettings=dialog;
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14),dp(12),dp(14),dp(12));
        body.addView(MirrorTheme.text(this,"设置",23,true));
        body.addView(MirrorTheme.text(this,"调好镜中的自己",13,false));
        MirrorTheme.addButton(body,MirrorTheme.button(this,"画面、镜像与表情",false,()->{dialog.dismiss();showSceneView();}));
        MirrorTheme.addButton(body,MirrorTheme.button(this,"相机与动作校准",false,()->{dialog.dismiss();showCameraCalibration();}));
        MirrorTheme.addButton(body,MirrorTheme.button(this,"屏幕与运行参数（高级）",false,()->{dialog.dismiss();showMaintenance();}));
        MirrorTheme.addButton(body,MirrorTheme.button(this,"关于另一个你",false,()->{
            AlertDialog about=new AlertDialog.Builder(this).setTitle("Another You / 另一个你")
                    .setMessage("镜中的角色，用表情回应你。\n\n相机画面用于本机互动。个人中性基准只用于本次互动，安装与画面设置在明确保存后保留。\n\n完整角色库仍在逐项校正；版本与运行详情见高级设置。")
                    .setPositiveButton("返回",null).create();about.show();MirrorTheme.safeDialog(this,about);
        }));
        MirrorTheme.addButton(body,MirrorTheme.button(this,"返回魔镜",true,dialog::dismiss));
        ScrollView scroll=new ScrollView(this);scroll.addView(body);dialog.setContentView(scroll);
        dialog.setOnDismissListener(d->productSettings=null);dialog.show();MirrorTheme.safeDialog(this,dialog);
    }
    private void showCameraCalibration(){
        if(cameraPanel!=null||!resumed)return;
        if(!avatarStartup.ready()){
            android.widget.Toast.makeText(this,configurationError.contains("所选角色")?"请先在角色页重新选择角色，再打开校准。":"角色仍在准备，请稍后打开校准。",android.widget.Toast.LENGTH_LONG).show();return;
        }
        if(!options.input.equals("camera")){
            android.widget.Toast.makeText(this,"当前是测试录像输入，请重新以实时相机启动后校准。",android.widget.Toast.LENGTH_LONG).show();return;
        }
        if(!renderer.hasRuntimeFrame()){
            android.widget.Toast.makeText(this,"角色画面仍在准备，请稍后打开相机校准。",android.widget.Toast.LENGTH_LONG).show();return;
        }
        final CameraControlSettings original=requestedCamera;
        FaceControlCalibration oldCalibration=interaction.calibration();
        final FaceControlCalibration originalCalibration=new FaceControlCalibration(oldCalibration.revision(),oldCalibration.mirror(),null,null);
        surface.onPause();
        try{
        cameraPanel=new CameraCalibrationPanel(this,original,originalCalibration,settings.writable,new CameraCalibrationPanel.Host(){
            @Override public void changeInput(CameraControlSettings controls){
                synchronized(interaction){
                    requestedCamera=controls;cameraInput=null;cameraObservation=null;
                    interaction.setCalibration(new FaceControlCalibration(controls.revision,controls.mirrorInteraction,null,null));
                    interaction.reset();cancelWorker();
                }
            }
            @Override public boolean changeCalibration(CameraCalibrationPanel.Input expected,FaceControlCalibration calibration){
                synchronized(interaction){
                    if(!resumed||cameraPanel==null||expected==null||expected!=cameraInput||worker==null||worker.cancelled)return false;
                    requestedCamera=expected.effective.withMirror(calibration.mirror(),calibration.revision());
                    interaction.setCalibration(calibration);return true;
                }
            }
            @Override public boolean confirmCalibration(CameraCalibrationPanel owner,CameraCalibrationPanel.Input expected,
                    NeutralCalibrationCollector.Session session,NeutralCalibrationCollector.Result result,FaceControlCalibration calibration){
                // The inference callback revokes READY under this same delivery lock before offerRaw.
                // Recheck and publish together; never hold the panel lock while entering this lock.
                synchronized(interaction){
                    if(!resumed||cameraPanel!=owner||expected==null||expected!=cameraInput||worker==null||worker.cancelled
                            ||!owner.isCurrentReady(expected,session,result))return false;
                    requestedCamera=expected.effective.withMirror(calibration.mirror(),calibration.revision());
                    interaction.setCalibration(calibration);return true;
                }
            }
            @Override public void save(CameraCalibrationPanel owner,CameraCalibrationPanel.Input expected,CameraControlSettings controls){
                synchronized(interaction){
                    if(!resumed||cameraPanel!=owner||expected==null||expected!=cameraInput||worker==null||worker.cancelled)
                        throw new IllegalStateException("相机输入已改变，请重新检查方向和动作");
                    MirrorSettings.saveCamera(MirrorActivity.this,controls);
                    // Camera edits do not activate another runtime profile from the preferences map.
                    settings=settings.withCamera(controls);requestedCamera=settings.camera;
                }
            }
            @Override public void closed(CameraCalibrationPanel panel,boolean saved,boolean inputChanged){
                synchronized(interaction){
                    if(cameraPanel!=panel)return;cameraPanel=null;
                    if(!saved){requestedCamera=original;
                        CameraCalibrationPanel.Input current=cameraInput;
                        boolean matching=current!=null&&original.matches(current.effective.cameraId,current.effective.fingerprint,WIDTH,HEIGHT);
                        interaction.setCalibration(new FaceControlCalibration(originalCalibration.revision()+1,matching&&original.mirrorInteraction,null,null));
                        if(inputChanged){cameraInput=null;cameraObservation=null;interaction.reset();cancelWorker();}
                    }
                }
                if(resumed){renderer.resumeRuntimeAvatar();surface.onResume();}
            }
        });
        cameraPanel.startPreview();
        }catch(RuntimeException failure){
            if(cameraPanel!=null)cameraPanel.dismiss();
            else if(resumed){renderer.resumeRuntimeAvatar();surface.onResume();}
            android.widget.Toast.makeText(this,"无法打开校准预览："+concise(failure),android.widget.Toast.LENGTH_LONG).show();
        }
        startIfReady(false);
    }
    private JSONObject cameraPreviewJson()throws Exception{
        CameraCalibrationPanel panel=cameraPanel;if(panel==null)return null;
        CameraCalibrationAvatarPreview.Status preview=panel.previewStatus();
        return new JSONObject().put("state",preview.state()).put("source",preview.source())
                .put("model_sha256",preview.modelSha256()).put("display_name",preview.displayName()).put("error",preview.error())
                .put("submitted_frames",preview.submittedFrames()).put("last_frame_elapsed_ns",preview.lastFrameElapsedNs())
                .put("viewport_width",preview.viewportWidth()).put("viewport_height",preview.viewportHeight())
                .put("maximum_fps",preview.maximumFps()).put("additional_pose_workers",preview.additionalPoseWorkers())
                .put("decoded_asset_bytes",preview.decodedAssetBytes()).put("main_multiview_paused",true);
    }
    private String maintenanceMetrics() {
        try {
            JSONObject gl = renderer.runtimeStatus();
            Stats current = stats;
            JSONObject values = current == null ? new JSONObject() : current.summary(SystemClock.elapsedRealtimeNanos());
            return String.format(Locale.ROOT, "画面 %.1f FPS；有效面捕 %.1f FPS；恢复 %d 次\n"
                    + (options.npuBlendshapes
                            ? "NPU：检测+478点；52表情：CPU/NPU混合；CPU：归一化+姿态\n"
                            : "NPU：检测+478点；CPU：52表情+姿态\n")
                    + "诊断仅保存数值，不保存人脸图像。",
                    gl.optDouble("fps"), values.optDouble("face_fps", 0), recoveries);
        } catch (Exception error) { return "统计暂不可用"; }
    }
    private TextView label(String text) { TextView view = new TextView(this); view.setText(text); return view; }
    private Spinner spinner(String[] choices) {
        Spinner view = MirrorTheme.selectionSpinner(this,"选择运行参数");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        view.setAdapter(adapter); return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String stateLabel(InteractionController.State state) {
        return switch (state) {
            case WAITING -> "等待人脸";
            case ACQUIRING -> "正在确认人脸";
            case INTERACTIVE -> "跟随中";
            case GRACE -> "暂时未见人脸";
            case ERROR -> "暂不可互动";
        };
    }

    private final class RuntimeWorker extends Thread {
        private final RuntimeStatusOrder.Epoch ownerEpoch;
        private final RuntimeInputStop inputStop;
        private volatile boolean cancelled;
        private volatile boolean waitingForHardware = true;
        private final RuntimeProgressWatchdog watchdog = new RuntimeProgressWatchdog();
        private long sequence, modelTimestamp;
        RuntimeWorker(RuntimeStatusOrder.Epoch ownerEpoch) {
            super("MirrorRuntime");this.ownerEpoch=ownerEpoch;
            inputStop=new RuntimeInputStop(ownerEpoch,SystemClock.elapsedRealtimeNanos());
        }
        void cancel() {
            cancelled = true; watchdog.cancel(); interrupt();
            // Only CameraFrameSource permits cross-thread close while an Image is borrowed.
            inputStop.requestCameraClose();
        }
        @Override public void run() {
            boolean ownsHardware = false;
            try {
                HARDWARE_OWNER.acquire(); ownsHardware = true; waitingForHardware = false;
                synchronized(interaction) {
                    if(cancelled||ownerEpoch!=statusEpoch)return;
                    if(!inputStartAllowed()){runtimeError=inputStopFailureMessage();interaction.setError();return;}
                    interaction.reset();
                }
                int failures = 0;
                while (!cancelled) {
                    FrameInput source = null, reference = null;
                    YuvConverter converter = null, referenceConverter = null;
                    CameraBitmapNormalizer normalizer=null;
                    CameraCalibrationPanel.Input openedInput=null;
                    NpuFacePipeline npu = null;
                    try {
                        progress(RuntimeProgressWatchdog.Stage.OPENING_CAMERA);
                        source = options.input.equals("replay") ? replay() : new CameraFrameSource(MirrorActivity.this, WIDTH, HEIGHT, CAPTURE_FPS);
                        if(source instanceof CameraFrameSource)inputStop.bindCamera(source);
                        if (cancelled) { inputStop.requestCameraClose(); break; }
                        progress(RuntimeProgressWatchdog.Stage.INITIALIZING);
                        converter = new YuvConverter(MirrorActivity.this, WIDTH, HEIGHT, true, true);
                        CameraControlSettings effective=CameraControlSettings.DEFAULT;
                        CameraControlSettings requestedControls=requestedCamera;
                        if(options.input.equals("camera")&&source instanceof CameraFrameSource camera){
                            boolean match=requestedControls.cameraId.equals(camera.cameraId())&&requestedControls.fingerprint.equals(camera.fingerprint())
                                    &&requestedControls.width==WIDTH&&requestedControls.height==HEIGHT;
                            effective=new CameraControlSettings(camera.cameraId(),camera.fingerprint(),WIDTH,HEIGHT,match?requestedControls.rotationDegrees:0,
                                    match&&requestedControls.reflectInput,match&&requestedControls.mirrorInteraction,requestedControls.revision);
                            openedInput=new CameraCalibrationPanel.Input(effective,!requestedControls.cameraId.isEmpty()&&!match?
                                    "保存的相机描述或尺寸不匹配，已暂用默认方向；请重新核对安装。":"");
                        }
                        normalizer=new CameraBitmapNormalizer(WIDTH,HEIGHT,effective.rotationDegrees,effective.reflectInput);
                        final CameraCalibrationPanel.Input attemptInput=openedInput;
                        if (options.input.equals("camera_replay")) {
                            reference = replay();
                            referenceConverter = new YuvConverter(MirrorActivity.this, WIDTH, HEIGHT, true, false);
                        }
                        npu = new NpuFacePipeline(MirrorActivity.this,true,options.npuBlendshapes);
                        if (cancelled) break;
                        source.beginMeasurement();
                        if (reference != null) reference.beginMeasurement();
                        Stats attempt = new Stats(SystemClock.elapsedRealtimeNanos()); stats = attempt;
                        synchronized(interaction) {
                            if(cancelled||ownerEpoch!=statusEpoch)break;
                            interaction.setCalibration(new FaceControlCalibration(effective.revision,effective.mirrorInteraction,null,null));
                            interaction.reset();cameraInput=attemptInput;cameraObservation=null;
                            runtimeError = ""; interaction.clearError();
                        }
                        recordEvent(ownerEpoch,attempt.startedNs, "input_open", options.input);
                        long reportAt = 0, stableStart = attempt.startedNs;
                        while (!cancelled) {
                            long started = SystemClock.elapsedRealtimeNanos();
                            ConvertedFrame converted = convert(source, converter, watchdog);
                            long receivedNs = converted.receivedNs();
                            Bitmap input = converter.bitmap;
                            double referenceMs = 0;
                            if (reference != null) {
                                ConvertedFrame recorded = convert(reference, referenceConverter, watchdog);
                                receivedNs = recorded.receivedNs();
                                referenceMs = recorded.conversionMs();
                                input = referenceConverter.bitmap;
                            }
                            if (cancelled) break;
                            long normalizing=SystemClock.elapsedRealtimeNanos();input=normalizer.apply(input);
                            attempt.normalized((SystemClock.elapsedRealtimeNanos()-normalizing)/1e6);
                            CameraCalibrationPanel activePanel=cameraPanel;
                            if(activePanel!=null&&attemptInput!=null)activePanel.offerPreview(attemptInput,input);
                            boolean blackout = options.blackout(started - attempt.startedNs);
                            if (blackout) input.eraseColor(Color.BLACK);
                            attempt.converted(converted.conversionMs(), referenceMs);
                            long inferenceStart = SystemClock.elapsedRealtimeNanos();
                            progress(RuntimeProgressWatchdog.Stage.INFERENCING);
                            long frameSequence = sequence++, frameReceived = receivedNs;
                            modelTimestamp = Math.max(modelTimestamp + 1, SystemClock.uptimeMillis());
                            npu.submit(input, modelTimestamp, true, detected -> {
                                if (cancelled) return;
                                long completed = SystemClock.elapsedRealtimeNanos();
                                FaceFrame frame = detected == null ? FaceFrame.absent(frameSequence, frameReceived, completed)
                                        : FaceFrame.present(frameSequence, frameReceived, completed, detected.blendshapes(), detected.pose());
                                synchronized(interaction) {
                                    if(cancelled||ownerEpoch!=statusEpoch)return;
                                    interaction.accept(frame);
                                    cameraObservation=new CameraObservation(attemptInput,frame);
                                    CameraCalibrationPanel currentPanel=cameraPanel;
                                    if(currentPanel!=null)currentPanel.offerRaw(attemptInput,frame);
                                }
                                attempt.completed(detected != null, (completed - frameReceived) / 1e6,
                                        (completed - inferenceStart) / 1e6, detected == null ? 0 : detected.landmarks().length / 3);
                            });
                            attempt.submitted((SystemClock.elapsedRealtimeNanos() - inferenceStart) / 1e6);
                            long now = SystemClock.elapsedRealtimeNanos();
                            if (now >= reportAt) {
                                // summary drains the sole CPU job here, away from the UI and GL threads.
                                progress(RuntimeProgressWatchdog.Stage.REPORTING);
                                writeStatus(ownerEpoch,attempt, source, npu.summary(), blackout);
                                reportAt = now + 5_000_000_000L;
                            }
                            if (now - stableStart > 30_000_000_000L) failures = 0;
                            InteractionController.Snapshot current = snapshot;
                            boolean active = current != null && (current.state() == InteractionController.State.INTERACTIVE
                                    || current.state() == InteractionController.State.GRACE);
                            int fps = cameraPanel!=null ? 20 : active ? settings.activeFps : 3;
                            progress(RuntimeProgressWatchdog.Stage.PACING);
                            long rest = 1_000_000_000L / fps - (SystemClock.elapsedRealtimeNanos() - started);
                            if (rest > 0) Thread.sleep(rest / 1_000_000L, (int) (rest % 1_000_000L));
                        }
                    } catch (InterruptedException interrupted) {
                        synchronized(interaction) {
                            if(!cancelled&&ownerEpoch==statusEpoch){runtimeError="处理线程被中断";interaction.setError();}
                        }
                        break;
                    } catch (Exception | LinkageError error) {
                        recordRuntimeNativeFailure(inputStop,error);
                        boolean currentOwner;
                        synchronized(interaction) {
                            currentOwner=!cancelled&&ownerEpoch==statusEpoch;
                            if(currentOwner){runtimeError=concise(error);interaction.setError();cameraInput=null;cameraObservation=null;recoveries++;}
                        }
                        if (currentOwner) {
                            recordEvent(ownerEpoch,SystemClock.elapsedRealtimeNanos(), "fault", runtimeError);
                            Log.e("MirrorRuntime", "Runtime input failed", error);
                            writeFaultStatus(statusTicket(ownerEpoch));
                        }
                    } catch (Error error) {
                        recordRuntimeNativeFailure(inputStop,error);
                        throw error;
                    } finally {
                        // Every borrowed Image was closed by convert(); only this worker owns these resources.
                        synchronized(interaction){if(cameraInput==openedInput){cameraInput=null;cameraObservation=null;}}
                        progress(RuntimeProgressWatchdog.Stage.RELEASING);
                        closeRuntimeInputs(inputStop,source,reference,npu,normalizer,converter,referenceConverter);
                    }
                    if(inputStop.hasFailures()){cancelled=true;break;}
                    if (cancelled) break;
                    int delay = RETRY_SECONDS[Math.min(failures++, RETRY_SECONDS.length - 1)];
                    progress(RuntimeProgressWatchdog.Stage.RETRY_WAIT);
                    recordEvent(ownerEpoch,SystemClock.elapsedRealtimeNanos(), "retry_wait_seconds", String.valueOf(delay));
                    Thread.sleep(delay * 1000L);
                }
            } catch (InterruptedException cancelledWhileWaiting) {
                Thread.currentThread().interrupt();
            } finally {
                // A duplicate close can return while the dedicated close task is still in the camera HAL.
                // Keep the process-wide ownership permit until both cleanup paths have finished.
                cancelled=true;
                RuntimeInputStop.Receipt stopped=finishRuntimeInputStop(inputStop,SystemClock::elapsedRealtimeNanos);
                lastInputStop=new InputStopRecord(this,stopped);
                if(!stopped.closeCallsSucceeded()) {
                    synchronized(interaction){if(ownerEpoch==statusEpoch){runtimeError=inputStopFailureMessage();interaction.setError();}}
                    recordEvent(ownerEpoch,SystemClock.elapsedRealtimeNanos(),"input_stop_failed",stopped.workerId());
                }
                progress(RuntimeProgressWatchdog.Stage.STOPPED);
                if (ownsHardware) HARDWARE_OWNER.release();
                main.post(() -> {
                    if (worker == this) worker = null;
                    startIfReady(false);
                });
            }
        }
        private void progress(RuntimeProgressWatchdog.Stage stage) {
            watchdog.progress(stage, SystemClock.elapsedRealtimeNanos());
        }
        private ReplayFrameSource replay() throws Exception {
            return new ReplayFrameSource(MirrorActivity.this, WIDTH, HEIGHT, options.recordId, options.replayFps, false);
        }
    }

    private record ConvertedFrame(long receivedNs, double conversionMs) {}
    private static ConvertedFrame convert(FrameInput source, YuvConverter converter, RuntimeProgressWatchdog watchdog) throws Exception {
        watchdog.progress(RuntimeProgressWatchdog.Stage.CAPTURING, SystemClock.elapsedRealtimeNanos());
        FrameInput.Frame frame = source.take();
        long before = SystemClock.elapsedRealtimeNanos();
        try {
            watchdog.progress(RuntimeProgressWatchdog.Stage.CONVERTING, before);
            converter.convert(frame);
            return new ConvertedFrame(frame.receivedNs, (SystemClock.elapsedRealtimeNanos() - before) / 1e6);
        }
        finally { frame.close(); }
    }
    private static void closeRuntimeInputs(RuntimeInputStop stop,AutoCloseable source,AutoCloseable reference,
                                          AutoCloseable npu,AutoCloseable normalizer,AutoCloseable converter,AutoCloseable referenceConverter) {
        // Clear and claim use the same lock: a late cancel cannot schedule an untracked closer.
        stop.clearCamera(source);
        closeInput(stop,RuntimeInputStop.Resource.SOURCE,source);
        closeInput(stop,RuntimeInputStop.Resource.REFERENCE,reference);
        closeInput(stop,RuntimeInputStop.Resource.NPU,npu);
        closeInput(stop,RuntimeInputStop.Resource.NORMALIZER,normalizer);
        closeInput(stop,RuntimeInputStop.Resource.CONVERTER,converter);
        closeInput(stop,RuntimeInputStop.Resource.REFERENCE_CONVERTER,referenceConverter);
    }
    private static void recordRuntimeNativeFailure(RuntimeInputStop stop,Throwable error) {
        if(NativeCleanupUnconfirmed.contains(error))stop.recordUnconfirmedCleanup(RuntimeInputStop.Resource.NPU,error);
    }
    private static void closeInput(RuntimeInputStop stop,RuntimeInputStop.Resource kind,AutoCloseable resource) {
        Throwable failure=stop.close(kind,resource);
        if(failure!=null)Log.e("MirrorRuntime","Input resource close failed: "+kind.name(),failure);
    }
    private static RuntimeInputStop.Receipt finishRuntimeInputStop(RuntimeInputStop stop,java.util.function.LongSupplier clock) {
        stop.awaitCameraClose();
        RuntimeInputStop.Receipt receipt=stop.seal(clock.getAsLong());
        INPUT_STOP_FAILURES.record(receipt); // Must precede release of HARDWARE_OWNER.
        return receipt;
    }
    private static boolean inputStartAllowed(){return !INPUT_STOP_FAILURES.blocked();}
    private static String inputStopFailureMessage(){return "输入资源停止未确认，请强制停止应用后重新打开";}
    private static JSONObject inputStopReceiptJson(RuntimeInputStop.Receipt receipt)throws Exception {
        JSONObject resources=new JSONObject();
        for(RuntimeInputStop.Resource kind:RuntimeInputStop.Resource.values())resources.put(kind.name(),new JSONObject()
                .put("attempts",receipt.attempts(kind)).put("failures",receipt.failures(kind))
                .put("first_failure_type",receipt.failureType(kind)==null?"":receipt.failureType(kind)));
        return new JSONObject().put("worker_id",receipt.workerId()).put("epoch_id",receipt.epochId())
                .put("epoch_number",receipt.epochNumber()).put("started_elapsed_ns",receipt.startedNs())
                .put("finished_elapsed_ns",receipt.finishedNs()).put("close_calls_succeeded",receipt.closeCallsSucceeded())
                .put("async_camera_claimed",receipt.asyncCameraClaimed())
                .put("async_camera_thread_terminated",receipt.asyncCameraThreadTerminated())
                .put("resources",resources).put("hardware_qualified",false)
                .put("scope","Acquired-resource close returns and camera-close thread termination only; excludes runtime-worker termination, Camera2 HAL acknowledgement, native post-worker, GL/pose and factory failure cleanup");
    }
    private JSONObject inputStopJson()throws Exception {
        InputStopRecord previous=lastInputStop;
        RuntimeInputStop.Receipt failure=INPUT_STOP_FAILURES.failure();
        Thread.State state=previous==null?null:previous.worker().getState();
        return new JSONObject().put("failure_latched",failure!=null)
                .put("latched_failure_receipt",failure==null?JSONObject.NULL:inputStopReceiptJson(failure))
                .put("last_worker_receipt",previous==null?JSONObject.NULL:inputStopReceiptJson(previous.receipt()))
                .put("last_worker_thread_state",state==null?JSONObject.NULL:state.name())
                .put("last_worker_thread_terminated",state==Thread.State.TERMINATED);
    }
    private static String concise(Throwable error) {
        String text = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
        return text.length() > 160 ? text.substring(0, 160) : text;
    }
    private void recordEvent(long now, String event, String value) {
        recordEvent(statusEpoch,now,event,value);
    }
    private void recordEvent(RuntimeStatusOrder.Epoch owner,long now,String event,String value) {
        try {
            JSONObject row = new JSONObject().put("elapsed_ns", now).put("monotonic_ns", System.nanoTime())
                    .put("event", event).put("value", value);
            if (event.equals("state")) row.put("state", value);
            synchronized (events) {
                if(owner==null||owner!=statusEpoch)return;
                if (events.size() >= 64) events.removeFirst(); events.addLast(row);
            }
        } catch (Exception ignored) { /* Numerical diagnostics must not interrupt interaction. */ }
    }
    private RuntimeStatusOrder.Ticket statusTicket(RuntimeStatusOrder.Epoch owner) {
        return STATUS_ORDER.capture(owner,SystemClock.elapsedRealtimeNanos());
    }
    private JSONObject basicStatus(RuntimeStatusOrder.Ticket ticket) throws Exception {
        InteractionController.Snapshot current = snapshot;
        long elapsedNs = SystemClock.elapsedRealtimeNanos(), monotonicNs = System.nanoTime();
        JSONObject value = new JSONObject().put("schema_version", 1).put("input", options.input)
                .put("activity_instance_id",activityInstanceId)
                .put("gl_context_policy",new JSONObject().put("release_on_pause_requested",options.releaseGlOnPause)
                        .put("preserve_on_pause_requested",!options.releaseGlOnPause)
                        .put("preserve_on_pause_actual",preserveEglOnPauseActual)
                        .put("actual_scope","GLSurfaceView getter configuration; context generations prove recreation"))
                .put("runtime_session_id",ticket.epoch().id()).put("runtime_epoch",ticket.epoch().number())
                .put("session_started_elapsed_ns",ticket.epoch().startedNs()).put("status_sequence",ticket.sequence())
                .put("status_capture_elapsed_ns",ticket.capturedNs())
                .put("input_description", options.label()).put("diagnostic_character", false)
                .put("updated_elapsed_ns", elapsedNs).put("updated_monotonic_ns", monotonicNs)
                .put("state", current == null ? "WAITING" : current.state().name())
                .put("result_age_ms", current == null ? -1 : current.resultAgeMs())
                .put("face_present", inputStartAllowed() && !renderFault && progressFault == null && current != null && current.facePresent()).put("error", runtimeError)
                .put("input_stop",inputStopJson())
                .put("render_fault", renderFault).put("render_error", renderError)
                .put("processing_fault", progressFault != null).put("processing_timeout", progressFaultJson())
                .put("runtime_stage", worker == null ? "STOPPED" : worker.watchdog.stage().name())
                .put("recoveries", recoveries).put("analysis_active_target_fps", settings.activeFps).put("analysis_idle_target_fps", 3)
                .put("camera_calibration_open",cameraPanel!=null).put("camera_controls",cameraControlsJson())
                .put("camera_avatar_preview",cameraPreviewJson())
                .put("control_revision",current==null?0:current.calibrationRevision())
                .put("control_error",current==null?"":current.calibrationError())
                .put("control_rejected_frames",current==null?0:current.calibrationRejectedFrames())
                .put("view_width", runtimeViewWidth).put("view_height", runtimeViewHeight).put("view_count", options.viewCount)
                .put("configured_view_count",settings.viewCount)
                .put("debug_npu_blendshapes",options.npuBlendshapes)
                .put("debug_render_overrides",new JSONObject().put("synchronous",options.synchronousAvatar)
                        .put("persistent_fbos",options.persistentFbos)
                        .put("cached_camera_vp",options.cachedCameraVp)
                        .put("static_background_cache",options.staticBackgroundCache)
                        .put("gpu_profile",options.gpuProfile)
                        .put("orm_rg8",options.ormRg8)
                        .put("release_gl_on_pause",options.releaseGlOnPause)
                        .put("batched",options.batchedAvatar).put("view_preset",options.viewPreset==null?JSONObject.NULL:options.viewPreset)
                        .put("active_target_fps",options.activeTargetFps).put("view_count",options.viewCount))
                .put("optical_calibration", new JSONObject().put("pitch", settings.panel.pitch())
                        .put("pitch_units", settings.panel.pitchUnits().name()).put("tan", settings.panel.tan())
                        .put("phase_cycles", settings.panel.phaseCycles()).put("subpixel_order", settings.panel.subpixelOrder().name())
                        .put("reverse_views", settings.panel.reverseViews()).put("y_origin", settings.panel.yOrigin().name())
                        .put("optical_alignment_verified", false))
                .put("renderer", renderer.runtimeStatus()).put("stored_face_images", false);
        synchronized (events) {
            value.put("events", new JSONArray(events));
            JSONArray states = new JSONArray();
            for (JSONObject row : events) if (row.has("state")) states.put(row);
            value.put("state_events", states);
        }
        return value;
    }
    private JSONObject cameraControlsJson() throws Exception {
        CameraCalibrationPanel.Input current=cameraInput;
        CameraControlSettings controls=current==null?CameraControlSettings.DEFAULT:current.effective;
        return new JSONObject().put("camera_id",controls.cameraId).put("descriptor_sha256",controls.fingerprint)
                .put("input_rotation_degrees",controls.rotationDegrees).put("reflect_normalized_input",controls.reflectInput)
                .put("mirror_interaction",interaction.calibration().mirror()).put("warning",current==null?"":current.warning)
                .put("neutral_head_session_only",interaction.calibration().hasNeutralPose())
                .put("personal_baseline_session_only",interaction.calibration().personalBaseline()!=null)
                .put("descriptor_is_unique_device_serial",false);
    }
    private void writeStatus(RuntimeStatusOrder.Epoch owner,Stats measurements, FrameInput source, JSONObject npu, boolean blackout) {
        RuntimeStatusOrder.Ticket ticket=statusTicket(owner);
        if(ticket==null)return;
        try {
            long now = SystemClock.elapsedRealtimeNanos();
            JSONObject value = basicStatus(ticket).put("metrics", measurements.summary(now)).put("npu", npu)
                    .put("source", source.summary(Math.max(.001, (now - measurements.startedNs) / 1e9)))
                    .put("debug_blackout_active", blackout)
                    .put("inference_timing_scope", "submission includes NPU plus prior CPU backpressure; completion latency includes postprocessing");
            atomicStatus(ticket,value);
        } catch (Exception error) { Log.e("MirrorRuntime", "Cannot save runtime status", error); }
    }
    private void queueFaultStatus(String name) {
        RuntimeStatusOrder.Ticket ticket=statusTicket(statusEpoch);
        if(ticket!=null)new Thread(()->writeFaultStatus(ticket),name).start();
    }
    private void writeFaultStatus(RuntimeStatusOrder.Ticket ticket) {
        if(!STATUS_ORDER.mayPublish(ticket))return;
        try { atomicStatus(ticket,basicStatus(ticket).put("state", "ERROR").put("face_present", false)); }
        catch (Exception error) { Log.e("MirrorRuntime", "Cannot save fault status", error); }
    }
    private void atomicStatus(RuntimeStatusOrder.Ticket ticket,JSONObject data) throws Exception {
        synchronized (STATUS_FILE_LOCK) {
            if (isDestroyed()||!STATUS_ORDER.mayPublish(ticket)) return;
            // A report prepared before the GL fault must not overwrite the latched diagnostic state.
            if (renderFault) data.put("state", "ERROR").put("face_present", false)
                    .put("render_fault", true).put("render_error", renderError);
            if (progressFault != null) data.put("state", "ERROR").put("face_present", false)
                    .put("processing_fault", true).put("processing_timeout", progressFaultJson());
            if(!inputStartAllowed())data.put("state","ERROR").put("face_present",false)
                    .put("error",inputStopFailureMessage()).put("input_stop",inputStopJson());
            AtomicFile destination = new AtomicFile(new File(getFilesDir(), "mirror-runtime-status.json"));
            FileOutputStream stream = null;
            try {
                stream = destination.startWrite();
                stream.write(data.toString(2).getBytes(StandardCharsets.UTF_8));
                if(!STATUS_ORDER.mayPublish(ticket)){destination.failWrite(stream);stream=null;return;}
                destination.finishWrite(stream);
                stream=null;
                STATUS_ORDER.published(ticket);
            } catch (Exception error) { if (stream != null) destination.failWrite(stream); throw error; }
        }
    }

    private Object progressFaultJson() throws Exception {
        RuntimeProgressWatchdog.Fault fault = progressFault;
        return fault == null ? JSONObject.NULL : new JSONObject().put("stage", fault.stage().name())
                .put("timeout_ms", fault.timeoutMs()).put("stalled_ms", fault.stalledMs())
                .put("observed_elapsed_ns", fault.observedNs());
    }

    private static final class Stats {
        final long startedNs;
        private long converted, submissions, completions, faces,normalized;
        private double conversionMs, referenceMs, submissionMs, completeMs, receivedMs,normalizationMs;
        private int landmarks;
        Stats(long now) { startedNs = now; }
        synchronized void converted(double conversion, double reference) {
            converted++; conversionMs += (conversion - conversionMs) / converted;
            referenceMs += (reference - referenceMs) / converted;
        }
        synchronized void submitted(double value) { submissions++; submissionMs += (value - submissionMs) / submissions; }
        synchronized void normalized(double value){normalized++;normalizationMs+=(value-normalizationMs)/normalized;}
        synchronized void completed(boolean face, double received, double complete, int count) {
            completions++; if (face) faces++; landmarks = Math.max(landmarks, count);
            receivedMs += (received - receivedMs) / completions; completeMs += (complete - completeMs) / completions;
        }
        synchronized JSONObject summary(long now) throws Exception {
            double seconds = Math.max(.001, (now - startedNs) / 1e9);
            return new JSONObject().put("elapsed_s", seconds).put("converted_frames", converted)
                    .put("submitted_frames", submissions).put("completed_frames", completions).put("face_frames", faces)
                    .put("analysis_fps", completions / seconds).put("face_fps", faces / seconds)
                    .put("face_fraction", completions == 0 ? 0 : faces / (double) completions)
                    .put("conversion_mean_ms", conversionMs).put("reference_conversion_mean_ms", referenceMs)
                    .put("normalization_mean_ms",normalizationMs).put("normalized_frames",normalized)
                    .put("submission_mean_ms", submissionMs).put("inference_completion_mean_ms", completeMs)
                    .put("received_to_completed_mean_ms", receivedMs).put("landmarks", landmarks)
                    .put("blendshapes", faces > 0 ? 52 : 0).put("retained_timing_samples", 0)
                    .put("scope", "current input session, includes acquisition/idle; online means with bounded memory");
        }
    }

    private static final class InputOptions {
        final String input, recordId;
        final double replayFps;
        final long blackoutAfterNs, blackoutDurationNs;
        final boolean synchronousAvatar,batchedAvatar,persistentFbos,cachedCameraVp,releaseGlOnPause,npuBlendshapes,privateHead,staticBackgroundCache,gpuProfile,ormRg8;
        final String viewPreset;
        final int activeTargetFps,viewCount;
        private InputOptions(String input, String recordId, double replayFps, int after, int duration,
                             boolean synchronousAvatar,boolean batchedAvatar,String viewPreset,int activeTargetFps,boolean persistentFbos,boolean cachedCameraVp,int viewCount,boolean releaseGlOnPause,boolean npuBlendshapes,boolean privateHead,boolean staticBackgroundCache,boolean gpuProfile,boolean ormRg8) {
            this.input = input; this.recordId = recordId; this.replayFps = replayFps;
            blackoutAfterNs = after * 1_000_000_000L; blackoutDurationNs = duration * 1_000_000_000L;
            this.synchronousAvatar=synchronousAvatar;
            this.batchedAvatar=batchedAvatar;this.viewPreset=viewPreset;
            this.activeTargetFps=activeTargetFps;
            this.persistentFbos=persistentFbos;
            this.cachedCameraVp=cachedCameraVp;
            this.viewCount=viewCount;
            this.releaseGlOnPause=releaseGlOnPause;
            this.npuBlendshapes=npuBlendshapes;
            this.privateHead=privateHead;
            this.staticBackgroundCache=staticBackgroundCache;
            this.gpuProfile=gpuProfile;
            this.ormRg8=ormRg8;
        }
        static InputOptions defaults() { return defaults(20); }
        static InputOptions defaults(int savedViews) { return new InputOptions("camera", "face-reference-stable-20261001-01", 24.369907, 0, 0, false,false,null,31,true,false,RuntimeViewCount.read(null,false,savedViews),false,true,false,false,false,false); }
        static InputOptions read(Bundle extras, boolean debug) { return read(extras,debug,20); }
        static InputOptions read(Bundle extras, boolean debug,int savedViews) {
            if (extras == null) return defaults(savedViews);
            // Only these existing Mirror test options are overrides. Presence (including null)
            // is different from absence; ordinary extras keep the same product defaults.
            for(String key:new String[]{"test_avatar_synchronous","test_avatar_batched","test_persistent_fbos",
                    "test_cached_camera_vp","test_static_background_cache","test_gpu_profile","test_orm_rg8","test_release_gl_on_pause","test_npu_blendshapes","test_private_head","test_view_preset",
                    "test_view_count","test_active_target_fps","test_blackout_after_s","test_blackout_duration_s"})
                if(extras.containsKey(key)&&(!debug||value(extras,key)==null))
                    throw new IllegalArgumentException("Explicit test option requires a non-null debug value: "+key);
            String input = text(extras, "runtime_input", "camera");
            if (!input.equals("camera") && !input.equals("replay") && !input.equals("camera_replay"))
                throw new IllegalArgumentException("runtime_input must be camera, replay, or camera_replay");
            String record = text(extras, "record_id", "face-reference-stable-20261001-01");
            if (!record.matches("[a-zA-Z0-9_-]{1,120}")) throw new IllegalArgumentException("Invalid record_id");
            double fps = number(extras, "replay_fps", 24.369907);
            if (!Double.isFinite(fps) || fps < 1 || fps > 60) throw new IllegalArgumentException("Invalid replay_fps");
            int after = integer(extras, "test_blackout_after_s", 0), duration = integer(extras, "test_blackout_duration_s", 0);
            if (after < 0 || after > 3600 || duration < 0 || duration > 60)
                throw new IllegalArgumentException("Invalid blackout interval");
            if ((after != 0 || duration != 0) && (!debug || input.equals("camera") || duration == 0))
                throw new IllegalArgumentException("Blackout requires a debug replay input and positive duration");
            Object synchronous=value(extras,"test_avatar_synchronous");
            if(synchronous!=null&&(!(synchronous instanceof Boolean)||!debug))throw new IllegalArgumentException("Synchronous avatar override requires debug Boolean");
            Object batched=value(extras,"test_avatar_batched");
            if(batched!=null&&(!(batched instanceof Boolean)||!debug))throw new IllegalArgumentException("Batched avatar override requires debug Boolean");
            Object persistent=value(extras,"test_persistent_fbos");
            if(persistent!=null&&(!(persistent instanceof Boolean)||!debug))throw new IllegalArgumentException("Persistent FBO override requires debug Boolean");
            Object cachedVp=value(extras,"test_cached_camera_vp");
            if(cachedVp!=null&&(!(cachedVp instanceof Boolean)||!debug))throw new IllegalArgumentException("Camera VP cache override requires debug Boolean");
            Object background=value(extras,"test_static_background_cache");
            if(background!=null&&(!(background instanceof Boolean)||!debug))throw new IllegalArgumentException("Background cache override requires debug Boolean");
            Object gpuProfile=value(extras,"test_gpu_profile");
            if(gpuProfile!=null&&(!(gpuProfile instanceof Boolean)||!debug))throw new IllegalArgumentException("GPU profiling override requires debug Boolean");
            Object ormRg8=value(extras,"test_orm_rg8");
            if(ormRg8!=null&&(!(ormRg8 instanceof Boolean)||!debug))throw new IllegalArgumentException("ORM upload override requires debug Boolean");
            boolean privateHead=RuntimeExpressionBackend.readPrivateHead(extras,debug);
            if(Boolean.TRUE.equals(background)&&(!privateHead||Boolean.TRUE.equals(batched)))
                throw new IllegalArgumentException("Background cache requires private-head individual debug rendering");
            String preset=null;
            if(value(extras,"test_view_preset")!=null) {
                preset=text(extras,"test_view_preset","");
                if(!debug||!java.util.Arrays.asList(MirrorSettings.VIEW_PRESETS).contains(preset))
                    throw new IllegalArgumentException("View preset override requires a supported debug preset");
            }
            int target=integer(extras,"test_active_target_fps",31);
            if(value(extras,"test_active_target_fps")!=null&&(!debug||(target!=31&&target!=35)))
                throw new IllegalArgumentException("Frame pacing override requires debug Integer 31 or 35");
            int views=RuntimeViewCount.read(extras,debug,savedViews);
            return new InputOptions(input, record, fps, after, duration, Boolean.TRUE.equals(synchronous),Boolean.TRUE.equals(batched),preset,target,
                    persistent==null||Boolean.TRUE.equals(persistent),Boolean.TRUE.equals(cachedVp),views,RuntimeGlLifecycle.readReleaseOnPause(extras,debug),
                    !extras.containsKey("test_npu_blendshapes")||RuntimeExpressionBackend.read(extras,debug),privateHead,Boolean.TRUE.equals(background),Boolean.TRUE.equals(gpuProfile),Boolean.TRUE.equals(ormRg8));
        }
        boolean needsCamera() { return !input.equals("replay"); }
        boolean blackout(long elapsedNs) {
            return blackoutDurationNs > 0 && elapsedNs >= blackoutAfterNs && elapsedNs - blackoutAfterNs < blackoutDurationNs;
        }
        String label() { return switch (input) {
            case "replay" -> "录像回放测试（无USB采集）";
            case "camera_replay" -> "USB采集＋录像面捕测试";
            default -> "实时USB摄像头";
        }; }
        @SuppressWarnings("deprecation") private static Object value(Bundle extras, String key) { return extras.get(key); }
        private static String text(Bundle extras, String key, String fallback) {
            Object value = value(extras, key); if (value == null) return fallback;
            if (!(value instanceof String)) throw new IllegalArgumentException("Invalid " + key);
            return (String) value;
        }
        private static double number(Bundle extras, String key, double fallback) {
            Object value = value(extras, key); if (value == null) return fallback;
            if (!(value instanceof Number)) throw new IllegalArgumentException("Invalid " + key);
            return ((Number) value).doubleValue();
        }
        private static int integer(Bundle extras, String key, int fallback) {
            Object value = value(extras, key); if (value == null) return fallback;
            if (!(value instanceof Integer)) throw new IllegalArgumentException("Invalid " + key);
            return (Integer) value;
        }
    }
}
