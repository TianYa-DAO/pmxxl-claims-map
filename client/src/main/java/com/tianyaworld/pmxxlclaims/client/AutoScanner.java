package com.tianyaworld.pmxxlclaims.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AutoScanner {

    private static final long SUPPRESS_MS = 1500L;
    private static final long QUERY_TTL_MS = 10 * 60 * 1000L;
    private static final int MAX_TRACKED = 4096;

    private static final Map<String, Long> QUERIED = new LinkedHashMap<>();
    private static long suppressUntil;
    private static boolean sawCoord;
    private static long lastQueryAt;
    private static String lastWorld = "";
    private static long lastChunk = Long.MIN_VALUE;

    private AutoScanner() {
    }

    public static void reset() {
        QUERIED.clear();
        suppressUntil = 0L;
        sawCoord = false;
        lastQueryAt = 0L;
        lastWorld = "";
        lastChunk = Long.MIN_VALUE;
    }

    public static void tick(MinecraftClient client) {
        if (!ClientConfig.autoScan() || ClaimCache.isActive()) {
            return;
        }
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || player.networkHandler == null) {
            return;
        }
        String world = MinimapRenderer.worldId(client.world);
        if (!world.equals(lastWorld)) {
            lastWorld = world;
            lastChunk = Long.MIN_VALUE;
        }
        int chunkX = player.getBlockX() >> 4;
        int chunkZ = player.getBlockZ() >> 4;
        long chunk = ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
        if (chunk == lastChunk) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastQueryAt < ClientConfig.intervalMs()) {
            return;
        }
        lastChunk = chunk;
        String key = world + "|" + chunkX + ":" + chunkZ;
        Long queriedAt = QUERIED.get(key);
        if (queriedAt != null && now - queriedAt < QUERY_TTL_MS) {
            return;
        }
        if (insideKnownClaim(player, world)) {
            remember(key, now);
            return;
        }
        lastQueryAt = now;
        suppressUntil = now + SUPPRESS_MS;
        sawCoord = false;
        remember(key, now);
        player.networkHandler.sendChatCommand("res show");
    }

    private static void remember(String key, long now) {
        QUERIED.put(key, now);
        if (QUERIED.size() > MAX_TRACKED) {
            Iterator<Map.Entry<String, Long>> iterator = QUERIED.entrySet().iterator();
            int remove = QUERIED.size() - MAX_TRACKED;
            while (iterator.hasNext() && remove-- > 0) {
                iterator.next();
                iterator.remove();
            }
        }
    }

    private static boolean insideKnownClaim(ClientPlayerEntity player, String world) {
        int x = player.getBlockX();
        int z = player.getBlockZ();
        for (ClaimStore.KnownClaim claim : ClaimStore.forWorld(world)) {
            for (ClaimStore.Box box : claim.boxes()) {
                if (x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ()) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean suppress(String line, boolean overlay) {
        if (overlay || System.currentTimeMillis() > suppressUntil || line == null) {
            return false;
        }
        boolean asciiCoords = line.contains("X:") && line.contains("Y:") && line.contains("Z:");
        boolean fullwidthCoords = line.contains("X\uFF1A") && line.contains("Y\uFF1A") && line.contains("Z\uFF1A");
        if (asciiCoords || fullwidthCoords) {
            sawCoord = true;
            return true;
        }
        if (line.contains("\u4e0d\u5728\u9886\u5730\u5185") || line.contains("\u4e0d\u5728\u4efb\u4f55\u9886\u5730")
                || line.contains("not in a residence") || line.contains("Not in a residence")) {
            return true;
        }
        if (sawCoord) {
            return line.contains("\u9886\u5730") || line.contains("\u6240\u6709\u8005")
                    || line.contains("Land") || line.contains("Owner") || line.contains("Residence");
        }
        return false;
    }
}
