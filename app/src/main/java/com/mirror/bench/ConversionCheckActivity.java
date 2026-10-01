package com.mirror.bench;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Compare the two converters on identical recorded and synthetic NV21 frames. */
public final class ConversionCheckActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view=new TextView(this); view.setText("Comparing RGA and RenderScript colors"); setContentView(view);
        new Thread(this::check,"ConversionCheck").start();
    }
    private void check() {
        JSONObject result=new JSONObject(); JSONArray rows=new JSONArray();
        int width=640,height=480,size=width*height*3/2;
        try(YuvConverter rs=new YuvConverter(this,width,height); YuvConverter rga=new YuvConverter(this,width,height,true);
            RandomAccessFile file=new RandomAccessFile(new File(getFilesDir(),"recordings/face-reference-stable-20261001-01.nv21"),"r")) {
            byte[] nv21=new byte[size]; int[] expected=new int[width*height],actual=new int[width*height];
            for(int i=0;i<10;i++) {
                file.seek((long)i*80*size); file.readFully(nv21);
                rows.put(compare("recorded-"+(i*80),nv21,rs,rga,expected,actual,width,height));
            }
            // Include dark/light neutral values and chroma extremes to expose range,
            // channel order, clipping, and output alpha errors absent in a face image.
            for(int[] values:new int[][]{{16,128,128},{235,128,128},{0,128,128},{255,128,128},{81,90,240},{145,54,34},{41,240,110}}) {
                java.util.Arrays.fill(nv21,0,width*height,(byte)values[0]);
                for(int i=width*height;i<size;i+=2) { nv21[i]=(byte)values[2]; nv21[i+1]=(byte)values[1]; }
                rows.put(compare(java.util.Arrays.toString(values),nv21,rs,rga,expected,actual,width,height));
            }
            result.put("status","success").put("rga_version",RgaConvert.version()).put("frames",rows);
        } catch(Throwable error) {
            try { result.put("status","error").put("error",error.toString()).put("frames",rows); } catch(Exception ignored) {}
        }
        try {
            Files.write(new File(getFilesDir(),"conversion-check.json").toPath(),result.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch(Exception error) { android.util.Log.e("MirrorBench","Cannot save conversion check",error); }
    }
    private static JSONObject compare(String name,byte[] pixels,YuvConverter rs,YuvConverter rga,
            int[] expected,int[] actual,int width,int height) throws Exception {
        rs.convertNv21(pixels); rga.convertNv21(pixels);
        rs.bitmap.getPixels(expected,0,width,0,0,width,height); rga.bitmap.getPixels(actual,0,width,0,0,width,height);
        long sum=0,squared=0; int max=0,alphaMismatch=0;
        for(int i=0;i<expected.length;i++) {
            if((expected[i]>>>24)!=(actual[i]>>>24)) alphaMismatch++;
            for(int shift=0;shift<24;shift+=8) {
                int delta=Math.abs(((expected[i]>>>shift)&255)-((actual[i]>>>shift)&255));
                sum+=delta; squared+=(long)delta*delta; max=Math.max(max,delta);
            }
        }
        return new JSONObject().put("name",name).put("mean_rgb_absolute_error",sum/(double)(expected.length*3))
                .put("rmse",Math.sqrt(squared/(double)(expected.length*3))).put("max_rgb_error",max)
                .put("alpha_mismatches",alphaMismatch);
    }
}
