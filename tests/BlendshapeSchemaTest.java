package com.mirror.bench;

public final class BlendshapeSchemaTest {
    private static int checks;
    public static void main(String[] args) {
        check(BlendshapeSchema.SIZE == 52);
        check(BlendshapeSchema.indexOf("_neutral") == 0);
        check(BlendshapeSchema.indexOf("eyeBlinkLeft") == 9);
        check(BlendshapeSchema.indexOf("eyeBlinkRight") == 10);
        check(BlendshapeSchema.indexOf("jawOpen") == 25);
        check(BlendshapeSchema.indexOf("noseSneerRight") == 51);
        check(BlendshapeSchema.indexOf("tongueOut") == -1);
        for (int i = 0; i < 52; i++) {
            check(BlendshapeSchema.indexOf(BlendshapeSchema.name(i)) == i);
            BlendshapeSchema.validateClassification(i, BlendshapeSchema.name(i), i);
        }
        rejects(() -> BlendshapeSchema.validateClassification(9, "eyeBlinkRight", 9));
        rejects(() -> BlendshapeSchema.validateClassification(10, "eyeBlinkRight", 9));
        rejects(() -> BlendshapeSchema.validateClassification(0, "", 0));
        rejects(() -> BlendshapeSchema.validateClassification(52, "other", 52));
        InteractionController controller = new InteractionController();
        float[] weights = new float[52];
        weights[9] = .9f; weights[10] = .1f; weights[15] = .7f;
        weights[16] = .2f; weights[25] = 1; weights[51] = .6f;
        controller.accept(FaceFrame.present(0, 0, 0, weights, FaceFrame.identity()));
        controller.sample(0);
        controller.accept(FaceFrame.present(1, 250_000_000L, 260_000_000L, weights, FaceFrame.identity()));
        var snapshot = controller.sample(270_000_000L);
        check(snapshot.state() == InteractionController.State.INTERACTIVE);
        float[] full = snapshot.blendshapes52();
        check(full.length == 52 && full[9] > full[10] * 8);
        check(full[15] > full[16] * 3 && full[51] > .5f);
        check(snapshot.sequence() == 1 && snapshot.receivedNs() == 250_000_000L
                && snapshot.completedNs() == 260_000_000L);
        full[9] = -100;
        check(snapshot.blendshapes52()[9] > .8f);
        check(snapshot.renderWeights()[1] > .45f && snapshot.renderWeights()[1] < .51f);
        var lost = controller.sample(900_000_000L);
        check(lost.blendshapes52()[9] < .02f);
        var idle = controller.sample(3_000_000_000L);
        for (float value : idle.blendshapes52()) check(value == 0);
        controller.reset();
        var reset = controller.sample(0);
        check(reset.sequence() == -1 && reset.receivedNs() == -1 && reset.completedNs() == -1);
        System.out.println("BlendshapeSchemaTest: " + checks + " assertions passed");
    }
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("check " + checks); }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; } throw new AssertionError("expected rejection"); }
}
