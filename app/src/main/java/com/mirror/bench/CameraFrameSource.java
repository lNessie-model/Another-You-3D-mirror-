package com.mirror.bench;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** One consumer-owned frame and one replaceable pending frame, never an unbounded queue. */
final class CameraFrameSource implements FrameInput {
    private final HandlerThread thread=new HandlerThread("CameraAcquire");
    private final Object state=new Object();
    private Frame pending;
    private int acquiredImages;
    private final AtomicLong captures=new AtomicLong(),received=new AtomicLong(),replaced=new AtomicLong(),failures=new AtomicLong();
    private final CountDownLatch ready=new CountDownLatch(1);
    private volatile String error="";
    private volatile boolean closed;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private String id;
    private String fingerprint;
    private Range<Integer> fpsRange;
    private long startCaptures,startReceived,startReplaced,startFailures;

    @SuppressWarnings("MissingPermission")
    CameraFrameSource(Context context,int width,int height,int targetFps) throws Exception {
        try {
            if(Looper.myLooper()==context.getMainLooper())
                throw new IllegalStateException("Construct CameraFrameSource on a worker thread");
            if(width<=0||height<=0||targetFps<=0) throw new IllegalArgumentException("Invalid camera dimensions or frame rate");
            CameraManager manager=(CameraManager)context.getSystemService(Context.CAMERA_SERVICE);
            String[] ids=manager.getCameraIdList();
            if(ids.length==0) throw new IllegalStateException("No Camera2 devices");
            id=ids[0];
            for(String candidate:ids) {
                Integer level=manager.getCameraCharacteristics(candidate).get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                if(level!=null&&level==CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL) { id=candidate; break; }
            }
            CameraCharacteristics characteristics=manager.getCameraCharacteristics(id);
            var configurations=characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes=configurations==null?null:configurations.getOutputSizes(ImageFormat.YUV_420_888);
            if(sizes==null||!Arrays.asList(sizes).contains(new Size(width,height)))
                throw new IllegalArgumentException("Unsupported camera dimensions");
            // Descriptor binding, not a USB serial number: identical devices can share this identity.
            String[] sizeNames=Arrays.stream(sizes).map(Size::toString).sorted().toArray(String[]::new);
            String description=id+"|"+characteristics.get(CameraCharacteristics.LENS_FACING)
                    +"|"+characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
                    +"|"+characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    +"|"+String.join(",",sizeNames);
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(description.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder encoded=new StringBuilder(64);for(byte b:digest)encoded.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            fingerprint=encoded.toString();
            Range<Integer>[] ranges=characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if(ranges==null||ranges.length==0) throw new IllegalStateException("Camera exposes no frame rate ranges");
            fpsRange=ranges[0];
            for(Range<Integer> range:ranges)
                if(Math.abs(range.getUpper()-targetFps)<Math.abs(fpsRange.getUpper()-targetFps)) fpsRange=range;

            // Validate before creating a thread or reader. All later failures share the cleanup below.
            thread.start(); Handler acquireHandler=new Handler(thread.getLooper());
            // This looper outlives the source, so late open/configure callbacks can release their objects.
            Handler lifecycleHandler=new Handler(context.getMainLooper());
            reader=ImageReader.newInstance(width,height,ImageFormat.YUV_420_888,4);
            reader.setOnImageAvailableListener(this::onImageAvailable,acquireHandler);
            manager.openCamera(id,new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) { opened(device,acquireHandler,lifecycleHandler); }
                @Override public void onDisconnected(CameraDevice device) { deviceFailed(device,"Camera disconnected"); }
                @Override public void onError(CameraDevice device,int code) { deviceFailed(device,"Camera error "+code); }
            },lifecycleHandler);
            if(!ready.await(20,TimeUnit.SECONDS)) throw new IllegalStateException("Camera open timeout");
            synchronized(state) {
                if(!error.isEmpty()) throw new IllegalStateException(error);
                if(closed) throw new IllegalStateException("Camera closed during initialization");
            }
        } catch(Throwable failure) {
            try { close(); } catch(Throwable cleanup) { failure.addSuppressed(cleanup); }
            if(failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw failure;
        }
    }
    private void opened(CameraDevice device,Handler acquireHandler,Handler lifecycleHandler) {
        synchronized(state) {
            if(closed) { closeCallbackResource(device); return; }
            camera=device;
            try {
                device.createCaptureSession(Collections.singletonList(reader.getSurface()),new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession configured) {
                        synchronized(state) {
                            if(closed) { closeCallbackResource(configured); return; }
                            session=configured;
                            try {
                                CaptureRequest.Builder request=device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                request.addTarget(reader.getSurface());
                                request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,fpsRange);
                                configured.setRepeatingRequest(request.build(),new CameraCaptureSession.CaptureCallback() {
                                    @Override public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest r,TotalCaptureResult result) {
                                        if(!closed) captures.incrementAndGet();
                                    }
                                    @Override public void onCaptureFailed(CameraCaptureSession s,CaptureRequest r,CaptureFailure f) {
                                        if(!closed) failures.incrementAndGet();
                                    }
                                },acquireHandler);
                                ready.countDown();
                            } catch(Throwable problem) { fail(problem.toString()); }
                        }
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession configured) {
                        closeCallbackResource(configured); fail("Camera session configuration failed");
                    }
                },lifecycleHandler);
            } catch(Throwable problem) { fail(problem.toString()); }
        }
    }
    private void deviceFailed(CameraDevice device,String reason) {
        synchronized(state) { if(camera==device) camera=null; fail(reason); }
        closeCallbackResource(device);
    }
    private void closeCallbackResource(AutoCloseable resource) {
        try { resource.close(); }
        catch(Throwable problem) { fail(problem.toString()); Log.e("MirrorBench","Camera callback cleanup failed",problem); }
    }
    private void onImageAvailable(ImageReader source) {
        synchronized(state) {
            if(closed) return;
            try {
                Image image=source.acquireLatestImage(); if(image==null) return;
                Frame next;
                try { next=new Frame(image,this::frameClosed); }
                catch(Throwable problem) { image.close(); throw problem; }
                acquiredImages++;
                Frame old=pending; pending=next;
                received.incrementAndGet();
                if(old!=null) { old.close(); replaced.incrementAndGet(); }
                state.notifyAll();
            } catch(Throwable problem) { fail(problem.toString()); }
        }
    }
    private void frameClosed() {
        ImageReader release=null;
        synchronized(state) {
            acquiredImages--;
            if(closed&&acquiredImages==0) { release=reader; reader=null; }
        }
        if(release!=null) release.close();
    }
    private void fail(String reason) {
        synchronized(state) {
            if(!closed) { error=reason; ready.countDown(); state.notifyAll(); }
        }
    }
    @Override public Frame take() throws Exception {
        long deadline=SystemClock.elapsedRealtime()+3000;
        synchronized(state) {
            for(;;) {
                if(closed) throw new IllegalStateException("Camera is closed");
                if(!error.isEmpty()) throw new IllegalStateException(error);
                if(pending!=null) { Frame frame=pending; pending=null; return frame; }
                long remaining=deadline-SystemClock.elapsedRealtime();
                if(remaining<=0) throw new IllegalStateException("No camera frame for 3 seconds");
                state.wait(remaining);
            }
        }
    }
    @Override public void beginMeasurement() {
        startCaptures=captures.get(); startReceived=received.get(); startReplaced=replaced.get(); startFailures=failures.get();
    }
    String cameraId(){return id;}
    String fingerprint(){return fingerprint;}
    @Override public JSONObject summary(double seconds) throws Exception {
        return new JSONObject().put("camera_id",id).put("camera_descriptor_sha256",fingerprint).put("fps_range",fpsRange.toString())
                .put("capture_results",captures.get()-startCaptures).put("received_images",received.get()-startReceived)
                .put("capture_fps",(captures.get()-startCaptures)/seconds).put("received_fps",(received.get()-startReceived)/seconds)
                .put("pending_frames_replaced",replaced.get()-startReplaced).put("capture_failures",failures.get()-startFailures)
                .put("error",error).put("queue_policy","acquireLatestImage plus one replaceable pending frame");
    }
    @Override public void close() {
        CameraCaptureSession releaseSession; CameraDevice releaseCamera; ImageReader releaseReader; Frame last;
        synchronized(state) {
            if(closed) return;
            closed=true; ready.countDown(); state.notifyAll();
            releaseSession=session; session=null; releaseCamera=camera; camera=null;
            last=pending; pending=null;
            // ImageReader.close invalidates even the consumer's acquired Image/ByteBuffers.
            releaseReader=acquiredImages==0?reader:null;
            if(releaseReader!=null) reader=null;
        }
        RuntimeException failure=null;
        failure=release(releaseSession,failure); failure=release(releaseCamera,failure);
        failure=release(last,failure); failure=release(releaseReader,failure);
        thread.quitSafely();
        boolean interrupted=false;
        if(Thread.currentThread()!=thread) {
            long deadline=SystemClock.elapsedRealtime()+2000;
            while(thread.isAlive()&&SystemClock.elapsedRealtime()<deadline) {
                try { thread.join(Math.max(1,deadline-SystemClock.elapsedRealtime())); }
                catch(InterruptedException ignored) { interrupted=true; }
            }
            if(thread.isAlive()) {
                IllegalStateException timeout=new IllegalStateException("CameraAcquire did not stop within 2 seconds");
                if(failure==null) failure=timeout; else failure.addSuppressed(timeout);
            }
        }
        if(interrupted) Thread.currentThread().interrupt();
        if(failure!=null) throw failure;
    }
    private static RuntimeException release(AutoCloseable resource,RuntimeException failure) {
        if(resource==null) return failure;
        try { resource.close(); }
        catch(Throwable problem) {
            if(failure==null) failure=new IllegalStateException("Camera resource cleanup failed",problem);
            else failure.addSuppressed(problem);
        }
        return failure;
    }
}
