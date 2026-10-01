package com.mirror.bench;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** One consumer-owned frame and one replaceable pending frame, never an unbounded queue. */
final class CameraFrameSource implements FrameInput {
    private final HandlerThread thread=new HandlerThread("CameraAcquire");
    private final AtomicReference<Frame> pending=new AtomicReference<>();
    private final AtomicLong captures=new AtomicLong(),received=new AtomicLong(),replaced=new AtomicLong(),failures=new AtomicLong();
    private final CountDownLatch ready=new CountDownLatch(1);
    private volatile String error="";
    private volatile boolean closed;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private String id;
    private Range<Integer> fpsRange;
    private long startCaptures,startReceived,startReplaced,startFailures;

    @SuppressWarnings("MissingPermission")
    CameraFrameSource(Context context,int width,int height,int targetFps) throws Exception {
        thread.start(); Handler handler=new Handler(thread.getLooper());
        CameraManager manager=(CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
        String[] ids=manager.getCameraIdList();
        if(ids.length==0) throw new IllegalStateException("No Camera2 devices");
        id=ids[0];
        for(String candidate:ids) {
            Integer level=manager.getCameraCharacteristics(candidate).get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            if(level!=null&&level==CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL) { id=candidate; break; }
        }
        CameraCharacteristics characteristics=manager.getCameraCharacteristics(id);
        Size[] sizes=characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.YUV_420_888);
        if(!Arrays.asList(sizes).contains(new Size(width,height))) throw new IllegalArgumentException("Unsupported camera dimensions");
        Range<Integer>[] ranges=characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        fpsRange=ranges[0];
        for(Range<Integer> range:ranges)
            if(Math.abs(range.getUpper()-targetFps)<Math.abs(fpsRange.getUpper()-targetFps)) fpsRange=range;
        reader=ImageReader.newInstance(width,height,ImageFormat.YUV_420_888,4);
        reader.setOnImageAvailableListener(source -> {
            try {
                Image image=source.acquireLatestImage(); if(image==null) return;
                if(closed) { image.close(); return; }
                received.incrementAndGet();
                Frame old=pending.getAndSet(new Frame(image));
                if(old!=null) { old.image.close(); replaced.incrementAndGet(); }
            } catch(Throwable problem) { if(!closed) fail(problem.toString()); }
        },handler);
        manager.openCamera(id,new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice device) {
                camera=device;
                if(closed) { device.close(); return; }
                try {
                    device.createCaptureSession(Collections.singletonList(reader.getSurface()),new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession configured) {
                            session=configured;
                            if(closed) { configured.close(); return; }
                            try {
                                CaptureRequest.Builder request=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                request.addTarget(reader.getSurface());
                                request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,fpsRange);
                                configured.setRepeatingRequest(request.build(),new CameraCaptureSession.CaptureCallback() {
                                    @Override public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest r,TotalCaptureResult result) { captures.incrementAndGet(); }
                                    @Override public void onCaptureFailed(CameraCaptureSession s,CaptureRequest r,CaptureFailure f) { failures.incrementAndGet(); }
                                },handler);
                                ready.countDown();
                            } catch(Throwable problem) { fail(problem.toString()); }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) { fail("Camera session configuration failed"); }
                    },handler);
                } catch(Throwable problem) { fail(problem.toString()); }
            }
            @Override public void onDisconnected(CameraDevice device) { device.close(); if(!closed) fail("Camera disconnected"); }
            @Override public void onError(CameraDevice device,int code) { device.close(); fail("Camera error "+code); }
        },handler);
        if(!ready.await(20,TimeUnit.SECONDS)) { close(); throw new IllegalStateException("Camera open timeout"); }
        if(!error.isEmpty()) { close(); throw new IllegalStateException(error); }
    }
    private void fail(String reason) { error=reason; ready.countDown(); }
    @Override public Frame take() throws Exception {
        long deadline=SystemClock.elapsedRealtime()+3000;
        while(!closed&&SystemClock.elapsedRealtime()<deadline) {
            if(!error.isEmpty()) throw new IllegalStateException(error);
            Frame frame=pending.getAndSet(null); if(frame!=null) return frame;
            SystemClock.sleep(2);
        }
        throw new IllegalStateException("No camera frame for 3 seconds");
    }
    @Override public void beginMeasurement() {
        startCaptures=captures.get(); startReceived=received.get(); startReplaced=replaced.get(); startFailures=failures.get();
    }
    @Override public JSONObject summary(double seconds) throws Exception {
        return new JSONObject().put("camera_id",id).put("fps_range",fpsRange.toString())
                .put("capture_results",captures.get()-startCaptures).put("received_images",received.get()-startReceived)
                .put("capture_fps",(captures.get()-startCaptures)/seconds).put("received_fps",(received.get()-startReceived)/seconds)
                .put("pending_frames_replaced",replaced.get()-startReplaced).put("capture_failures",failures.get()-startFailures)
                .put("error",error).put("queue_policy","acquireLatestImage plus one replaceable pending frame");
    }
    @Override public void close() {
        closed=true;
        if(session!=null) session.close();
        if(camera!=null) camera.close();
        thread.quitSafely();
        try { thread.join(2000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        Frame last=pending.getAndSet(null); if(last!=null) last.image.close();
        if(reader!=null) reader.close();
    }
}
