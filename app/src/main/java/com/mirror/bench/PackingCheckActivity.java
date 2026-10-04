package com.mirror.bench;

import android.app.Activity;
import android.os.Bundle;
import android.media.Image;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** Native packing correctness, using known fixtures plus identical live Camera2 planes. */
public final class PackingCheckActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view=new TextView(this); view.setText("Checking native YUV packing"); setContentView(view);
        new Thread(this::check,"PackingCheck").start();
    }
    private void check() {
        JSONObject result=new JSONObject();
        try {
            byte[] expected={1,2,3,4,5,6,7,8,31,21,32,22},actual=new byte[12];
            ByteBuffer y=direct(99,1,2,3,4,5,6,7,8); y.position(1);
            if(!RgaConvert.pack(4,2,y,4,1,direct(21,22),2,1,direct(31,32),2,1,actual)||!Arrays.equals(expected,actual))
                throw new AssertionError("Known planar fixture failed");
            ByteBuffer vu=direct(31,21,32,22),u=vu.duplicate(),v=vu.duplicate(); u.position(1); v.limit(3);
            if(!RgaConvert.pack(4,2,y,4,1,u,4,2,v,4,2,actual)||!Arrays.equals(expected,actual))
                throw new AssertionError("Known shared NV21 fixture failed");
            if(y.position()!=1||u.position()!=1||v.position()!=0||v.limit()!=3)
                throw new AssertionError("Native packing changed source buffer bounds");
            if(RgaConvert.pack(4,2,ByteBuffer.wrap(new byte[8]),4,1,direct(21,22),2,1,direct(31,32),2,1,actual))
                throw new AssertionError("Heap planes must use the Java fallback");
            boolean rejected=false;
            try { RgaConvert.pack(4,2,direct(1,2,3),4,1,direct(21,22),2,1,direct(31,32),2,1,actual); }
            catch(IllegalStateException expectedError) { rejected=true; }
            if(!rejected) throw new AssertionError("Truncated Y plane was accepted");
            int checked=0; byte[] javaPixels=new byte[640*480*3/2],nativePixels=new byte[javaPixels.length];
            YuvPacking.Workspace scratch=new YuvPacking.Workspace();
            try(CameraFrameSource camera=new CameraFrameSource(this,640,480,25)) {
                for(int i=0;i<24;i++) try(FrameInput.Frame frame=camera.take()) {
                    Image.Plane[] p=frame.image.getPlanes();
                    YuvPacking.toNv21(640,480,p[0].getBuffer(),p[0].getRowStride(),p[0].getPixelStride(),
                            p[1].getBuffer(),p[1].getRowStride(),p[1].getPixelStride(),p[2].getBuffer(),p[2].getRowStride(),p[2].getPixelStride(),javaPixels,scratch);
                    if(!RgaConvert.pack(640,480,p[0].getBuffer(),p[0].getRowStride(),p[0].getPixelStride(),
                            p[1].getBuffer(),p[1].getRowStride(),p[1].getPixelStride(),p[2].getBuffer(),p[2].getRowStride(),p[2].getPixelStride(),nativePixels)||!Arrays.equals(javaPixels,nativePixels))
                        throw new AssertionError("Native/live-plane byte mismatch at frame "+i);
                    checked++;
                }
            }
            result.put("status","success").put("known_fixtures",2).put("heap_fallback_checked",true)
                    .put("truncated_plane_rejected",true).put("live_frames_checked",checked).put("nv21_byte_mismatches",0);
        } catch(Throwable error) {
            try { result.put("status","error").put("error",error.toString()); } catch(Exception ignored) {}
        }
        try { Files.write(new File(getFilesDir(),"packing-check.json").toPath(),result.toString(2).getBytes(StandardCharsets.UTF_8)); }
        catch(Exception error) { android.util.Log.e("MirrorBench","Cannot save packing check",error); }
    }
    private static ByteBuffer direct(int... values) {
        ByteBuffer buffer=ByteBuffer.allocateDirect(values.length);
        for(int value:values) buffer.put((byte)value);
        buffer.flip(); return buffer;
    }
}
