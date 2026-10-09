package com.mirror.bench;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** One short portal stroke and wordmark reveal; no repeating drawing after removal. */
final class MirrorBrandView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path oval = new Path(), segment = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final Typeface serif = Typeface.create("serif", Typeface.NORMAL), sans = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Runnable finished;
    private ValueAnimator animator;
    private float progress;
    MirrorBrandView(Context context, Runnable finished) {
        super(context); this.finished = finished;
        setBackgroundColor(MirrorTheme.BACKGROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    void begin() {
        animator = ValueAnimator.ofFloat(0f, 1f); animator.setDuration(620);
        animator.setInterpolator(new DecelerateInterpolator(1.2f));
        animator.addUpdateListener(value -> {
            progress = (float) value.getAnimatedValue();
            setAlpha(progress < .72f ? 1f : Math.max(0f, (1f - progress) / .28f));
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
        float rx = Math.min(w * .19f, h * .12f), ry = rx * 1.45f, cx = w / 2f, cy = h * .38f;
        oval.reset(); oval.addOval(cx - rx, cy - ry, cx + rx, cy + ry, Path.Direction.CW);
        measure.setPath(oval, true);
    }
    @Override protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, h = getHeight();
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(MirrorTheme.dp(getContext(), 1));
        paint.setColor(0x446f293c); canvas.drawPath(oval, paint);
        segment.reset(); measure.getSegment(0f, measure.getLength() * Math.min(1f, progress / .75f), segment, true);
        paint.setColor(MirrorTheme.GOLD); canvas.drawPath(segment, paint);
        float radius = MirrorTheme.dp(getContext(), 8);
        canvas.drawCircle(cx, h * .38f, radius, paint);
        canvas.drawLine(cx - radius * 3, h * .38f, cx - radius * 1.5f, h * .38f, paint);
        canvas.drawLine(cx + radius * 1.5f, h * .38f, cx + radius * 3, h * .38f, paint);
        paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(serif);
        paint.setTextSize(MirrorTheme.dp(getContext(), 29));
        float available = getWidth() * .6f;
        if(paint.measureText("ANOTHER YOU")>available)paint.setTextSize(paint.getTextSize()*available/paint.measureText("ANOTHER YOU"));
        paint.setColor(MirrorTheme.GOLD); paint.setAlpha((int) (255 * Math.min(1f, progress * 3f)));
        canvas.drawText("ANOTHER YOU", cx, h * .69f, paint);
        paint.setTypeface(sans);
        paint.setTextSize(MirrorTheme.dp(getContext(), 19)); paint.setColor(MirrorTheme.INK);
        canvas.drawText("另一个你", cx, h * .735f, paint);
        paint.setTextSize(MirrorTheme.dp(getContext(), 12)); paint.setColor(MirrorTheme.MUTED);
        canvas.drawText("与镜中的另一个自己相遇", cx, h * .79f, paint);
    }
    @Override protected void onDetachedFromWindow() { stop(); super.onDetachedFromWindow(); }
}
