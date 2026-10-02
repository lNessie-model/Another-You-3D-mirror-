package com.mirror.bench;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
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
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Visible, offline interaction entry point with the replaceable built-in GLB character. */
public final class MirrorActivity extends Activity {
    private static final int CAMERA_PERMISSION = 42;
    private static final int PANEL_CALIBRATION = 43;
    private static final int WIDTH = 640, HEIGHT = 480, CAPTURE_FPS = 25;
    private static final int[] RETRY_SECONDS = {1, 2, 4, 8, 30};
    // Also serializes native resources across Activity recreation; never acquired on the UI thread.
    private static final Semaphore HARDWARE_OWNER = new Semaphore(1);
    private static final Object STATUS_FILE_LOCK = new Object();
    private static final RuntimeStatusOrder STATUS_ORDER = new RuntimeStatusOrder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final InteractionController interaction = new InteractionController();
    private final ArrayDeque<JSONObject> events = new ArrayDeque<>();
    private GLSurfaceView surface;
    private InterlaceRenderer renderer;
    private TextView status;
    private MirrorSettings settings;
    private InputOptions options;
    private RuntimeWorker worker;
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
        permissionPrompted = savedState != null && savedState.getBoolean("permission_prompted", false);
        try { options = InputOptions.read(getIntent().getExtras(),
                (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0); }
        catch (IllegalArgumentException error) {
            configurationError = error.getMessage();
            options = InputOptions.defaults();
            interaction.setError();
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        renderer = new InterlaceRenderer(20, .3f, true);
        renderer.setRuntimeMode(true);
        renderer.setTargetFps(31);
        renderer.setViewSize(settings.viewWidth, settings.viewHeight);
        renderer.setPipeline(true);
        renderer.setCull(true, false);
        renderer.setPreblend(true, false);
        renderer.setMultiview(true, false);
        renderer.setExplicitLod(true);
        renderer.setDiscardDepth(true, false);
        renderer.setRuntimeAvatar(getAssets());
        renderer.setAvatarAsynchronous(!options.synchronousAvatar);
        renderer.setPanelCalibration(settings.panel);
        surface = new GLSurfaceView(this);
        surface.setEGLContextClientVersion(3);
        surface.setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        surface.setPreserveEGLContextOnPause(true);
        surface.setRenderer(renderer);
        FrameLayout root = new FrameLayout(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(4), dp(8), dp(4));
        bar.setBackgroundColor(0xb018202a);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(12);
        bar.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        Button maintain = new Button(this);
        maintain.setText("维护"); maintain.setOnClickListener(ignored -> showMaintenance());
        bar.addView(maintain, new LinearLayout.LayoutParams(-2, -2));
        Button exit = new Button(this);
        exit.setText("退出"); exit.setOnClickListener(ignored -> finish());
        bar.addView(exit, new LinearLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        root.addView(bar, position);
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true; renderer.resumeRuntimeAvatar();
        beginStatusSession();
        surface.onResume();
        main.removeCallbacks(tick); main.post(tick);
        startIfReady(true);
    }
    @Override protected void onPause() {
        resumed = false;
        main.removeCallbacks(tick);
        cancelWorker();
        renderer.setInteractiveFace(new float[4], FaceFrame.identity(), false);
        surface.onPause();
        super.onPause();
    }
    @Override protected void onDestroy() {
        main.removeCallbacks(tick); cancelWorker(); renderer.closeRuntimeAvatar(); super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("permission_prompted", permissionPrompted);
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
        if(requestCode==PANEL_CALIBRATION) {
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
            interaction.reset();
            if(!configurationError.isEmpty())runtimeError=configurationError;
            else if(options.needsCamera()&&!hasCameraPermission())runtimeError="摄像头未授权，请在维护页面重试";
            else if(!renderFault&&progressFault==null)runtimeError="";
            if(renderFault||progressFault!=null||!configurationError.isEmpty()
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
        if (!resumed || maintenance || calibrationOpen || restarting || renderFault || progressFault != null || isFinishing() || isDestroyed()
                || worker != null) return;
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
        worker.start();
    }
    private void reportPreflightFault(String error) {
        runtimeError = error; interaction.setError(); recordFaultState();
        if (error.equals(reportedPreflightError)) return;
        reportedPreflightError = error;
        // No input worker exists in these cases, so it cannot produce the usual status file.
        queueFaultStatus("MirrorPreflightFaultReport");
    }
    private void cancelWorker() { if (worker != null) worker.cancel(); }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            long now = SystemClock.elapsedRealtimeNanos();
            boolean updateText = now - lastUiTextNs >= 500_000_000L;
            if (updateText && !renderFault) {
                try {
                    String error = renderer.runtimeStatus().optString("error", "");
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
            now = SystemClock.elapsedRealtimeNanos();
            boolean active = !maintenance && !renderFault && progressFault == null && worker != null && !worker.cancelled
                    && snapshot.state() == InteractionController.State.INTERACTIVE && snapshot.facePresent();
            renderer.setInteractiveSnapshot(snapshot, active);
            renderer.setTargetFps(!maintenance && !renderFault && progressFault == null && worker != null && !worker.cancelled
                    && (snapshot.state() == InteractionController.State.INTERACTIVE
                    || snapshot.state() == InteractionController.State.GRACE) ? 31 : 10);
            if (snapshot.state() != lastEventState) {
                lastEventState = snapshot.state();
                recordEvent(now, "state", snapshot.state().name());
            }
            if (updateText) {
                lastUiTextNs = now;
                String error = configurationError.isEmpty() ? runtimeError : configurationError;
                String text = "镜中向导 · " + options.label() + "\n"
                        + (maintenance ? "维护中" : !renderFault&&!renderer.hasRuntimeFrame()?"正在加载角色":stateLabel(snapshot.state()));
                if (worker != null && worker.cancelled) text += " · 停止中，等待资源释放";
                else if (worker != null && worker.waitingForHardware) text += " · 等待上一运行释放设备";
                if (!error.isEmpty()) text += " · " + error;
                status.setText(text);
            }
            main.postDelayed(this, 33);
        }
    };

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
        details.setText("当前为镜中向导原型。20视点，原生竖屏输出。\n"
                + "pitch="+settings.panel.pitch()+" "+settings.panel.pitchUnits()+", tan="+settings.panel.tan()
                + ", phase="+settings.panel.phaseCycles()+"；实屏光学未确认。\n"+settings.warning+"\n"
                + "输入：" + options.label() + "\n" + maintenanceMetrics());
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
        Button calibrationButton=new Button(this);calibrationButton.setText("屏幕校准 / 20视点测试图");panel.addView(calibrationButton);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("魔镜维护").setView(panel)
                .setPositiveButton("保存并运行", (d, which) -> {
                    int fps = MirrorSettings.ANALYSIS_RATES[rates.getSelectedItemPosition()];
                    String preset = MirrorSettings.VIEW_PRESETS[views.getSelectedItemPosition()];
                    if (fps != settings.activeFps || !preset.equals(settings.viewPreset)) {
                        try {
                            MirrorSettings.save(this, fps, preset);
                            restarting = true; recreate();
                        } catch(RuntimeException invalid) {
                            android.widget.Toast.makeText(this,"未保存："+invalid.getMessage(),android.widget.Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("返回", null)
                .setNeutralButton("授权 / 重试", (d, which) -> {
                    if (renderFault) {
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
            startActivityForResult(new Intent(this,CalibrationActivity.class),PANEL_CALIBRATION);
            dialog.dismiss();
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(settings.writable);
    }
    private String maintenanceMetrics() {
        try {
            JSONObject gl = renderer.runtimeStatus();
            Stats current = stats;
            JSONObject values = current == null ? new JSONObject() : current.summary(SystemClock.elapsedRealtimeNanos());
            return String.format(Locale.ROOT, "画面 %.1f FPS；有效面捕 %.1f FPS；恢复 %d 次\n"
                    + "NPU：检测+478点；CPU：52表情+姿态\n"
                    + "诊断仅保存数值，不保存人脸图像。",
                    gl.optDouble("fps"), values.optDouble("face_fps", 0), recoveries);
        } catch (Exception error) { return "统计暂不可用"; }
    }
    private TextView label(String text) { TextView view = new TextView(this); view.setText(text); return view; }
    private Spinner spinner(String[] choices) {
        Spinner view = new Spinner(this);
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
        private volatile boolean cancelled;
        private volatile boolean waitingForHardware = true;
        private volatile FrameInput sourceOwned;
        private final AtomicBoolean sourceCloseScheduled = new AtomicBoolean();
        private final CountDownLatch sourceCloseFinished = new CountDownLatch(1);
        private final RuntimeProgressWatchdog watchdog = new RuntimeProgressWatchdog();
        private long sequence, modelTimestamp;
        RuntimeWorker(RuntimeStatusOrder.Epoch ownerEpoch) { super("MirrorRuntime");this.ownerEpoch=ownerEpoch; }
        void cancel() {
            cancelled = true; watchdog.cancel(); interrupt();
            closeCameraAsync();
        }
        private void closeCameraAsync() {
            FrameInput owned = sourceOwned;
            // CameraFrameSource protects borrowed Images. Replay and native inference stay worker-owned.
            if (!(owned instanceof CameraFrameSource) || !sourceCloseScheduled.compareAndSet(false, true)) return;
            Thread closer = new Thread(() -> {
                try { close(owned); }
                finally { sourceCloseFinished.countDown(); }
            }, "MirrorCameraStop");
            try { closer.start(); }
            catch (RuntimeException | Error failure) {
                sourceCloseFinished.countDown();
                Log.e("MirrorRuntime", "Cannot schedule camera stop; worker will close it", failure);
            }
        }
        private void awaitCameraStop() {
            if (!sourceCloseScheduled.get()) return;
            boolean interrupted = false;
            for (;;) {
                try { sourceCloseFinished.await(); break; }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) interrupt();
        }
        @Override public void run() {
            boolean ownsHardware = false;
            try {
                HARDWARE_OWNER.acquire(); ownsHardware = true; waitingForHardware = false;
                synchronized(interaction) {
                    if(cancelled||ownerEpoch!=statusEpoch)return;
                    interaction.reset();
                }
                int failures = 0;
                while (!cancelled) {
                    FrameInput source = null, reference = null;
                    YuvConverter converter = null, referenceConverter = null;
                    NpuFacePipeline npu = null;
                    try {
                        progress(RuntimeProgressWatchdog.Stage.OPENING_CAMERA);
                        source = options.input.equals("replay") ? replay() : new CameraFrameSource(MirrorActivity.this, WIDTH, HEIGHT, CAPTURE_FPS);
                        sourceOwned = source;
                        if (cancelled) { closeCameraAsync(); break; }
                        progress(RuntimeProgressWatchdog.Stage.INITIALIZING);
                        converter = new YuvConverter(MirrorActivity.this, WIDTH, HEIGHT, true, true);
                        if (options.input.equals("camera_replay")) {
                            reference = replay();
                            referenceConverter = new YuvConverter(MirrorActivity.this, WIDTH, HEIGHT, true, false);
                        }
                        npu = new NpuFacePipeline(MirrorActivity.this);
                        if (cancelled) break;
                        source.beginMeasurement();
                        if (reference != null) reference.beginMeasurement();
                        Stats attempt = new Stats(SystemClock.elapsedRealtimeNanos()); stats = attempt;
                        synchronized(interaction) {
                            if(cancelled||ownerEpoch!=statusEpoch)break;
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
                            int fps = active ? settings.activeFps : 3;
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
                        boolean currentOwner;
                        synchronized(interaction) {
                            currentOwner=!cancelled&&ownerEpoch==statusEpoch;
                            if(currentOwner){runtimeError=concise(error);interaction.setError();recoveries++;}
                        }
                        if (currentOwner) {
                            recordEvent(ownerEpoch,SystemClock.elapsedRealtimeNanos(), "fault", runtimeError);
                            Log.e("MirrorRuntime", "Runtime input failed", error);
                            writeFaultStatus(statusTicket(ownerEpoch));
                        }
                    } finally {
                        // Every borrowed Image was closed by convert(); only this worker owns these resources.
                        progress(RuntimeProgressWatchdog.Stage.RELEASING);
                        close(source); close(reference); close(npu); close(converter); close(referenceConverter);
                        sourceOwned = null;
                    }
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
                awaitCameraStop();
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
    private static void close(AutoCloseable resource) {
        if (resource == null) return;
        try { resource.close(); } catch (Exception error) { Log.e("MirrorRuntime", "Resource close failed", error); }
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
                .put("runtime_session_id",ticket.epoch().id()).put("runtime_epoch",ticket.epoch().number())
                .put("session_started_elapsed_ns",ticket.epoch().startedNs()).put("status_sequence",ticket.sequence())
                .put("status_capture_elapsed_ns",ticket.capturedNs())
                .put("input_description", options.label()).put("diagnostic_character", false)
                .put("updated_elapsed_ns", elapsedNs).put("updated_monotonic_ns", monotonicNs)
                .put("state", current == null ? "WAITING" : current.state().name())
                .put("result_age_ms", current == null ? -1 : current.resultAgeMs())
                .put("face_present", !renderFault && progressFault == null && current != null && current.facePresent()).put("error", runtimeError)
                .put("render_fault", renderFault).put("render_error", renderError)
                .put("processing_fault", progressFault != null).put("processing_timeout", progressFaultJson())
                .put("runtime_stage", worker == null ? "STOPPED" : worker.watchdog.stage().name())
                .put("recoveries", recoveries).put("analysis_active_target_fps", settings.activeFps).put("analysis_idle_target_fps", 3)
                .put("view_width", settings.viewWidth).put("view_height", settings.viewHeight).put("view_count", 20)
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
        private long converted, submissions, completions, faces;
        private double conversionMs, referenceMs, submissionMs, completeMs, receivedMs;
        private int landmarks;
        Stats(long now) { startedNs = now; }
        synchronized void converted(double conversion, double reference) {
            converted++; conversionMs += (conversion - conversionMs) / converted;
            referenceMs += (reference - referenceMs) / converted;
        }
        synchronized void submitted(double value) { submissions++; submissionMs += (value - submissionMs) / submissions; }
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
        final boolean synchronousAvatar;
        private InputOptions(String input, String recordId, double replayFps, int after, int duration, boolean synchronousAvatar) {
            this.input = input; this.recordId = recordId; this.replayFps = replayFps;
            blackoutAfterNs = after * 1_000_000_000L; blackoutDurationNs = duration * 1_000_000_000L;
            this.synchronousAvatar=synchronousAvatar;
        }
        static InputOptions defaults() { return new InputOptions("camera", "face-reference-stable-20261001-01", 24.369907, 0, 0, false); }
        static InputOptions read(Bundle extras, boolean debug) {
            if (extras == null) return defaults();
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
            return new InputOptions(input, record, fps, after, duration, Boolean.TRUE.equals(synchronous));
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
