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
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/** Finite UI-only motion. Navigation, saving and hardware never wait for an animation. */
final class MirrorMotion {
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
                target.animate().scaleX(.985f).scaleY(.985f).setDuration(80).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                target.animate().cancel();
                if (enabled(target.getContext())) target.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                else reset(target);
            }
            return false; // Retain native click, scroll cancellation, focus and accessibility semantics.
        });
    }

    static void previewReady(View image) {
        reset(image);
        if (!image.isAttachedToWindow() || !enabled(image.getContext())) return;
        image.setAlpha(0f); image.setScaleX(.975f); image.setScaleY(.975f);
        image.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    static void reset(View view) {
        view.animate().setListener(null).cancel();
        view.setAlpha(1f); view.setScaleX(1f); view.setScaleY(1f);
    }

    static void brandReveal(FrameLayout page, boolean freshLaunch) {
        if (page instanceof Page root && freshLaunch && enabled(root.getContext())) root.requestBrand();
    }

    /** This root is used only by metadata/UI pages, never by the interlaced GLSurfaceView. */
    static final class Page extends FrameLayout implements Application.ActivityLifecycleCallbacks {
        private final Activity owner;
        private boolean registered, destroyed, paused, introduced, brandRequested;
        private MirrorBrandView brand;
        private final Runnable reveal = () -> {
            if (paused || destroyed || !isAttachedToWindow()) return;
            if (enabled(getContext())) {
                setAlpha(0f); setScaleX(.992f); setScaleY(.992f);
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
                        .setInterpolator(new DecelerateInterpolator()).start();
            } else reset(this);
            if (brandRequested && enabled(getContext())) {
                brand = new MirrorBrandView(getContext(), this::dismissBrand);
                addView(brand, new FrameLayout.LayoutParams(-1, -1));
                brand.begin();
            }
        };

        Page(Activity activity) { super(activity); owner = activity; bind(); }
        private void bind() {
            if (!registered && !destroyed) { owner.getApplication().registerActivityLifecycleCallbacks(this); registered = true; }
        }
        private void unbind() {
            if (registered) { owner.getApplication().unregisterActivityLifecycleCallbacks(this); registered = false; }
        }
        void requestBrand() { brandRequested = true; }
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow(); bind();
            if (!introduced) { introduced = true; post(reveal); }
        }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            // Remove before hit-testing: the same down event can reach the real Home button.
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) dismissBrand();
            return super.dispatchTouchEvent(event);
        }
        private void dismissBrand() {
            if (brand == null) return;
            MirrorBrandView old = brand; brand = null; old.stop(); removeView(old);
        }
        private void stop() { removeCallbacks(reveal); dismissBrand(); resetTree(this); }
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
