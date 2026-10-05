package com.mirror.bench;

import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

/** Host checks for actual renderer state/math. Does not simulate OpenGL or assert device performance. */
public final class RendererRuntimeTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        unlimitedRuntimeRetainsNoFrameSamples();
        benchmarkKeepsOriginalSamples();
        columnMajorPoseStripsScaleAndTranslation();
        inputCopiesAndExpressionValidation();
        invalidPoseReturnsNeutral();
        rotationLimitsAndMixedAxes();
        smoothPoseAndStaleFaceRecovery();
        stableWeightsPreservePreblendCache();
        runtimeConfigurationAndVisibility();
        inferenceWaitsForSuccessfulRuntimeFrame();
        bundledAvatarConfigurationRejectsUnsafeMetadata();
        System.out.println("PASS: " + checks + " renderer assertions, including 1,000,000 runtime frames");
    }
    private static void bundledAvatarConfigurationRejectsUnsafeMetadata() throws Exception {
        String digest="0".repeat(64);
        for(String path: new String[]{"../character", "avatars/catalog/../geralt", "/avatars/catalog/geralt",
                "avatars/catalog/geralt/nested", "https://example.test/model", "avatars/catalog/Geralt"}) {
            InterlaceRenderer r=renderer(true);boolean rejected=false;
            try {r.setBundledRuntimeAvatar(null,path,"geralt",digest,digest,true);}
            catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"unsafe catalog directory rejected: "+path);
            check(field(r,"bundledAvatarDirectory")==null,"unsafe catalog cannot publish a partial choice");
        }
        for(String hash: new String[]{"", "0".repeat(63), "g".repeat(64)}) {
            InterlaceRenderer r=renderer(true);boolean rejected=false;
            try {r.setBundledRuntimeAvatar(null,"avatars/catalog/geralt","geralt",hash,digest,true);}
            catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"catalog content must identify a valid exact digest");
            check(field(r,"bundledAvatarDirectory")==null,"invalid digest leaves selection unset");
        }
        for(String[] pair: new String[][]{{"avatars/catalog/geralt","ada"},{"avatars/builtin-guide","geralt"},
                {"avatars/catalog/builtin-guide","builtin-guide"}}){
            InterlaceRenderer r=renderer(true);boolean badIdentity=false;
            try {r.setBundledRuntimeAvatar(null,pair[0],pair[1],digest,digest,true);}
            catch(IllegalArgumentException expected){badIdentity=true;}
            check(badIdentity,"role identity cannot label another directory");
            check(field(r,"bundledAvatarDirectory")==null,"identity mismatch leaves no partial selection");
        }
        InterlaceRenderer benchmark=renderer(false);boolean rejected=false;
        try {benchmark.setBundledRuntimeAvatar(null,"avatars/catalog/geralt","geralt",digest,digest,true);}
        catch(IllegalStateException expected){rejected=true;}
        check(rejected,"benchmark cannot attach product role assets");
        InterlaceRenderer initialized=renderer(true);
        Field state=InterlaceRenderer.class.getDeclaredField("surfaceInitialized");state.setAccessible(true);state.set(initialized,true);
        rejected=false;
        try {initialized.setBundledRuntimeAvatar(null,"avatars/catalog/geralt","geralt",digest,digest,true);}
        catch(IllegalStateException expected){rejected=true;}
        check(rejected,"live GL ownership prevents changing role metadata in place");
    }

    private static void inferenceWaitsForSuccessfulRuntimeFrame() throws Exception {
        InterlaceRenderer renderer=renderer(true);
        check(!renderer.hasRuntimeFrame(),"new runtime has no ready frame");
        Method record=method("recordFrame",long.class,long.class,boolean.class,long.class);
        record.invoke(renderer,1L,2L,false,1L);
        check(!renderer.hasRuntimeFrame(),"statistics alone cannot release input startup gate");
        RuntimeGlLifecycle gate=(RuntimeGlLifecycle)field(renderer,"runtimeGlLifecycle");
        gate.contextCreated();long enteredEpoch=gate.frameEpoch();
        record.invoke(renderer,2L,3L,false,2L);
        check(!renderer.hasRuntimeFrame(),"context without actual successful submission remains gated");
        gate.successfulFrame(enteredEpoch);
        check(renderer.hasRuntimeFrame(),"successful current-context submission releases input startup gate");
        renderer.resumeRuntimeAvatar();
        check(!renderer.hasRuntimeFrame(),"resume waits for its own first successful frame");
        record.invoke(renderer,3L,4L,false,3L);
        gate.successfulFrame(enteredEpoch);
        check(!renderer.hasRuntimeFrame(),"old frame or statistics cannot resurrect readiness after resume");
        gate.successfulFrame(gate.frameEpoch());
        check(renderer.hasRuntimeFrame(),"resumed successful frame releases startup gate");
        Field error=InterlaceRenderer.class.getDeclaredField("error");error.setAccessible(true);error.set(renderer,"GPU failed");
        check(!renderer.hasRuntimeFrame(),"latched GL error cannot permit input startup");
        InterlaceRenderer benchmark=renderer(false);record.invoke(benchmark,1L,2L,false,1L);
        check(!benchmark.hasRuntimeFrame(),"legacy benchmark does not satisfy runtime gate");
    }

    private static void unlimitedRuntimeRetainsNoFrameSamples() throws Exception {
        InterlaceRenderer renderer = renderer(true);
        renderer.setProfileStages(true);
        renderer.beginMeasurement();
        Method record = method("recordFrame", long.class, long.class, boolean.class, long.class);
        for (int i = 0; i < 1_000_000; i++) {
            long before = 1_000_000_000L + i * 33_333_333L;
            record.invoke(renderer, before, before + 5_000_000L, true, before + 3_000_000L);
        }
        for (String name : new String[]{"workMs", "intervalMs", "sceneStageMs", "interlaceStageMs"})
            check(list(renderer, name).isEmpty(), "runtime must not retain " + name);
        check((Long) field(renderer, "runtimeFrames") == 1_000_000L, "lifetime frame count");
        near((Double) field(renderer, "runtimeFps"), 30, .1, "runtime FPS window");
        near((Double) field(renderer, "runtimeWorkMeanMs"), 5, .0001, "runtime work mean");
        renderer.beginMeasurement();
        check((Long) field(renderer, "runtimeFrames") == 0L, "measurement reset clears runtime count");
        near((Double) field(renderer, "runtimeFps"), 0, .0001, "measurement reset clears previous FPS");
    }

    private static void benchmarkKeepsOriginalSamples() throws Exception {
        InterlaceRenderer renderer = renderer(false);
        Method record = method("recordFrame", long.class, long.class, boolean.class, long.class);
        record.invoke(renderer, 1L, 3L, false, 2L);
        check(list(renderer, "workMs").isEmpty(), "benchmark ignores frames before beginMeasurement");
        renderer.beginMeasurement();
        for (int i = 0; i < 100; i++) {
            long before = 1_000_000L + i * 10_000_000L;
            record.invoke(renderer, before, before + 2_000_000L, i % 30 == 0, before + 1_000_000L);
        }
        check(list(renderer, "workMs").size() == 100, "benchmark work samples retained");
        check(list(renderer, "intervalMs").size() == 99, "benchmark intervals retained");
        check(list(renderer, "sceneStageMs").size() == 4, "benchmark scene samples retained");
        check(list(renderer, "interlaceStageMs").size() == 4, "benchmark interlace samples retained");
        near((Double) list(renderer, "workMs").get(0), 2, .0001, "benchmark work duration");
        near((Double) list(renderer, "intervalMs").get(0), 10, .0001, "benchmark frame interval");
        near((Double) list(renderer, "sceneStageMs").get(0), 1, .0001, "benchmark scene duration");
        check((Long) field(renderer, "runtimeFrames") == 0L, "benchmark excludes runtime aggregation");
        renderer.beginMeasurement();
        check(list(renderer, "workMs").isEmpty(), "benchmark measurement reset");
    }

    private static void columnMajorPoseStripsScaleAndTranslation() throws Exception {
        InterlaceRenderer renderer = renderer(true);
        float[] pose = pose(10, 30, -15);
        for (int column = 0; column < 3; column++)
            for (int row = 0; row < 3; row++) pose[column * 4 + row] *= column + 2;
        pose[12] = 900; pose[13] = -50; pose[14] = 100;
        renderer.setInteractiveFace(new float[4], pose, true);
        near(angles(renderer)[0], 10, .001, "pitch survives nonuniform scale removal");
        near(angles(renderer)[1], 30, .001, "column-major yaw survives translation removal");
        near(angles(renderer)[2], -15, .001, "roll survives nonuniform scale removal");
    }

    private static void inputCopiesAndExpressionValidation() throws Exception {
        InterlaceRenderer renderer = renderer(true);
        float[] pose = pose(0, 30, 0), weights = {-1, .3f, 2, Float.NaN};
        renderer.setInteractiveFace(weights, pose, true);
        Arrays.fill(weights, 1); Arrays.fill(pose, 0);
        near(angles(renderer)[1], 30, .001, "caller mutation cannot change pose snapshot");
        check(Arrays.equals(weights(renderer), new float[]{0, .3f, 1, 0}), "copied weights clamped and finite");
        renderer.setInteractiveFace(new float[]{Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, .5f, 1}, null, true);
        check(Arrays.equals(weights(renderer), new float[]{0, 0, .5f, 1}), "infinite weights become neutral");
        renderer.setInteractiveFace(new float[5], null, true);
        check(Arrays.equals(weights(renderer), new float[4]), "malformed weight count becomes neutral");
        renderer.setInteractiveFace(null, null, true);
        check(Arrays.equals(weights(renderer), new float[4]), "missing weights become neutral");
    }

    private static void invalidPoseReturnsNeutral() throws Exception {
        InterlaceRenderer renderer = renderer(true);
        neutral(renderer, null, "missing pose");
        neutral(renderer, new float[15], "short pose");
        neutral(renderer, new float[16], "degenerate pose");
        float[] invalid = pose(0, 30, 0); invalid[0] = Float.NaN;
        neutral(renderer, invalid, "NaN matrix");
        invalid = pose(0, 30, 0); invalid[12] = Float.POSITIVE_INFINITY;
        neutral(renderer, invalid, "nonfinite translation");
        invalid = pose(0, 0, 0); invalid[0] = -1;
        neutral(renderer, invalid, "reflection");
        invalid = pose(0, 0, 0); invalid[4] = .5f;
        neutral(renderer, invalid, "shear");
        invalid = pose(0, 30, 0); invalid[3] = .5f;
        neutral(renderer, invalid, "projective matrix");
        invalid = pose(0, 30, 0); invalid[15] = 0;
        neutral(renderer, invalid, "invalid homogeneous component");
    }

    private static void rotationLimitsAndMixedAxes() throws Exception {
        InterlaceRenderer renderer = renderer(true);
        for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
            float[] degrees = new float[3]; degrees[axis] = 80 * sign;
            renderer.setInteractiveFace(new float[4], pose(degrees[0], degrees[1], degrees[2]), true);
            near(angles(renderer)[axis], new float[]{45, 65, 40}[axis] * sign, .001, "rotation limit axis " + axis);
        }
        renderer.setInteractiveFace(new float[4], pose(-20, 35, 25), true);
        near(angles(renderer)[0], -20, .001, "mixed rotation pitch");
        near(angles(renderer)[1], 35, .001, "mixed rotation yaw");
        near(angles(renderer)[2], 25, .001, "mixed rotation roll");
    }

    private static void smoothPoseAndStaleFaceRecovery() throws Exception {
        InterlaceRenderer renderer = renderer(true);
        Method update = method("updateInteractiveFace", long.class);
        renderer.setInteractiveFace(new float[]{1, .5f, .2f, 0}, pose(0, 30, 0), true);
        update.invoke(renderer, SystemClock.now);
        float first = ((float[]) field(renderer, "runtimeAngles"))[1];
        check(first > 0 && first < 30, "pose starts with a partial step");
        for (int i = 1; i < 30; i++) update.invoke(renderer, SystemClock.now + i * 16_666_667L);
        near(((float[]) field(renderer, "runtimeAngles"))[1], 30, .3, "pose converges smoothly");
        check(Arrays.equals((float[]) field(renderer, "expressions"), new float[]{1, .5f, .2f, 0}),
                "active expression weights avoid duplicate smoothing");
        update.invoke(renderer, SystemClock.now + 1_000_000_000L);
        check(!(Boolean) field(renderer, "runtimeFaceActive"), "pose expires after one second");
        update.invoke(renderer, SystemClock.now + 4_000_000_000L);
        near(((float[]) field(renderer, "runtimeAngles"))[1], 0, .001, "stale pose returns neutral");
        for (float value : (float[]) field(renderer, "expressions")) near(value, 0, .0001, "idle weight returns neutral");
        renderer.setInteractiveFace(new float[]{1, 1, 1, 1}, pose(0, 30, 0), false);
        for (float value : angles(renderer)) near(value, 0, .0001, "inactive pose is neutral");
        check(Arrays.equals(weights(renderer), new float[4]), "inactive weights are neutral");
    }

    private static void stableWeightsPreservePreblendCache() throws Exception {
        InterlaceRenderer renderer = renderer(true);
        Method update = method("updateInteractiveFace", long.class);
        renderer.setInteractiveFace(new float[]{.1f, .2f, .3f, .4f}, pose(0, 0, 0), true);
        update.invoke(renderer, SystemClock.now);
        Object cached = field(renderer, "expressions");
        renderer.setInteractiveFace(new float[]{.1f, .2f, .3f, .4f}, pose(0, 15, 0), true);
        update.invoke(renderer, SystemClock.now + 16_666_667L);
        check(cached == field(renderer, "expressions"), "unchanged expression weights reuse preblend input");
    }

    private static void runtimeConfigurationAndVisibility() throws Exception {
        InterlaceRenderer renderer = renderer(true);
        check(Modifier.isVolatile(InterlaceRenderer.class.getDeclaredField("targetFps").getModifiers()), "FPS publishes between threads");
        check(Modifier.isVolatile(InterlaceRenderer.class.getDeclaredField("interactiveFace").getModifiers()), "face snapshot publishes between threads");
        for (int fps : new int[]{0, 5, 30, 60}) {
            renderer.setTargetFps(fps);
            check((Integer) field(renderer, "targetFps") == fps, "accept FPS " + fps);
        }
        for (int fps : new int[]{-1, 61}) {
            boolean rejected = false;
            try { renderer.setTargetFps(fps); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "reject invalid FPS " + fps);
        }
        Field initialized = InterlaceRenderer.class.getDeclaredField("surfaceInitialized");
        initialized.setAccessible(true); initialized.set(renderer, true);
        boolean rejected = false;
        try { renderer.setRuntimeMode(false); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "runtime mode cannot change after GL initialization");
    }

    private static InterlaceRenderer renderer(boolean runtime) {
        InterlaceRenderer renderer = new InterlaceRenderer(20, .5f, true);
        if (runtime) renderer.setRuntimeMode(true);
        return renderer;
    }
    private static float[] pose(float pitch, float yaw, float roll) {
        double x = Math.toRadians(pitch), y = Math.toRadians(yaw), z = Math.toRadians(roll);
        double cx = Math.cos(x), sx = Math.sin(x), cy = Math.cos(y), sy = Math.sin(y), cz = Math.cos(z), sz = Math.sin(z);
        return new float[]{(float)(cz*cy), (float)(sz*cy), (float)-sy, 0,
                (float)(cz*sy*sx-sz*cx), (float)(sz*sy*sx+cz*cx), (float)(cy*sx), 0,
                (float)(cz*sy*cx+sz*sx), (float)(sz*sy*cx-cz*sx), (float)(cy*cx), 0, 0, 0, 0, 1};
    }
    private static float[] angles(InterlaceRenderer renderer) throws Exception { return (float[]) field(field(renderer, "interactiveFace"), "angles"); }
    private static float[] weights(InterlaceRenderer renderer) throws Exception { return (float[]) field(field(renderer, "interactiveFace"), "weights"); }
    private static List<?> list(Object instance, String name) throws Exception { return (List<?>) field(instance, name); }
    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(instance);
    }
    private static Method method(String name, Class<?>... types) throws Exception {
        Method method = InterlaceRenderer.class.getDeclaredMethod(name, types); method.setAccessible(true); return method;
    }
    private static void neutral(InterlaceRenderer renderer, float[] pose, String name) throws Exception {
        renderer.setInteractiveFace(new float[4], pose, true);
        for (float value : angles(renderer)) near(value, 0, .0001, name);
    }
    private static void near(double actual, double expected, double tolerance, String message) {
        check(Math.abs(actual - expected) < tolerance, message + ": " + actual);
    }
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
