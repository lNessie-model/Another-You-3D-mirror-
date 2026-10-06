package com.mirror.bench;

import java.nio.ByteBuffer;

/** Counts opaque diagnostic IDs only after reference/probe alpha agrees at every pixel. */
final class AvatarMaterialCoverageStats {
    final long[] visibleByItem;
    final long[] primaryLodBins = new long[12];
    long pixels, foreground, background, primaryEstimatedEligible;
    final int primaryItem;

    AvatarMaterialCoverageStats(int itemCount, int primaryItem) {
        if (itemCount < 1 || itemCount > 254 || primaryItem < 0 || primaryItem >= itemCount)
            throw new IllegalArgumentException("Bounded item IDs and primary item required");
        visibleByItem = new long[itemCount];
        this.primaryItem = primaryItem;
    }

    void add(ByteBuffer reference, ByteBuffer probe, int pixelCount) {
        if (reference == null || probe == null || pixelCount < 1
                || pixelCount > Integer.MAX_VALUE / 4
                || reference.remaining() != pixelCount * 4 || probe.remaining() != pixelCount * 4)
            throw new IllegalArgumentException("Exact RGBA buffers required");
        int r0 = reference.position(), p0 = probe.position();
        // Validate the complete layer first, so a rejected layer cannot publish partial counts.
        for (int i = 0; i < pixelCount; i++) {
            int r = r0 + i * 4, p = p0 + i * 4;
            int alpha = reference.get(r + 3) & 255, other = probe.get(p + 3) & 255;
            if ((alpha != 0 && alpha != 255) || alpha != other)
                throw new IllegalStateException("Reference/probe opaque alpha differs at pixel " + i);
            if (alpha == 0) {
                if (probe.get(p) != 0 || probe.get(p + 1) != 0 || probe.get(p + 2) != 0)
                    throw new IllegalStateException("Uncovered diagnostic pixel is not zero");
                continue;
            }
            int id = (probe.get(p) & 255) - 1, eligible = probe.get(p + 1) & 255;
            int lod = probe.get(p + 2) & 255;
            if (id < 0 || id >= visibleByItem.length || (eligible != 0 && eligible != 255)
                    || (id != primaryItem && eligible != 0) || lod >= primaryLodBins.length)
                throw new IllegalStateException("Invalid diagnostic ID/mask/LOD at pixel " + i);
        }
        for (int i = 0; i < pixelCount; i++) {
            int p = p0 + i * 4;
            if (probe.get(p + 3) == 0) { background++; continue; }
            int id = (probe.get(p) & 255) - 1;
            visibleByItem[id]++; foreground++;
            if (id == primaryItem) {
                primaryLodBins[probe.get(p + 2) & 255]++;
                if ((probe.get(p + 1) & 255) == 255) primaryEstimatedEligible++;
            }
        }
        pixels += pixelCount;
    }
}
