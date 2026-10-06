package com.mirror.bench;

import android.os.Bundle;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Tests the built production InputOptions class; no GL driver is emulated. */
public final class GpuProfileConfigTest {
    private static int checks;
    private static Method read;
    private static Field flag;
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("check " + checks);
    }
    private static Object parsed(Bundle extras, boolean debug) throws Exception {
        return read.invoke(null, extras, debug, 16);
    }
    private static boolean enabled(Bundle extras, boolean debug) throws Exception {
        return flag.getBoolean(parsed(extras, debug));
    }
    private static void rejected(Bundle extras, boolean debug) throws Exception {
        try {
            parsed(extras, debug);
            throw new AssertionError("invalid GPU profiling override accepted");
        } catch (InvocationTargetException expected) {
            check(expected.getCause() instanceof IllegalArgumentException);
        }
    }
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("com.mirror.bench.MirrorActivity$InputOptions");
        read = type.getDeclaredMethod("read", Bundle.class, boolean.class, int.class);
        read.setAccessible(true);
        flag = type.getDeclaredField("gpuProfile");
        flag.setAccessible(true);
        check(!enabled(null, true));
        check(!enabled(null, false));
        check(!enabled(new Bundle(), true));
        check(!enabled(new Bundle(), false));
        Bundle extras = new Bundle();
        extras.putString("product_action", "scene");
        check(!enabled(extras, true));
        for (boolean value : new boolean[]{true, false}) {
            extras.putBoolean("test_gpu_profile", value);
            check(enabled(extras, true) == value);
            rejected(extras, false);
        }
        extras.putString("test_gpu_profile", null);
        rejected(extras, true);
        rejected(extras, false);
        extras.putString("test_gpu_profile", "true");
        rejected(extras, true);
        extras.putInt("test_gpu_profile", 1);
        rejected(extras, true);
        extras.putLong("test_gpu_profile", 1L);
        rejected(extras, true);
        extras.putFloat("test_gpu_profile", 1f);
        rejected(extras, true);
        Bundle clean = new Bundle();
        clean.putBoolean("test_gpu_profile", true);
        Object options = parsed(clean, true);
        Field views = type.getDeclaredField("viewCount"); views.setAccessible(true);
        Field persistent = type.getDeclaredField("persistentFbos"); persistent.setAccessible(true);
        Field npu = type.getDeclaredField("npuBlendshapes"); npu.setAccessible(true);
        check(views.getInt(options) == 16);
        check(persistent.getBoolean(options));
        check(npu.getBoolean(options));
        InterlaceRenderer renderer = new InterlaceRenderer(16, .3f, true);
        renderer.setRuntimeMode(true);
        Field profile = InterlaceRenderer.class.getDeclaredField("gpuProfile"); profile.setAccessible(true);
        check(profile.get(renderer) == null);
        check(renderer.runtimeStatus().getJSONObject("gpu_profile").getString("state").equals("disabled"));
        check(renderer.runtimeStatus().getJSONObject("gpu_profile").getInt("query_pool_capacity") == 0);
        renderer.setGpuProfile(true);
        check(profile.get(renderer) == null); // Configuration does not allocate or call a GL driver.
        check(renderer.runtimeStatus().getJSONObject("gpu_profile").getBoolean("requested"));
        Field initialized = InterlaceRenderer.class.getDeclaredField("surfaceInitialized"); initialized.setAccessible(true);
        initialized.setBoolean(renderer, true);
        try { renderer.setGpuProfile(false); throw new AssertionError("changed GPU mode after GL creation"); }
        catch (IllegalStateException expected) { checks++; }
        System.out.println("GpuProfileConfigTest: " + checks + " checks passed; actual InputOptions, no GPU evidence");
    }
}
