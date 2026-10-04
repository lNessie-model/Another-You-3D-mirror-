package com.mirror.bench;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;
import org.json.JSONObject;

/** Reproducible native UI workload, not a claim about a future WebView or production page. */
final class UiLoadView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private long draws,cpuNs,first,last;
    UiLoadView(Context context) { super(context); setLayerType(View.LAYER_TYPE_NONE,null); }
    synchronized void beginMeasurement() { draws=0; cpuNs=0; first=0; last=0; }
    @Override protected void onDraw(Canvas canvas) {
        long cpu=android.os.Debug.threadCpuTimeNanos(),now=SystemClock.elapsedRealtimeNanos();
        float width=getWidth(),height=getHeight(),phase=now/1e9f;
        paint.setColor(0xe8151c27); canvas.drawRect(0,0,width,height,paint);
        paint.setColor(0xfff3f6fc); paint.setTextSize(32); canvas.drawText("互动魔镜  ·  UI 性能负载测试",28,48,paint);
        paint.setTextSize(23); paint.setColor(0xff9cb8d8);
        canvas.drawText("20 视点 / 面捕 / 状态信息 / 动态图表",28,83,paint);
        paint.setColor(0xff67d6af);
        for(int i=0;i<96;i++) {
            float bar=(.35f+.25f*(float)Math.sin(phase*1.8f+i*.3f))*(height-110);
            float x=28+i*(width-56)/96; canvas.drawRect(x,height-18-bar,x+(width-56)/96*.62f,height-18,paint);
        }
        synchronized(this) { if(first==0) first=now; last=now; draws++; cpuNs+=android.os.Debug.threadCpuTimeNanos()-cpu; }
        postInvalidateDelayed(100);
    }
    synchronized JSONObject summary() throws Exception {
        double elapsed=(last-first)/1e9;
        return new JSONObject().put("workload","Native Android translucent panel, text and 96 animated bars; request redraw every 100 ms")
                .put("draws",draws).put("draw_fps",elapsed>0?(draws-1)/elapsed:0)
                .put("canvas_caller_cpu_ms_total",cpuNs/1e6)
                .put("scope","Canvas caller CPU only; full UI/RenderThread/GPU costs included in process and whole-system measurements");
    }
}
