package com.mirror.bench;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;

/** Finite UI-only motion. Navigation, saving and hardware never wait for an animation. */
final class MirrorMotion {
    private static final Interpolator SETTLE = new PathInterpolator(.22f, 1f, .36f, 1f);
    private MirrorMotion() {}

    static boolean enabled(Context context) {
        if (Build.VERSION.SDK_INT >= 26) return ValueAnimator.areAnimatorsEnabled();
        try { return Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f; }
        catch (RuntimeException unavailable) { return false; }
    }

    static void pressFeedback(View view) {
        view.setOnTouchListener((target, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && target.isEnabled() && enabled(target.getContext())) {
                target.animate().cancel();
                target.animate().scaleX(.982f).scaleY(.982f).setStartDelay(0).setDuration(110).setInterpolator(SETTLE).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                target.animate().cancel();
                if (enabled(target.getContext())) target.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(220).setInterpolator(SETTLE).start();
                else reset(target);
            }
            return false; // Retain native click, scroll cancellation, focus and accessibility semantics.
        });
    }

    static void previewReady(View image) {
        reset(image);
        if (!image.isAttachedToWindow() || !enabled(image.getContext())) return;
        image.setAlpha(0f); image.setScaleX(.982f); image.setScaleY(.982f);
        image.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(420)
                .setInterpolator(SETTLE).start();
    }

    static void reset(View view) {
        view.animate().setListener(null).cancel();
        view.animate().setStartDelay(0);
        view.setAlpha(1f); view.setScaleX(1f); view.setScaleY(1f); view.setTranslationY(0f);
    }

    static void brandReveal(FrameLayout page, boolean freshLaunch) {
        if (page instanceof Page root && freshLaunch && enabled(root.getContext())) root.requestBrand();
    }

    /** This root is used only by metadata/UI pages, never by the interlaced GLSurfaceView. */
    static final class Page extends FrameLayout implements Application.ActivityLifecycleCallbacks {
        private final Activity owner;
        private boolean registered, destroyed, paused, introduced, brandRequested;
        private MirrorBrandView brand;
        private ViewGroup body;
        private final Runnable reveal = () -> {
            if (paused || destroyed || !isAttachedToWindow()) return;
            if (brandRequested && enabled(getContext())) {
                brand = new MirrorBrandView(getContext(), body, this::introduceBody, () -> dismissBrand(false));
                addView(brand, new FrameLayout.LayoutParams(-1, -1));
                brand.begin();
            } else introduceBody();
        };

        Page(Activity activity) { super(activity); owner = activity; bind(); }
        private void bind() {
            if (!registered && !destroyed) { owner.getApplication().registerActivityLifecycleCallbacks(this); registered = true; }
        }
        private void unbind() {
            if (registered) { owner.getApplication().unregisterActivityLifecycleCallbacks(this); registered = false; }
        }
        void requestBrand() { brandRequested = true; }
        void setBody(ViewGroup content) { body = content; }
        private void introduceBody() {
            if (paused || destroyed || body == null || !isAttachedToWindow() || !enabled(getContext())) return;
            // Only visible direct rows participate; long offscreen settings lists stay idle.
            int order = 0, viewport = ((View) body.getParent()).getHeight();
            for (int i = 0; i < body.getChildCount(); i++) {
                View row = body.getChildAt(i);
                if (brandRequested && i < 3) { reset(row); continue; } // The aligned brand wordmark hands over to these rows.
                if (row.getVisibility() != VISIBLE || row.getBottom() <= 0 || row.getTop() >= viewport) continue;
                reset(row); row.setAlpha(0f); row.setTranslationY(MirrorTheme.dp(getContext(), 8));
                row.animate().alpha(1f).translationY(0f).setDuration(460).setStartDelay(Math.min(175, order++ * 35))
                        .setInterpolator(SETTLE).start();
            }
        }
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow(); bind();
            if (!introduced) { introduced = true; post(reveal); }
        }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            // Remove before hit-testing: the same down event can reach the real Home button.
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                dismissBrand(true); resetTree(this);
            }
            return super.dispatchTouchEvent(event);
        }
        private void dismissBrand(boolean interrupted) {
            if (brand == null) return;
            MirrorBrandView old = brand; brand = null; old.stop(); removeView(old);
            if (interrupted) resetTree(this);
        }
        private void stop() { removeCallbacks(reveal); dismissBrand(true); resetTree(this); }
        private static void resetTree(View view) {
            reset(view);
            if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) resetTree(group.getChildAt(i));
        }
        @Override protected void onDetachedFromWindow() { stop(); unbind(); super.onDetachedFromWindow(); }
        @Override public void onActivityPaused(Activity activity) { if (activity == owner) { paused = true; stop(); } }
        @Override public void onActivityResumed(Activity activity) { if (activity == owner) paused = false; }
        @Override public void onActivityDestroyed(Activity activity) { if (activity == owner) { destroyed = true; stop(); unbind(); } }
        @Override public void onActivityCreated(Activity activity, Bundle state) {}
        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    }
}
