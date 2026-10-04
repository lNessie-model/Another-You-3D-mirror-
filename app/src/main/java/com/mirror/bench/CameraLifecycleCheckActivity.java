package com.mirror.bench;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real-device regression for failed constructors and repeated camera ownership. */
public final class CameraLifecycleCheckActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label=new TextView(this); label.setText("Camera lifecycle regression check"); setContentView(label);
        new Thread(this::check,"CameraLifecycleCheck").start();
    }
    private void check() {
        JSONObject result=new JSONObject(); JSONArray errors=new JSONArray(),cycles=new JSONArray();
        int before=cameraThreads(),expectedFailures=0,afterFailures=before,completed=0;
        try {
            result.put("unsupported_attempts",30).put("threads_before",before);
            for(int i=0;i<30;i++) {
                CameraFrameSource unexpected=null;
                try {
                    // Positive, deliberately unsupported dimensions exercise constructor cleanup.
                    unexpected=new CameraFrameSource(this,641,479,25);
                    errors.put("Unsupported dimensions were accepted at attempt "+i);
                } catch(IllegalArgumentException expected) {
                    if(expected.getMessage()!=null&&expected.getMessage().contains("Unsupported camera dimensions")) expectedFailures++;
                    else errors.put("Unexpected validation error: "+expected);
                } catch(Throwable error) { errors.put("Unexpected constructor error: "+error); }
                finally { if(unexpected!=null) unexpected.close(); }
            }
            afterFailures=awaitThreadCount(before);
            if(afterFailures!=before) errors.put("Failed constructors leaked CameraAcquire threads: "+before+" -> "+afterFailures);
            for(int i=0;i<3;i++) {
                JSONObject cycle=new JSONObject(); CameraFrameSource source=null;
                int cycleBefore=cameraThreads(); long started=SystemClock.elapsedRealtimeNanos();
                try {
                    source=new CameraFrameSource(this,640,480,25);
                    cycle.put("open_ms",(SystemClock.elapsedRealtimeNanos()-started)/1e6);
                    try(FrameInput.Frame frame=source.take()) {
                        if(frame.image==null||frame.image.getWidth()!=640||frame.image.getHeight()!=480)
                            throw new IllegalStateException("Expected an actual 640x480 camera Image");
                        cycle.put("frame_timestamp_ns",frame.image.getTimestamp());
                        cycle.put("frame_bytes",frame.image.getPlanes()[0].getBuffer().remaining());
                        if(i==1) {
                            // Closing the producer must not invalidate a consumer's in-use Image.
                            var plane=frame.image.getPlanes()[0].getBuffer();
                            int first=plane.get(plane.position()),last=plane.get(plane.limit()-1);
                            source.close();
                            if(frame.image.getWidth()!=640||frame.image.getHeight()!=480||
                                    plane.get(plane.position())!=first||plane.get(plane.limit()-1)!=last)
                                throw new IllegalStateException("Closing source invalidated its held frame");
                            cycle.put("held_frame_readable_after_source_close",true);
                            frame.close(); // The implicit second close must not release the reader twice.
                        }
                    }
                    if(i==2) checkWaitingConsumer(source,cycle);
                    long closing=SystemClock.elapsedRealtimeNanos(); source.close(); source.close();
                    cycle.put("double_close_ms",(SystemClock.elapsedRealtimeNanos()-closing)/1e6);
                    int cycleAfter=awaitThreadCount(cycleBefore);
                    cycle.put("threads_before",cycleBefore).put("threads_after",cycleAfter);
                    if(cycleAfter!=cycleBefore) throw new IllegalStateException("Successful capture leaked CameraAcquire threads");
                    // A closed source must reject take immediately, rather than wait its 3s timeout.
                    long taking=SystemClock.elapsedRealtimeNanos(); boolean rejected=false;
                    try(FrameInput.Frame ignored=source.take()) { }
                    catch(IllegalStateException expected) { rejected=true; }
                    double closedTakeMs=(SystemClock.elapsedRealtimeNanos()-taking)/1e6;
                    cycle.put("closed_take_rejected",rejected).put("closed_take_ms",closedTakeMs);
                    if(!rejected||closedTakeMs>500) throw new IllegalStateException("Closed take did not promptly reject");
                    cycle.put("passed",true); completed++;
                } catch(Throwable error) { cycle.put("passed",false).put("error",error.toString()); errors.put("Cycle "+i+": "+error); }
                finally { if(source!=null) source.close(); }
                cycles.put(cycle);
            }
        } catch(Throwable error) { errors.put(error.toString()); }
        try {
            int after=awaitThreadCount(before);
            if(after!=before) errors.put("Final CameraAcquire thread count changed: "+before+" -> "+after);
            boolean passed=expectedFailures==30&&completed==3&&before==afterFailures&&before==after&&errors.length()==0;
            result.put("expected_constructor_failures",expectedFailures).put("threads_after_failures",afterFailures)
                    .put("capture_cycles",cycles).put("completed_capture_cycles",completed).put("threads_after",after)
                    .put("passed",passed).put("status",passed?"success":"failed").put("errors",errors);
            Files.write(new File(getFilesDir(),"camera-lifecycle-check.json").toPath(),result.toString(2).getBytes(StandardCharsets.UTF_8));
            Log.i("MirrorBench","CAMERA_LIFECYCLE "+result);
        } catch(Throwable error) { Log.e("MirrorBench","Cannot save camera lifecycle check",error); }
    }
    private static void checkWaitingConsumer(CameraFrameSource source,JSONObject result) throws Exception {
        var closing=new java.util.concurrent.atomic.AtomicBoolean();
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread consumer=new Thread(()->{
            try {
                for(;;) try(FrameInput.Frame ignored=source.take()) { }
            } catch(IllegalStateException expected) {
                if(!closing.get()) failure.set(expected);
            } catch(Throwable error) { failure.set(error); }
        },"CameraCloseWaiter");
        consumer.start();
        try {
            long deadline=SystemClock.elapsedRealtime()+2000;
            boolean waiting=false;
            while(consumer.isAlive()&&SystemClock.elapsedRealtime()<deadline) {
                Thread.State state=consumer.getState();
                if(state==Thread.State.WAITING||state==Thread.State.TIMED_WAITING) { waiting=true; break; }
                SystemClock.sleep(1);
            }
            closing.set(true);
            long closedAt=SystemClock.elapsedRealtimeNanos(); source.close();
            consumer.join(500);
            double stopMs=(SystemClock.elapsedRealtimeNanos()-closedAt)/1e6;
            result.put("waiting_take_observed",waiting).put("waiting_take_stopped",!consumer.isAlive())
                    .put("waiting_take_close_ms",stopMs);
            if(!waiting||consumer.isAlive()||failure.get()!=null)
                throw new IllegalStateException("Closing source did not wake its waiting consumer",failure.get());
        } finally {
            closing.set(true); source.close();
            if(consumer.isAlive()) { consumer.interrupt(); consumer.join(3500); }
        }
    }
    private static int cameraThreads() {
        int count=0;
        for(Thread thread:Thread.getAllStackTraces().keySet())
            if(thread.isAlive()&&"CameraAcquire".equals(thread.getName())) count++;
        return count;
    }
    private static int awaitThreadCount(int expected) {
        long deadline=SystemClock.elapsedRealtime()+2000;
        int count;
        do {
            count=cameraThreads(); if(count==expected) return count;
            SystemClock.sleep(20);
        } while(SystemClock.elapsedRealtime()<deadline);
        return cameraThreads();
    }
}
