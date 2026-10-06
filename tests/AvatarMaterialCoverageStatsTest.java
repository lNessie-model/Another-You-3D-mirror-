package com.mirror.bench;

import java.nio.ByteBuffer;

public final class AvatarMaterialCoverageStatsTest {
    private static int checks;
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError(); }
    private static ByteBuffer bytes(int... values) {
        ByteBuffer out = ByteBuffer.allocate(values.length);
        for (int value : values) out.put((byte)value);
        return out.flip();
    }
    private static void rejected(Runnable task) {
        boolean rejected = false;
        try { task.run(); } catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
        check(rejected);
    }
    public static void main(String[] args) {
        var stats = new AvatarMaterialCoverageStats(3, 0);
        var reference = bytes(0,0,0,0, 70,81,92,255, 170,191,112,255, 80,91,12,255);
        var probe = bytes(0,0,0,0, 1,255,2,255, 3,0,11,255, 1,0,3,255);
        reference.position(4); probe.position(4); reference.mark(); probe.mark();
        stats.add(reference, probe, 3);
        check(stats.pixels == 3 && stats.foreground == 3 && stats.background == 0);
        check(stats.visibleByItem[0] == 2 && stats.visibleByItem[2] == 1);
        check(stats.primaryEstimatedEligible == 1 && stats.primaryLodBins[2] == 1 && stats.primaryLodBins[3] == 1);
        check(reference.position() == 4 && probe.position() == 4);
        reference.reset(); probe.reset(); check(reference.position() == 4 && probe.position() == 4);
        reference.position(0); probe.position(0); stats.add(reference, probe, 4);
        check(stats.pixels == 7 && stats.background == 1 && stats.foreground == 6);
        check(stats.primaryEstimatedEligible == 2);
        long previous = stats.pixels;
        rejected(() -> stats.add(bytes(1,2,3,255, 1,2,3,255),bytes(1,255,2,255, 0,0,0,255),2));
        check(stats.pixels == previous && stats.foreground == 6); // Reject before accounting first valid pixel.
        rejected(() -> stats.add(bytes(0,0,0,0),bytes(1,0,0,0),1));
        rejected(() -> stats.add(bytes(1,2,3,255),bytes(1,0,0,0),1));
        rejected(() -> stats.add(bytes(1,2,3,128),bytes(1,0,0,128),1));
        rejected(() -> stats.add(bytes(1,2,3,255),bytes(2,255,0,255),1));
        rejected(() -> stats.add(bytes(1,2,3,255),bytes(1,254,0,255),1));
        rejected(() -> stats.add(bytes(1,2,3,255),bytes(1,0,12,255),1));
        rejected(() -> stats.add(reference,probe,3));
        rejected(() -> stats.add(reference,probe,-1));
        rejected(() -> stats.add(null,probe,1));
        rejected(() -> new AvatarMaterialCoverageStats(255,0));
        rejected(() -> new AvatarMaterialCoverageStats(3,3));
        System.out.println("AvatarMaterialCoverageStatsTest: " + checks + " checks passed");
    }
}
