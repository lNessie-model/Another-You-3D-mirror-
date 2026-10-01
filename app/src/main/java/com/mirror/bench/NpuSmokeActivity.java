package com.mirror.bench;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Verify NPU access from an ordinary Android application, not the ADB shell UID. */
public final class NpuSmokeActivity extends Activity {
    private static native String runNative(String model,String rgb,int loops);
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view=new TextView(this); view.setText("Checking application NPU access"); setContentView(view);
        new Thread(this::check,"NpuSmoke").start();
    }
    private void check() {
        JSONObject result;
        try {
            System.loadLibrary("rknnrt"); System.loadLibrary("mirror_npu");
            File directory=new File(getFilesDir(),"npu-smoke");
            result=new JSONObject(runNative(new File(directory,"mobilenet.rknn").getAbsolutePath(),
                    new File(directory,"dog.rgb").getAbsolutePath(),1000));
            result.put("uid",android.os.Process.myUid()).put("runtime_scope","app-packaged copy of existing vendor runtime; unchanged system driver");
        } catch(Throwable error) {
            result=new JSONObject();
            try { result.put("status","error").put("error",error.toString()); } catch(Exception ignored) {}
        }
        try { Files.write(new File(getFilesDir(),"npu-app-probe.json").toPath(),result.toString(2).getBytes(StandardCharsets.UTF_8)); }
        catch(Exception error) { android.util.Log.e("MirrorBench","Cannot save app NPU check",error); }
    }
}
