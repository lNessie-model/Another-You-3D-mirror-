package com.mirror.bench;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Debug-only, synthetic real-Bitmap check; never opens a camera or writes images. */
public final class CameraTransformCheckActivity extends Activity {
    private static final String REPORT_NAME="camera-transform-check.json";
    // Opaque, distinct, non-gray colors avoid alpha-premultiplication ambiguities.
    private static final int[] FIRST={0xffe12a17,0xff14ad53,0xff236de9,0xffa42bc6,0xffe1b425,0xff18b8cf};
    private static final int[] SECOND={0xff37bd81,0xffc1256f,0xff8f541e,0xff259ce3,0xffd98327,0xff713fcb};
    // Explicit source-index oracles: clockwise 0/90/180/270, each without/with
    // horizontal reflection AFTER rotation. These do not call transform math.
    private static final int[][] ORACLES={
            {0,1,2,3,4,5},{2,1,0,5,4,3},
            {3,0,4,1,5,2},{0,3,1,4,2,5},
            {5,4,3,2,1,0},{3,4,5,0,1,2},
            {2,5,1,4,0,3},{5,2,4,1,3,0}};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if((getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)==0) {
            finish();
            return;
        }
        TextView text=new TextView(this);
        text.setText("Checking eight camera bitmap transforms");
        setContentView(text);
        JSONObject report=new JSONObject();
        try {
            report.put("schema_version",1).put("run_id",UUID.randomUUID().toString())
                    .put("started_elapsed_ns",SystemClock.elapsedRealtimeNanos())
                    .put("status","running").put("passed",false)
                    .put("scope","Synthetic Android ARGB_8888 bitmap pixels and ownership only; no camera, NPU or rendered face direction validation")
                    .put("report_file",REPORT_NAME);
            save(report);
            // Only eight tiny 3x2 fixtures. Keep this debug action synchronous so
            // no obsolete Activity worker can overwrite a later run's report.
            JSONObject checks=checkBitmaps();
            report.put("checks",checks).put("passed",checks.getBoolean("passed"))
                    .put("status","completed").put("finished_elapsed_ns",SystemClock.elapsedRealtimeNanos());
            save(report);
            text.setText((checks.getBoolean("passed")?"PASS":"FAIL")+"\n"+REPORT_NAME
                    +"\nrun_id="+report.getString("run_id"));
        } catch(Exception error) {
            Log.e("MirrorBench","Camera bitmap check failed",error);
            try {
                report.put("status","error").put("passed",false).put("error",error.toString())
                        .put("finished_elapsed_ns",SystemClock.elapsedRealtimeNanos());
                save(report);
            } catch(Exception writeError) {
                Log.e("MirrorBench","Cannot save camera bitmap failure report",writeError);
            }
            text.setText("FAIL\n"+error);
        }
    }

    private void save(JSONObject report) throws IOException {
        AtomicFile file=new AtomicFile(new File(getFilesDir(),REPORT_NAME));
        FileOutputStream stream=null;
        try {
            stream=file.startWrite();
            stream.write(report.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
        } catch(IOException|RuntimeException error) {
            if(stream!=null)file.failWrite(stream);
            throw error;
        }
    }

    /** Package-visible for host oracle plumbing tests; real Bitmap evidence comes from Android. */
    static JSONObject checkBitmaps() throws JSONException {
        JSONArray cases=new JSONArray();
        int checks=0,failures=0;
        for(int ordinal=0;ordinal<ORACLES.length;ordinal++) {
            JSONObject row=checkCase(ordinal);
            cases.put(row);
            checks+=row.getInt("assertions");
            failures+=row.getJSONArray("errors").length();
        }
        return new JSONObject().put("case_count",cases.length()).put("assertions",checks)
                .put("failure_count",failures).put("passed",cases.length()==8&&failures==0)
                .put("cases",cases);
    }

    private static JSONObject checkCase(int ordinal) throws JSONException {
        int angle=(ordinal/2)*90;
        boolean reflect=(ordinal&1)!=0,identity=ordinal==0;
        int expectedWidth=(angle==90||angle==270)?2:3,expectedHeight=6/expectedWidth;
        JSONObject row=new JSONObject().put("clockwise_degrees",angle).put("reflect_after_rotation",reflect)
                .put("expected_width",expectedWidth).put("expected_height",expectedHeight);
        Assertions assertions=new Assertions();
        Bitmap source=null;
        CameraBitmapNormalizer normalizer=null;
        try {
            source=Bitmap.createBitmap(3,2,Bitmap.Config.ARGB_8888);
            source.setPixels(FIRST,0,3,0,0,3,2);
            normalizer=new CameraBitmapNormalizer(3,2,angle,reflect);
            Bitmap first=normalizer.apply(source);
            assertions.expect((first==source)==identity,"first output source alias");
            assertions.expect(first.getWidth()==expectedWidth,"first output width");
            assertions.expect(first.getHeight()==expectedHeight,"first output height");
            int[] expected=permuted(FIRST,ORACLES[ordinal]),actual=pixels(first);
            row.put("first_expected_argb",unsigned(expected)).put("first_actual_argb",unsigned(actual));
            assertions.pixelsEqual(expected,actual,"first transformed pixels");
            assertions.pixelsEqual(FIRST,pixels(source),"source unchanged after first apply");

            source.setPixels(SECOND,0,3,0,0,3,2);
            Bitmap second=normalizer.apply(source);
            assertions.expect(second==first,"second apply reuses same output object");
            assertions.expect((second==source)==identity,"second output source alias");
            expected=permuted(SECOND,ORACLES[ordinal]);actual=pixels(second);
            row.put("second_expected_argb",unsigned(expected)).put("second_actual_argb",unsigned(actual))
                    .put("source_alias",second==source).put("output_reused",first==second)
                    .put("actual_width",second.getWidth()).put("actual_height",second.getHeight());
            assertions.pixelsEqual(expected,actual,"second transformed pixels");
            assertions.pixelsEqual(SECOND,pixels(source),"source unchanged after second apply");
            normalizer.close();normalizer.close();
            assertions.expect(!source.isRecycled(),"borrowed source survives close twice");
            assertions.pixelsEqual(SECOND,pixels(source),"borrowed source readable after close");
            assertions.expect(second.isRecycled()!=identity,"close recycles only owned output");
            row.put("source_recycled_after_close",source.isRecycled())
                    .put("output_recycled_after_close",second.isRecycled());
            boolean rejected=false;
            try {normalizer.apply(source);} catch(IllegalStateException expectedError){rejected=true;}
            assertions.expect(rejected,"apply after close rejected");
        } catch(Exception error) {
            assertions.errors.put("fixture exception: "+error);
        } finally {
            if(normalizer!=null)normalizer.close();
            if(source!=null&&!source.isRecycled())source.recycle();
        }
        return row.put("assertions",assertions.count).put("errors",assertions.errors)
                .put("passed",assertions.errors.length()==0);
    }

    private static int[] permuted(int[] source,int[] indices) {
        int[] result=new int[indices.length];
        for(int i=0;i<result.length;i++)result[i]=source[indices[i]];
        return result;
    }
    private static int[] pixels(Bitmap bitmap) {
        int width=bitmap.getWidth(),height=bitmap.getHeight();
        int[] result=new int[width*height];
        bitmap.getPixels(result,0,width,0,0,width,height);
        return result;
    }
    private static JSONArray unsigned(int[] values) {
        JSONArray result=new JSONArray();
        for(int value:values)result.put(Integer.toUnsignedLong(value));
        return result;
    }
    private static final class Assertions {
        int count;
        final JSONArray errors=new JSONArray();
        void expect(boolean passed,String detail){count++;if(!passed)errors.put(detail);}
        void pixelsEqual(int[] expected,int[] actual,String detail){
            expect(expected.length==actual.length,detail+" count");
            for(int i=0;i<Math.min(expected.length,actual.length);i++)
                expect(expected[i]==actual[i],detail+" pixel "+i);
        }
    }
}
