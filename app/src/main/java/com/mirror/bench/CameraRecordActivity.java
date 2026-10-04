package com.mirror.bench;

import android.app.Activity;
import android.media.Image;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Preserve decoded camera pixels and frame timestamps for reproducible offline tests. */
public final class CameraRecordActivity extends Activity {
    private volatile boolean stopped;
    private TextView label;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        label=new TextView(this); label.setTextSize(28); label.setText("Preparing camera recording");
        setContentView(label);
        new Thread(this::record,"CameraRecord").start();
    }
    private void record() {
        String name=getIntent().getStringExtra("record_id");
        if(name==null||!name.matches("[a-zA-Z0-9_-]+")) name="camera-"+System.currentTimeMillis();
        int width=getIntent().getIntExtra("camera_width",640),height=getIntent().getIntExtra("camera_height",480);
        int seconds=getIntent().getIntExtra("seconds",60),fps=getIntent().getIntExtra("capture_fps",25);
        File dir=new File(getExternalFilesDir(null),"recordings");
        JSONObject result=new JSONObject(); CameraFrameSource camera=null;
        try {
            if(seconds<1||seconds>120) throw new IllegalArgumentException("Duration must be 1..120 seconds");
            if(!dir.isDirectory()&&!dir.mkdirs()) throw new IllegalStateException("Cannot create recording directory");
            File raw=new File(dir,name+".nv21"),times=new File(dir,name+"-timestamps.csv");
            if(raw.exists()||times.exists()) throw new IllegalStateException("Recording already exists: "+name);
            byte[] pixels=new byte[width*height*3/2];
            YuvPacking.Workspace packing=new YuvPacking.Workspace();
            camera=new CameraFrameSource(this,width,height,fps);
            for(int i=0;i<10&&!stopped;i++) camera.take().image.close();
            camera.beginMeasurement();
            long start=SystemClock.elapsedRealtimeNanos(),first=0,last=0,nextProgress=start;
            int frames=0;
            try(BufferedOutputStream output=new BufferedOutputStream(new FileOutputStream(raw),1024*1024);
                BufferedWriter timestamps=Files.newBufferedWriter(times.toPath(),StandardCharsets.UTF_8)) {
                timestamps.write("frame_index,camera_timestamp_ns,received_elapsed_ns\n");
                while(!stopped&&SystemClock.elapsedRealtimeNanos()-start<seconds*1_000_000_000L) {
                    CameraFrameSource.Frame frame=camera.take();
                    long stamp;
                    try {
                        Image image=frame.image; stamp=image.getTimestamp();
                        Image.Plane[] p=image.getPlanes();
                        YuvPacking.toNv21(width,height,p[0].getBuffer(),p[0].getRowStride(),p[0].getPixelStride(),
                                p[1].getBuffer(),p[1].getRowStride(),p[1].getPixelStride(),
                                p[2].getBuffer(),p[2].getRowStride(),p[2].getPixelStride(),pixels,packing);
                    } finally { frame.image.close(); }
                    output.write(pixels);
                    timestamps.write(frames+","+stamp+","+frame.receivedNs+"\n");
                    if(frames==0) first=stamp;
                    last=stamp; frames++;
                    long now=SystemClock.elapsedRealtimeNanos();
                    if(now>=nextProgress) {
                        int count=frames; long elapsed=(now-start)/1_000_000_000L;
                        runOnUiThread(()->label.setText("Recording: "+elapsed+" / "+seconds+" seconds\nFrames: "+count));
                        nextProgress=now+1_000_000_000L;
                    }
                }
            }
            double elapsed=(SystemClock.elapsedRealtimeNanos()-start)/1e9;
            result.put("status",stopped?"interrupted":"success").put("width",width).put("height",height)
                    .put("frames",frames).put("elapsed_s",elapsed).put("recorded_fps",frames/elapsed)
                    .put("first_camera_timestamp_ns",first).put("last_camera_timestamp_ns",last)
                    .put("timestamp_fps",frames<2?0:(frames-1)*1e9/(last-first))
                    .put("raw_bytes",raw.length()).put("pixel_format","nv21").put("audio",false)
                    .put("camera",camera.summary(elapsed));
        } catch(Throwable error) {
            Log.e("MirrorBench","Camera recording failed",error);
            try { result.put("status","error").put("error",error.toString()); } catch(Exception ignored) {}
        } finally { if(camera!=null) camera.close(); }
        try {
            result.put("record_id",name).put("created_epoch_ms",System.currentTimeMillis());
            File metadata=new File(dir,name+".json");
            if(metadata.exists()) metadata=new File(dir,name+"-attempt-"+System.currentTimeMillis()+".json");
            Files.write(metadata.toPath(),result.toString(2).getBytes(StandardCharsets.UTF_8));
            Log.i("MirrorBench","RECORDING "+result);
            String status=result.optString("status");
            runOnUiThread(()->label.setText("Recording finished: "+status));
        } catch(Exception error) { Log.e("MirrorBench","Saving recording metadata failed",error); }
    }
    @Override public void onDestroy() { stopped=true; super.onDestroy(); }
}
