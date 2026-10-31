package com.tianyaworld.pmxxlclaims.client.jm;

import com.tianyaworld.pmxxlclaims.client.ClaimCache;
import com.tianyaworld.pmxxlclaims.client.ClaimCodec;
import com.tianyaworld.pmxxlclaims.client.ClaimStore;
import com.tianyaworld.pmxxlclaims.client.ClientConfig;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.display.Context;
import journeymap.api.v2.client.display.DisplayType;
import journeymap.api.v2.client.display.PolygonOverlay;
import journeymap.api.v2.client.model.MapPolygon;
import journeymap.api.v2.client.model.ShapeProperties;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ClaimsOverlayManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("pmxxlclaims");
    private static final String MOD_ID = "pmxxlclaims";
    private static final long REBUILD_INTERVAL_MS = 500L;
    private static final int MAX_OVERLAYS = 1024;
    private static final int ORDER_BASE = 1100;

    private static volatile long lastRebuildAt;
    private static volatile boolean dirty = true;
    private static volatile boolean jmMinimapActive = true;
    private static String lastSignature = "";

    private ClaimsOverlayManager() {
    }

    public static void requestRebuild() {
        dirty = true;
    }

    public static boolean jmMinimapActive() {
        return jmMinimapActive;
    }

    public static void setJmMinimapActive(boolean active) {
        if (jmMinimapActive != active) {
            jmMinimapActive = active;
        }
    }

    public static void tick(MinecraftClient client) {
        IClientAPI api = ClaimsJourneyMapPlugin.api();
        if (api == null || client.player == null || client.world == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRebuildAt < REBUILD_INTERVAL_MS) {
            return;
        }
        lastRebuildAt = now;
        ClientWorld world = client.world;
        String worldId = world.getRegistryKey().getValue().toString();
        String signature = worldId + "|" + ClaimCache.contentVersion() + "|" + ClaimStore.version() + "|" + ClaimCache.isActive() + "|" + ClientConfig.claimAlpha();
        if (!dirty && signature.equals(lastSignature)) {
            return;
        }
        rebuild(api, client, world, worldId, signature);
    }

    private static void rebuild(IClientAPI api, MinecraftClient client, ClientWorld world, String worldId, String signature) {
        try {
            api.removeAll(MOD_ID, DisplayType.Polygon);
        } catch (RuntimeException exception) {
            LOGGER.warn("failed to clear claim overlays: " + exception.getMessage());
        }
        List<Entry> entries = collect(worldId, client);
        RegistryKey<World> dimension = world.getRegistryKey();
        boolean failed = false;
        int index = 0;
        for (Entry entry : entries) {
            try {
                ShapeProperties shape = new ShapeProperties();
                shape.setFillColor(entry.color & 0xFFFFFF);
                shape.setStrokeColor(0xFFFFFF);
                shape.setFillOpacity(ClientConfig.claimAlpha() / 255.0f);
                shape.setStrokeOpacity(0.85f);
                shape.setStrokeWidth(entry.subzone ? 1.0f : 2.0f);
                MapPolygon polygon = new MapPolygon(List.of(
                        new BlockPos(entry.minX, entry.y, entry.minZ),
                        new BlockPos(entry.maxX + 1, entry.y, entry.minZ),
                        new BlockPos(entry.maxX + 1, entry.y, entry.maxZ + 1),
                        new BlockPos(entry.minX, entry.y, entry.maxZ + 1)
                ));
                PolygonOverlay overlay = new PolygonOverlay(MOD_ID, dimension, shape, polygon);
                overlay.setLabel(entry.name);
                overlay.setTitle(entry.name);
                overlay.setDisplayOrder(ORDER_BASE + index * 2 + (entry.subzone ? 1 : 0));
                overlay.setActiveUIs(Context.UI.values());
                overlay.setActiveMapTypes(Context.MapType.values());
                api.show(overlay);
            } catch (Exception exception) {
                failed = true;
                LOGGER.debug("failed to show claim overlay " + entry.name + ": " + exception.getMessage());
            }
            index++;
        }
        dirty = failed;
        if (!failed) {
            lastSignature = signature;
        }
    }

    private static List<Entry> collect(String worldId, MinecraftClient client) {
        List<Entry> entries = new ArrayList<>();
        if (ClaimCache.isActive()) {
            for (ClaimCodec.Claim claim : ClaimCache.claimsFor(worldId)) {
                for (ClaimCodec.Box box : claim.boxes()) {
                    entries.add(new Entry(claim.name(), claim.color(), (claim.flags() & 1) != 0,
                            box.minX(), box.minZ(), box.maxX(), box.maxZ(), client.player.getBlockY()));
                }
            }
        } else {
            for (ClaimStore.KnownClaim claim : ClaimStore.forWorld(worldId)) {
                for (ClaimStore.Box box : claim.boxes()) {
                    entries.add(new Entry(claim.name(), 0x4FC3F7, false,
                            box.minX(), box.minZ(), box.maxX(), box.maxZ(), client.player.getBlockY()));
                }
            }
        }
        int px = client.player.getBlockX();
        int pz = client.player.getBlockZ();
        entries.sort(Comparator.comparingLong(entry -> {
            long dx = (entry.minX + entry.maxX) / 2L - px;
            long dz = (entry.minZ + entry.maxZ) / 2L - pz;
            return dx * dx + dz * dz;
        }));
        if (entries.size() > MAX_OVERLAYS) {
            return new ArrayList<>(entries.subList(0, MAX_OVERLAYS));
        }
        return entries;
    }

    private record Entry(String name, int color, boolean subzone, int minX, int minZ, int maxX, int maxZ, int y) {
    }
}
