package com.mirror.bench;

import android.opengl.GLES30;
import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;

/** External bounded diagnostic estimate. This mask is never used by production rendering. */
final class AvatarUvMaskEstimate implements AutoCloseable {
    private static final String MANIFEST_SHA="d859e0a5f3346b32575006d1f44dc6d9776405bc8af67c1caf30e1cbb50f57cc";
    private static final String RAW_SHA="12b5e08c42d978da51cd32350ca3b767914c6c818bd33f6626d65eff1b5a6ee5";
    final JSONObject metadata;
    final int texture;
    private boolean closed;
    AvatarUvMaskEstimate(File files, String modelSha256) throws Exception {
        File directory = new File(files, "material-coverage-check");
        byte[] manifest = readExactBounded(new File(directory, "mask.json"), 131072);
        if(!sha(manifest).equals(MANIFEST_SHA))throw new IllegalArgumentException("Frozen UV estimate manifest digest mismatch");
        metadata = new JSONObject(new String(manifest, StandardCharsets.UTF_8));
        JSONObject source = metadata.getJSONObject("source"), mask = metadata.getJSONObject("mask");
        if (metadata.getInt("schema_version") != 1
                || !metadata.getString("kind").equals("geralt-material0-uv-validity-estimate-v1")
                || !source.getString("model_sha256").equals(modelSha256)
                || source.getInt("mesh") != 0 || source.getInt("primitive") != 0
                || source.getInt("material") != 0 || source.getInt("texcoord_accessor") != 3
                || !source.getString("uv_origin").equals("glTF UV increasing v; row0 lowest v")
                || !mask.getString("file").equals("uv-validity-estimate.r8")
                || mask.getInt("width") != 2048 || mask.getInt("height") != 2048
                || !mask.getString("format").equals("R8_UNORM") || mask.getInt("bytes") != 5592405)
            throw new IllegalArgumentException("Fixed Geralt UV estimate contract required");
        byte[] data = readExactBounded(new File(directory, "uv-validity-estimate.r8"), 5592405);
        if (data.length != 5592405 || !mask.getString("sha256").equals(RAW_SHA) || !sha(data).equals(RAW_SHA))
            throw new IllegalArgumentException("UV estimate raw digest mismatch");
        JSONArray levels = mask.getJSONArray("mip_levels");
        if (levels.length() != 12) throw new IllegalArgumentException("Twelve complete estimate mip levels required");
        int offset = 0;
        for (int level = 0; level < 12; level++) {
            JSONObject item = levels.getJSONObject(level); int size = 2048 >> level, count = size * size;
            if (item.getInt("level") != level || item.getInt("width") != size || item.getInt("height") != size
                    || item.getInt("offset_bytes") != offset || item.getInt("length_bytes") != count)
                throw new IllegalArgumentException("UV estimate level layout mismatch");
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); digest.update(data, offset, count);
            if (!hex(digest.digest()).equals(item.getString("sha256"))) throw new IllegalArgumentException("UV estimate level digest mismatch");
            long nonzero = 0;
            for (int i = offset; i < offset + count; i++) {
                if (data[i] != 0 && (data[i] & 255) != 255) throw new IllegalArgumentException("Binary estimate values required");
                if (data[i] != 0) nonzero++;
            }
            if (nonzero != item.getLong("nonzero_texels")) throw new IllegalArgumentException("UV estimate population mismatch");
            if (level > 0) {
                int previous = levels.getJSONObject(level - 1).getInt("offset_bytes"), stride = size * 2;
                for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                    int p = previous + y * 2 * stride + x * 2;
                    byte expected = (byte)(data[p] & data[p+1] & data[p+stride] & data[p+stride+1]);
                    if (data[offset+y*size+x] != expected) throw new IllegalArgumentException("Estimate mip must use all-children AND");
                }
            }
            offset += count;
        }
        if (offset != data.length) throw new IllegalArgumentException("UV estimate trailing bytes");
        int[] id = new int[1], alignment = new int[1]; GLES30.glGetIntegerv(GLES30.GL_UNPACK_ALIGNMENT, alignment, 0);
        GLES30.glGenTextures(1, id, 0); texture = id[0];
        try {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture); GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 12, GLES30.GL_R8, 2048, 2048);
            ByteBuffer staging = ByteBuffer.allocateDirect(data.length); staging.put(data).flip();
            for (int level = 0; level < 12; level++) {
                JSONObject item = levels.getJSONObject(level); staging.position(item.getInt("offset_bytes"));
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, level, 0, 0, 2048 >> level, 2048 >> level, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, staging);
            }
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
            if (GLES30.glGetError() != GLES30.GL_NO_ERROR) throw new IllegalStateException("UV estimate texture upload failed");
        } catch (Exception | Error failure) { close(); throw failure; }
        finally { GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, alignment[0]); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0); }
    }
    private static byte[] readExactBounded(File file, int maximum) throws Exception {
        long size = file.length(); if (size < 1 || size > maximum) throw new IllegalArgumentException("Diagnostic file size exceeds bound");
        byte[] bytes = new byte[(int)size];
        try (FileInputStream input = new FileInputStream(file)) {
            int read = 0; while (read < bytes.length) { int n = input.read(bytes, read, bytes.length-read); if (n < 1) throw new IllegalArgumentException("Diagnostic file truncated"); read += n; }
            if (input.read() != -1) throw new IllegalArgumentException("Diagnostic file grew while reading");
        }
        return bytes;
    }
    static String sha(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String hex(byte[] bytes) { StringBuilder value = new StringBuilder(64); for (byte b : bytes) value.append(Character.forDigit((b >>> 4)&15,16)).append(Character.forDigit(b&15,16)); return value.toString(); }
    @Override public void close() { if (!closed) { closed=true; GLES30.glDeleteTextures(1,new int[]{texture},0); } }
}
