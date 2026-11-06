package com.tianyaworld.pmxxlclaims.client.xaero;

import com.tianyaworld.pmxxlclaims.client.ClaimCache;
import com.tianyaworld.pmxxlclaims.client.ClaimCodec;
import com.tianyaworld.pmxxlclaims.client.ClientConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xaero.common.minimap.render.MinimapRendererHelper;

import java.util.ArrayList;
import java.util.List;

public final class XaeroFboClaims {

    private static final Logger LOGGER = LoggerFactory.getLogger("pmxxlclaims");
    private static final long FRESH_MS = 1000L;

    private static volatile long lastDrawAt;
    private static volatile double lastLoggedDimensionScale = -1.0;
    private static volatile boolean loggedActive;

    private XaeroFboClaims() {
    }

    public static boolean isInLayer() {
        return System.currentTimeMillis() - lastDrawAt < FRESH_MS;
    }

    public static void draw(MatrixStack poseStack, MinimapRendererHelper helper, int xFloored, int zFloored, double dimensionScale,
                            int minViewX, int minViewZ, int maxViewX, int maxViewZ, double zoom, double uiScale) {
        if (!ClientConfig.enabled() || !ClientConfig.putInMapLayer()) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return;
        }
        String worldId = client.world.getRegistryKey().getValue().toString();
        List<ClaimCodec.Box> own = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        List<Boolean> subs = new ArrayList<>();
        for (ClaimCodec.Claim claim : ClaimCache.claimsFor(worldId)) {
            for (ClaimCodec.Box box : claim.boxes()) {
                own.add(box);
                colors.add(claim.color());
                subs.add((claim.flags() & 1) != 0);
            }
        }
        if (own.isEmpty()) {
            return;
        }
        lastDrawAt = System.currentTimeMillis();
        if (!loggedActive) {
            loggedActive = true;
            LOGGER.info("xaero fbo claims active");
        }
        if (dimensionScale != lastLoggedDimensionScale) {
            lastLoggedDimensionScale = dimensionScale;
            LOGGER.info("xaero fbo claims draw: dimensionScale=" + dimensionScale + " camera=" + xFloored + "," + zFloored);
        }
        int alpha = ClientConfig.claimAlpha();
        double viewBlocksX = Math.max(1, maxViewX - minViewX);
        double viewBlocksZ = Math.max(1, maxViewZ - minViewZ);
        MinecraftClient mc = MinecraftClient.getInstance();
        double screenWidth = 128.0;
        try {
            var session = xaero.hud.minimap.BuiltInHudModules.MINIMAP.getCurrentSession();
            if (session != null) {
                screenWidth = session.getWidth(mc.getWindow().getScaleFactor());
            }
        } catch (Throwable ignored) {
        }
        double pixelsPerBlock = screenWidth / viewBlocksX;
        if (!(pixelsPerBlock > 0.0001)) {
            pixelsPerBlock = 1.0;
        }
        float mainThickness = (float) Math.max(0.05, 2.0 / pixelsPerBlock);
        float subThickness = (float) Math.max(0.05, 1.0 / pixelsPerBlock);
        int borderColor = 0xE0FFFFFF;
        poseStack.push();
        try {
            poseStack.translate((float) -xFloored, (float) -zFloored, 0.0f);
            for (int i = 0; i < own.size(); i++) {
                ClaimCodec.Box box = own.get(i);
                int color = (alpha << 24) | (colors.get(i) & 0xFFFFFF);
                float x1 = box.minX();
                float y1 = box.minZ();
                float x2 = box.maxX() + 1.0f;
                float y2 = box.maxZ() + 1.0f;
                helper.drawMyColoredRect(poseStack.peek().getPositionMatrix(), x1, y1, x2, y2, color);
                float thickness = subs.get(i) ? subThickness : mainThickness;
                helper.drawMyColoredRect(poseStack.peek().getPositionMatrix(), x1, y1, x2, y1 + thickness, borderColor);
                helper.drawMyColoredRect(poseStack.peek().getPositionMatrix(), x1, y2 - thickness, x2, y2, borderColor);
                helper.drawMyColoredRect(poseStack.peek().getPositionMatrix(), x1, y1, x1 + thickness, y2, borderColor);
                helper.drawMyColoredRect(poseStack.peek().getPositionMatrix(), x2 - thickness, y1, x2, y2, borderColor);
            }
        } finally {
            poseStack.pop();
        }
    }
}