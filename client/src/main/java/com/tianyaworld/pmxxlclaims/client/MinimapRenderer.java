package com.tianyaworld.pmxxlclaims.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.world.ClientWorld;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MinimapRenderer implements HudElement {

    private record Draw(String name, int color, int flags, int[][] boxes) {
    }

    private static final int[] PALETTE = {
            0x4FC3F7, 0x81C784, 0xFFB74D, 0xE57373,
            0xBA68C8, 0x4DB6AC, 0xF06292, 0x9575CD
    };

    private static boolean visible = true;
    private static String cachedWorld = "";
    private static int cachedPackets = -1;
    private static int cachedVersion = -1;
    private static boolean cachedActive;
    private static List<Draw> cachedDraws = List.of();

    public static void toggle() {
        visible = !visible;
    }

    public static void setVisible(boolean value) {
        visible = value;
    }

    public static boolean isVisible() {
        return visible;
    }

    @Override
    public void render(DrawContext context, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!visible || client.player == null || client.world == null || client.options.hudHidden) {
            return;
        }
        ClientWorld world = client.world;
        int size = ClientConfig.minimapSize();
        int margin = ClientConfig.minimapMargin();
        int alpha = ClientConfig.claimAlpha();
        int screenWidth = context.getScaledWindowWidth();
        int left = screenWidth - size - margin;
        int top = margin;
        int right = left + size;
        int bottom = top + size;

        int playerX = client.player.getBlockX();
        int playerZ = client.player.getBlockZ();
        int originX = left + size / 2 - playerX;
        int originZ = top + size / 2 - playerZ;

        context.fill(left - 1, top - 1, right + 1, bottom + 1, 0xC0101010);
        context.enableScissor(left, top, right, bottom);
        try {
            int chunkRadius = size / 32 + 1;
            int centerChunkX = playerX >> 4;
            int centerChunkZ = playerZ >> 4;
            for (int chunkX = centerChunkX - chunkRadius; chunkX <= centerChunkX + chunkRadius; chunkX++) {
                for (int chunkZ = centerChunkZ - chunkRadius; chunkZ <= centerChunkZ + chunkRadius; chunkZ++) {
                    int pixelX = originX + (chunkX << 4);
                    int pixelZ = originZ + (chunkZ << 4);
                    int chunkColor = ChunkColorCache.color(world, chunkX, chunkZ);
                    context.fill(pixelX, pixelZ, pixelX + 16, pixelZ + 16, chunkColor);
                }
            }
            List<Draw> draws = draws(worldId(world));
            for (Draw draw : draws) {
                drawOne(context, client, draw, left, top, size, alpha, originX, originZ);
            }
            drawPlayer(context, left + size / 2, top + size / 2);
        } finally {
            context.disableScissor();
        }
        if (ClientConfig.showCount()) {
            int count = draws(worldId(world)).size();
            context.drawText(client.textRenderer, "claims " + count, left + 2, top + 2, 0xFFFFFFFF, true);
        }
    }

    public static String worldId(ClientWorld world) {
        return world.getRegistryKey().getValue().toString();
    }

    private List<Draw> draws(String worldId) {
        int packets = ClaimCache.packets();
        int version = ClaimStore.version();
        boolean active = ClaimCache.isActive();
        if (worldId.equals(cachedWorld) && packets == cachedPackets && version == cachedVersion && active == cachedActive) {
            return cachedDraws;
        }
        List<Draw> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (ClaimCodec.Claim claim : ClaimCache.claimsFor(worldId)) {
            List<int[]> boxes = new ArrayList<>(claim.boxes().size());
            for (ClaimCodec.Box box : claim.boxes()) {
                boxes.add(new int[]{box.minX(), box.minZ(), box.maxX(), box.maxZ()});
            }
            int color = claim.color() == 0 ? PALETTE[Math.floorMod(claim.name().hashCode(), PALETTE.length)] : claim.color();
            out.add(new Draw(claim.name(), color, claim.flags(), boxes.toArray(new int[0][])));
            names.add(claim.name());
        }
        if (!ClaimCache.isActive()) {
            for (ClaimStore.KnownClaim claim : ClaimStore.forWorld(worldId)) {
                if (!names.add(claim.name())) {
                    continue;
                }
                List<int[]> boxes = new ArrayList<>(claim.boxes().size());
                for (ClaimStore.Box box : claim.boxes()) {
                    boxes.add(new int[]{box.minX(), box.minZ(), box.maxX(), box.maxZ()});
                }
                out.add(new Draw(claim.name(), PALETTE[Math.floorMod(claim.name().hashCode(), PALETTE.length)], 0, boxes.toArray(new int[0][])));
            }
        }
        cachedWorld = worldId;
        cachedPackets = packets;
        cachedVersion = version;
        cachedActive = active;
        cachedDraws = out;
        return out;
    }

    private void drawOne(DrawContext context, MinecraftClient client, Draw draw, int left, int top, int size, int alpha, int originX, int originZ) {
        int fillColor = (alpha << 24) | (draw.color() & 0xFFFFFF);
        int borderColor = 0xE0FFFFFF;
        int thickness = (draw.flags() & 1) != 0 ? 1 : 2;
        for (int[] box : draw.boxes()) {
            int x1 = originX + box[0];
            int z1 = originZ + box[1];
            int x2 = originX + box[2] + 1;
            int z2 = originZ + box[3] + 1;
            if (x2 < left || x1 > left + size || z2 < top || z1 > top + size) {
                continue;
            }
            context.fill(x1, z1, x2, z2, fillColor);
            context.fill(x1, z1, x2, z1 + thickness, borderColor);
            context.fill(x1, z2 - thickness, x2, z2, borderColor);
            context.fill(x1, z1, x1 + thickness, z2, borderColor);
            context.fill(x2 - thickness, z1, x2, z2, borderColor);
            int centerX = (x1 + x2) / 2;
            int centerZ = (z1 + z2) / 2;
            if (x2 - x1 > 40 && z2 - z1 > 16) {
                int textX = Math.max(left + 10, Math.min(left + size - 10, centerX));
                int textZ = Math.max(top + 4, Math.min(top + size - 12, centerZ - 4));
                context.drawCenteredTextWithShadow(client.textRenderer, draw.name(), textX, textZ, 0xFFFFFFFF);
            }
        }
    }

    private void drawPlayer(DrawContext context, int centerX, int centerZ) {
        context.fill(centerX - 1, centerZ - 4, centerX + 1, centerZ + 2, 0xFFFFFFFF);
        context.fill(centerX - 3, centerZ + 2, centerX + 3, centerZ + 4, 0xFFFFFFFF);
    }
}
