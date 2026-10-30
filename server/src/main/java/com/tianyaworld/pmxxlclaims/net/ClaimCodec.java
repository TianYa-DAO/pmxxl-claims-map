package com.tianyaworld.pmxxlclaims.net;

import com.tianyaworld.pmxxlclaims.claim.ClaimData;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;

public final class ClaimCodec {

    private static final byte[] MAGIC = {'P', 'M', 'C', '3'};
    private static final int FLAG_DEFLATE = 1;
    private static final int COMPRESS_THRESHOLD = 1024;
    private static final int MAX_NAME = 120;
    private static final int MAX_WORLD = 512;

    private ClaimCodec() {
    }

    public static byte[] encode(String world, List<ClaimData.Claim> claims) {
        byte[] body = encodeBody(world, claims);
        byte flags = 0;
        if (body.length > COMPRESS_THRESHOLD) {
            byte[] packed = deflate(body);
            if (packed.length < body.length) {
                body = packed;
                flags |= FLAG_DEFLATE;
            }
        }
        byte[] out = new byte[5 + body.length];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        out[4] = flags;
        System.arraycopy(body, 0, out, 5, body.length);
        return out;
    }

    private static byte[] encodeBody(String world, List<ClaimData.Claim> claims) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(2048);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            writeString(out, world, MAX_WORLD);
            writeVarInt(out, claims.size());
            for (ClaimData.Claim claim : claims) {
                writeString(out, claim.name(), MAX_NAME);
                writeString(out, claim.owner(), MAX_NAME);
                out.writeInt(claim.color());
                out.writeByte(claim.flags());
                writeVarInt(out, claim.areas().size());
                for (ClaimData.Area area : claim.areas()) {
                    out.writeInt(area.minX());
                    out.writeInt(area.minZ());
                    out.writeInt(area.maxX());
                    out.writeInt(area.maxZ());
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("claim encode failed", exception);
        }
        return buffer.toByteArray();
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static void writeString(DataOutputStream out, String value, int maxBytes) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            bytes = truncateUtf8(bytes, maxBytes);
        }
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static byte[] truncateUtf8(byte[] bytes, int maxBytes) {
        int length = maxBytes;
        while (length > 0 && (bytes[length] & 0xC0) == 0x80) {
            length--;
        }
        return Arrays.copyOf(bytes, length);
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(data.length / 2 + 64);
        byte[] chunk = new byte[4096];
        try {
            while (!deflater.finished()) {
                int count = deflater.deflate(chunk);
                buffer.write(chunk, 0, count);
            }
        } finally {
            deflater.end();
        }
        return buffer.toByteArray();
    }
}
