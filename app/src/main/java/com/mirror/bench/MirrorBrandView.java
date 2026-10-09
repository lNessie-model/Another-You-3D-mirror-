package com.mirror.bench;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.view.animation.LinearInterpolator;

/** A finite engraved portal reveal, with time for the wordmark to settle. */
final class MirrorBrandView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path oval = new Path(), segment = new Path(), crest = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final Typeface serif = Typeface.create("serif", Typeface.NORMAL), sans = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Runnable unveiled, finished;
    private final ViewGroup home;
    private final int[] location = new int[2], origin = new int[2];
    private final float[] homeBaselines = new float[3], homeSizes = new float[3];
    private boolean homeResolved;
    private ValueAnimator animator;
    private float progress;
    private float cx, cy, rx, ry;
    private Shader halo;
    private boolean contentUnveiled;
    MirrorBrandView(Context context, ViewGroup home, Runnable unveiled, Runnable finished) {
        super(context); this.home = home; this.unveiled = unveiled; this.finished = finished;
        setBackgroundColor(MirrorTheme.BACKGROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    void begin() {
        animator = ValueAnimator.ofFloat(0f, 1f); animator.setDuration(2100);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(value -> {
            progress = (float) value.getAnimatedValue();
            if (!contentUnveiled && progress >= .86f) { contentUnveiled = true; unveiled.run(); }
            setAlpha(1f - phase(.88f, 1f));
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { finished.run(); }
        });
        animator.start();
    }
    void stop() {
        if (animator != null) {
            animator.removeAllListeners(); animator.removeAllUpdateListeners(); animator.cancel(); animator = null;
        }
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        cx = w / 2f; cy = h * .36f;
        rx = Math.min(w * .245f, h * .155f); ry = rx * 1.52f;
        oval.reset(); oval.moveTo(cx,cy-ry);
        // Two measured halves open from the crown, like a pair of engraved doors.
        oval.arcTo(cx-rx,cy-ry,cx+rx,cy+ry,-90,180,false);
        oval.arcTo(cx-rx,cy-ry,cx+rx,cy+ry,90,180,false); oval.close();
        measure.setPath(oval, true);
        halo = new RadialGradient(cx,cy,ry*1.30f,new int[]{0xff241a20,MirrorTheme.BACKGROUND},null,Shader.TileMode.CLAMP);
    }
    private float phase(float start,float end) {
        float t=Math.max(0f,Math.min(1f,(progress-start)/(end-start)));return t*t*(3f-2f*t);
    }
    private static float mix(float a,float b,float t) { return a+(b-a)*t; }
    private void resolveHome() {
        if (homeResolved || home == null || home.getWidth() <= 1 || home.getChildCount() < 3) return;
        getLocationInWindow(origin);
        for(int i=0;i<3;i++) {
            if (!(home.getChildAt(i) instanceof TextView label) || label.getHeight() <= 1) return;
            label.getLocationInWindow(location);homeBaselines[i]=location[1]-origin[1]+label.getBaseline();homeSizes[i]=label.getTextSize();
        }
        homeResolved=true;
    }
    @Override protected void onDraw(Canvas canvas) {
        float h=getHeight(),d=getResources().getDisplayMetrics().density;
        resolveHome();float settle=homeResolved?phase(.66f,.88f):0f;
        float portalCy=mix(cy,h*.5f,settle),portalRx=mix(rx,getWidth()*.44f,settle),portalRy=mix(ry,h*.445f,settle);
        paint.setStyle(Paint.Style.FILL);paint.setShader(halo);paint.setAlpha((int)(255*phase(0f,.25f)));
        canvas.drawRect(0,0,getWidth(),h,paint);paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(d*.65f);
        canvas.save();canvas.translate(cx,portalCy);canvas.scale(portalRx/rx,portalRy/ry);canvas.translate(-cx,-cy);
        paint.setColor(0x447c3548);paint.setAlpha((int)(68*phase(.04f,.30f)));
        canvas.drawOval(cx-rx-9*d,cy-ry-12*d,cx+rx+9*d,cy+ry+12*d,paint);
        paint.setColor(0x667f7162);paint.setAlpha((int)(102*phase(.04f,.30f)));canvas.drawPath(oval,paint);
        float sweep=phase(.06f,.60f),length=measure.getLength();
        segment.reset();measure.getSegment(0,length*.5f*sweep,segment,true);
        measure.getSegment(length*(1f-.5f*sweep),length,segment,true);
        paint.setStrokeWidth(d);paint.setColor(MirrorTheme.GOLD);paint.setAlpha(220);canvas.drawPath(segment,paint);
        canvas.restore();
        // Four restrained registration marks and a central signet echo the actual frame.
        float seal=phase(.22f,.53f);paint.setAlpha((int)(220*seal));float unit=8*d;
        for(int i=0;i<4;i++) {
            double angle=i*Math.PI/2;float x=cx+(float)Math.cos(angle)*(portalRx+18*d),y=portalCy+(float)Math.sin(angle)*(portalRy+18*d);
            canvas.drawCircle(x,y,1.5f*d,paint);
        }
        canvas.save();canvas.translate(cx,mix(cy,h*.16f,settle));float sealScale=(.92f+.08f*seal)*(1f-.35f*settle);canvas.scale(sealScale,sealScale);
        paint.setStrokeWidth(d*.8f);canvas.drawCircle(0,0,unit*2.6f,paint);
        canvas.drawCircle(0,0,unit*2.1f,paint);
        crest.reset();crest.moveTo(0,-unit*1.35f);crest.lineTo(unit*.68f,0);crest.lineTo(0,unit*1.35f);crest.lineTo(-unit*.68f,0);crest.close();canvas.drawPath(crest,paint);
        canvas.drawLine(-unit*4.5f,0,-unit*3f,0,paint);canvas.drawLine(unit*3f,0,unit*4.5f,0,paint);canvas.restore();
        float crestY=cy-ry-28*d;
        paint.setAlpha((int)(210*seal));canvas.drawLine(cx,crestY-6*d,cx,crestY+6*d,paint);canvas.drawLine(cx-4*d,crestY,cx+4*d,crestY,paint);
        paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(serif);
        paint.setTextSize(mix(MirrorTheme.dp(getContext(),29),homeResolved?homeSizes[0]:29*d,settle));
        float available = getWidth() * .6f;
        if(paint.measureText("ANOTHER YOU")>available)paint.setTextSize(paint.getTextSize()*available/paint.measureText("ANOTHER YOU"));
        float title=phase(.30f,.58f);
        paint.setColor(MirrorTheme.GOLD);paint.setAlpha((int)(255*title));
        canvas.drawText("ANOTHER YOU",cx,mix(h*.68f+(1-title)*8*d,homeResolved?homeBaselines[0]:h*.68f,settle),paint);
        paint.setTypeface(sans);
        paint.setTextSize(mix(MirrorTheme.dp(getContext(),19),homeResolved?homeSizes[1]:19*d,settle));paint.setColor(MirrorTheme.INK);
        float name=phase(.43f,.67f);paint.setAlpha((int)(255*name));
        canvas.drawText("另一个你",cx,mix(h*.725f+(1-name)*6*d,homeResolved?homeBaselines[1]:h*.725f,settle),paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(d*.65f);paint.setColor(MirrorTheme.METAL);paint.setAlpha((int)(160*name));
        paint.setAlpha((int)(160*name*(1f-settle)));float rule=rx*.60f*name;canvas.drawLine(cx-rule,h*.765f,cx+rule,h*.765f,paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(mix(MirrorTheme.dp(getContext(),12),homeResolved?homeSizes[2]:12*d,settle));paint.setColor(MirrorTheme.MUTED);
        paint.setAlpha((int)(255*phase(.53f,.73f)));canvas.drawText("与镜中的另一个自己相遇",cx,mix(h*.80f,homeResolved?homeBaselines[2]:h*.80f,settle),paint);
    }
    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }
}
