package com.tianyaworld.pmxxlclaims.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class ClaimCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("pmxxlclaims");
    private static final long ACTIVE_MS = 60000L;
    private static final long LOG_INTERVAL_MS = 30000L;

    private static volatile String world = "";
    private static volatile List<ClaimCodec.Claim> claims = List.of();
    private static volatile boolean active;
    private static volatile long lastPacketAt;
    private static volatile long lastDropLogAt;
    private static volatile int packets;

    private ClaimCache() {
    }

    public static void accept(byte[] raw) {
        ClaimCodec.Result result = ClaimCodec.decode(raw);
        if (result == null) {
            long now = System.currentTimeMillis();
            if (now - lastDropLogAt > LOG_INTERVAL_MS) {
                lastDropLogAt = now;
                LOGGER.debug("dropped malformed claim packet (" + (raw == null ? 0 : raw.length) + " bytes)");
            }
            return;
        }
        world = result.world();
        claims = result.claims();
        active = true;
        lastPacketAt = System.currentTimeMillis();
        packets++;
    }

    public static void reset() {
        world = "";
        claims = List.of();
        active = false;
        lastPacketAt = 0L;
        packets++;
    }

    public static List<ClaimCodec.Claim> claimsFor(String worldId) {
        if (!isActive() || !world.equals(worldId)) {
            return List.of();
        }
        return claims;
    }

    public static boolean isActive() {
        return active && System.currentTimeMillis() - lastPacketAt < ACTIVE_MS;
    }

    public static int packets() {
        return packets;
    }
}
