package com.tianyaworld.pmxxlclaims.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Inflater;

public final class ClaimCodec {

    private static final int FLAG_DEFLATE = 1;
    private static final int MAX_CLAIMS = 8192;
    private static final int MAX_AREAS = 512;
    private static final int MAX_WORLD = 512;
    private static final int MAX_STRING = 256;
    private static final int MAX_RAW = 2 * 1024 * 1024;
    private static final int MAX_INFLATED = 4 * 1024 * 1024;

    public record Box(int minX, int minZ, int maxX, int maxZ) {
    }

    public record Claim(String name, String owner, int color, int flags, List<Box> boxes) {
    }

    public record Result(String world, List<Claim> claims) {
    }

    private ClaimCodec() {
    }

    public static Result decode(byte[] raw) {
        try {
            return decodeUnsafe(raw);
        } catch (RuntimeException | IOException exception) {
            return null;
        }
    }

    private static Result decodeUnsafe(byte[] raw) throws IOException {
        if (raw == null || raw.length < 5 || raw.length > MAX_RAW) {
            return null;
        }
        if (raw[0] != 'P' || raw[1] != 'M' || raw[2] != 'C' || raw[3] != '3') {
            return null;
        }
        int flags = raw[4] & 0xFF;
        byte[] body = new byte[raw.length - 5];
        System.arraycopy(raw, 5, body, 0, body.length);
        if ((flags & FLAG_DEFLATE) != 0) {
            body = inflate(body);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            String world = readString(in, MAX_WORLD);
            int claimCount = readVarInt(in);
            if (claimCount < 0 || claimCount > MAX_CLAIMS) {
                return null;
            }
            List<Claim> claims = new ArrayList<>(claimCount);
            for (int i = 0; i < claimCount; i++) {
                String name = readString(in, MAX_STRING);
                String owner = readString(in, MAX_STRING);
                int color = in.readInt();
                int claimFlags = in.readUnsignedByte();
                int areaCount = readVarInt(in);
                if (areaCount < 0 || areaCount > MAX_AREAS) {
                    return null;
                }
                List<Box> boxes = new ArrayList<>(areaCount);
                for (int a = 0; a < areaCount; a++) {
                    int minX = in.readInt();
                    int minZ = in.readInt();
                    int maxX = in.readInt();
                    int maxZ = in.readInt();
                    boxes.add(new Box(Math.min(minX, maxX), Math.min(minZ, maxZ), Math.max(minX, maxX), Math.max(minZ, maxZ)));
                }
                claims.add(new Claim(name, owner, color, claimFlags, List.copyOf(boxes)));
            }
            return new Result(world, List.copyOf(claims));
        }
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            int current = in.readUnsignedByte();
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) {
                if (value < 0) {
                    throw new IOException("negative varint");
                }
                return value;
            }
            position += 7;
            if (position >= 35) {
                throw new IOException("varint too long");
            }
        }
    }

    private static String readString(DataInputStream in, int maxBytes) throws IOException {
        int length = readVarInt(in);
        if (length < 0 || length > maxBytes) {
            throw new IOException("bad string length");
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] inflate(byte[] data) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(data.length * 3 + 64, MAX_INFLATED));
        byte[] chunk = new byte[8192];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(chunk);
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                    continue;
                }
                buffer.write(chunk, 0, count);
                if (buffer.size() > MAX_INFLATED) {
                    throw new IOException("inflate limit exceeded");
                }
            }
        } catch (java.util.zip.DataFormatException exception) {
            throw new IOException(exception);
        } finally {
            inflater.end();
        }
        return buffer.toByteArray();
    }
}
