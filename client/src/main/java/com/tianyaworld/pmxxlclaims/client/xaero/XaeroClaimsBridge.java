package com.tianyaworld.pmxxlclaims.client.xaero;

import com.tianyaworld.pmxxlclaims.client.ClaimCache;
import com.tianyaworld.pmxxlclaims.client.ClaimCodec;
import com.tianyaworld.pmxxlclaims.client.ClientConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xaero.common.HudMod;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.Minimap;
import xaero.hud.minimap.element.render.MinimapElementGraphics;
import xaero.hud.minimap.element.render.MinimapElementReader;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
import xaero.hud.minimap.element.render.MinimapElementRenderProvider;
import xaero.hud.minimap.element.render.MinimapElementRenderer;
import xaero.hud.minimap.element.render.over.MinimapElementOverMapRendererHandler;
import xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions;
import xaero.hud.minimap.config.util.MinimapConfigClientUtils;
import xaero.hud.minimap.module.MinimapSession;
import xaero.lib.client.config.ClientConfigManager;

import java.util.ArrayList;
import java.util.List;

public final class XaeroClaimsBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("pmxxlclaims");
    private static final long RETRY_MS = 3000L;
    private static final int MAX_ATTEMPTS = 40;
    private static final long RENDER_FRESH_MS = 1000L;
    private static final double MIN_SCALE = 0.01;
    private static final double MAX_SCALE = 64.0;

    private static volatile boolean registered;
    private static volatile boolean failed;
    private static volatile long lastAttempt;
    private static volatile long lastRenderedAt;
    private static volatile int attempts;
    private static volatile double lastLoggedScale = -1.0;
    private static volatile long lastLogAt;

    private XaeroClaimsBridge() {
    }

    public static boolean isRendering() {
        return registered && !failed && System.currentTimeMillis() - lastRenderedAt < RENDER_FRESH_MS;
    }

    public static void tick(MinecraftClient client) {
        if (registered || failed || !ClientConfig.enabled()) {
            return;
        }
        if (!FabricLoader.getInstance().isModLoaded("xaerominimap")) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAttempt < RETRY_MS) {
            return;
        }
        lastAttempt = now;
        attempts++;
        if (attempts > MAX_ATTEMPTS) {
            failed = true;
            LOGGER.warn("xaero claims overlay registration gave up after " + attempts + " attempts");
            return;
        }
        try {
            HudMod mod = HudMod.INSTANCE;
            if (mod == null) {
                return;
            }
            Minimap minimap = mod.getMinimap();
            if (minimap == null) {
                return;
            }
            MinimapElementOverMapRendererHandler handler = minimap.getOverMapRendererHandler();
            if (handler == null) {
                return;
            }
            handler.add(new ClaimElementRenderer());
            registered = true;
            LOGGER.info("xaero claims overlay registered");
        } catch (Throwable throwable) {
            failed = true;
            LOGGER.warn("failed to register xaero claims overlay", throwable);
        }
    }

    static double unitsPerBlock() {
        double zoom = 1.0;
        double uiScale = 1.0;
        try {
            MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
            if (session != null && session.getProcessor() != null) {
                zoom = session.getProcessor().getMinimapZoom();
            }
            HudMod mod = HudMod.INSTANCE;
            if (mod != null && mod.getSettings() != null) {
                uiScale = mod.getSettings().getMinimapScale();
            }
        } catch (Throwable ignored) {
        }
        double units = zoom * uiScale / 2.0;
        if (!(units > MIN_SCALE) || units > MAX_SCALE) {
            units = 1.0;
        }
        return units * ClientConfig.xaeroScale();
    }

    static boolean northLocked() {
        try {
            HudMod mod = HudMod.INSTANCE;
            MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
            if (mod == null || session == null || session.getProcessor() == null) {
                return false;
            }
            ClientConfigManager configs = mod.getHudConfigs().getClientConfigManager();
            Object shapeValue = configs.getEffective(MinimapProfiledConfigOptions.SHAPE);
            int shape = shapeValue instanceof Integer ? (Integer) shapeValue : 1;
            int halfSize = session.getProcessor().getMinimapSize() / 2;
            return MinimapConfigClientUtils.getEffectiveNorthLocked(halfSize, shape);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static double renderAngleDegrees() {
        try {
            MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
            if (session != null && session.getProcessor() != null && session.getProcessor().getRenderer() != null) {
                return session.getProcessor().getRenderer().getRenderAngle(northLocked()) - 90.0;
            }
        } catch (Throwable ignored) {
        }
        return 0.0;
    }

    public record ClaimBox(int minX, int minZ, int maxX, int maxZ, int color, boolean subzone) {
    }

    private static final class ClaimProvider extends MinimapElementRenderProvider<ClaimBox, Object> {

        private List<ClaimBox> boxes = List.of();
        private int index;
        private int cachedVersion = -1;
        private String cachedWorld = "";

        @Override
        public void begin(MinimapElementRenderLocation location, Object context) {
            index = 0;
            if (location != MinimapElementRenderLocation.OVER_MINIMAP) {
                boxes = List.of();
                return;
            }
            lastRenderedAt = System.currentTimeMillis();
            ClientWorld world = MinecraftClient.getInstance().world;
            if (world == null) {
                boxes = List.of();
                return;
            }
            String worldId = world.getRegistryKey().getValue().toString();
            int version = ClaimCache.contentVersion();
            if (version == cachedVersion && worldId.equals(cachedWorld)) {
                return;
            }
            cachedVersion = version;
            cachedWorld = worldId;
            boxes = ClaimCacheIndex.collect(worldId);
        }

        @Override
        public boolean hasNext(MinimapElementRenderLocation location, Object context) {
            return index < boxes.size();
        }

        @Override
        public ClaimBox getNext(MinimapElementRenderLocation location, Object context) {
            return boxes.get(index++);
        }

        @Override
        public void end(MinimapElementRenderLocation location, Object context) {
            index = 0;
        }
    }

    private static final class ClaimReader extends MinimapElementReader<ClaimBox, Object> {

        @Override
        public boolean isHidden(ClaimBox box, Object context) {
            return false;
        }

        @Override
        public double getRenderX(ClaimBox box, Object context, float partialTicks) {
            return (box.minX + box.maxX + 1) / 2.0;
        }

        @Override
        public double getRenderY(ClaimBox box, Object context, float partialTicks) {
            return 64.0;
        }

        @Override
        public double getRenderZ(ClaimBox box, Object context, float partialTicks) {
            return (box.minZ + box.maxZ + 1) / 2.0;
        }

        @Override
        public int getInteractionBoxLeft(ClaimBox box, Object context, float partialTicks) {
            return 0;
        }

        @Override
        public int getInteractionBoxRight(ClaimBox box, Object context, float partialTicks) {
            return 0;
        }

        @Override
        public int getInteractionBoxTop(ClaimBox box, Object context, float partialTicks) {
            return 0;
        }

        @Override
        public int getInteractionBoxBottom(ClaimBox box, Object context, float partialTicks) {
            return 0;
        }

        @Override
        public int getRenderBoxLeft(ClaimBox box, Object context, float partialTicks) {
            return 0;
        }

        @Override
        public int getRenderBoxRight(ClaimBox box, Object context, float partialTicks) {
            return 0;
        }

        @Override
        public int getRenderBoxTop(ClaimBox box, Object context, float partialTicks) {
            return 0;
        }

        @Override
        public int getRenderBoxBottom(ClaimBox box, Object context, float partialTicks) {
            return 0;
        }

        @Override
        public int getLeftSideLength(ClaimBox box, net.minecraft.client.MinecraftClient client) {
            return 0;
        }

        @Override
        public String getMenuName(ClaimBox box) {
            return "";
        }

        @Override
        public String getFilterName(ClaimBox box) {
            return "claims";
        }

        @Override
        public int getMenuTextFillLeftPadding(ClaimBox box) {
            return 0;
        }

        @Override
        public int getRightClickTitleBackgroundColor(ClaimBox box) {
            return 0;
        }

        @Override
        public boolean shouldScaleBoxWithOptionalScale() {
            return false;
        }

        @Override
        public boolean isInteractable(MinimapElementRenderLocation location, ClaimBox box) {
            return false;
        }
    }

    private static boolean scissorActive;

    private static double visibleBlocks() {
        try {
            MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
            if (session == null || session.getProcessor() == null) {
                return 0.0;
            }
            double zoom = session.getProcessor().getMinimapZoom();
            if (!(zoom > 0.0001)) {
                return 0.0;
            }
            return session.getConfiguredWidth() / zoom;
        } catch (Throwable ignored) {
            return 0.0;
        }
    }

    private static int[] clampToView(ClaimBox box) {
        double radius = visibleBlocks();
        if (!(radius > 0.0)) {
            return new int[]{box.minX, box.minZ, box.maxX, box.maxZ};
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return new int[]{box.minX, box.minZ, box.maxX, box.maxZ};
        }
        double limit = radius * 0.7;
        int px = client.player.getBlockX();
        int pz = client.player.getBlockZ();
        int minX = (int) Math.max(box.minX, Math.ceil(px - limit));
        int maxX = (int) Math.min(box.maxX, Math.floor(px + limit));
        int minZ = (int) Math.max(box.minZ, Math.ceil(pz - limit));
        int maxZ = (int) Math.min(box.maxZ, Math.floor(pz + limit));
        if (minX > maxX || minZ > maxZ) {
            return null;
        }
        return new int[]{minX, minZ, maxX, maxZ};
    }

    private static boolean applyMinimapScissor() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
            if (client == null || session == null || session.getProcessor() == null) {
                return false;
            }
            Window window = client.getWindow();
            double scale = window.getScaleFactor();
            if (!(scale > 0.0)) {
                return false;
            }
            int screenWidth = window.getScaledWidth();
            int screenHeight = window.getScaledHeight();
            int width = session.getWidth(scale);
            int height = session.getHeight(scale);
            int x = session.getEffectiveX(screenWidth, scale);
            int y = session.getEffectiveY(screenHeight, scale);
            if (width <= 0 || height <= 0) {
                return false;
            }
            int pixelX = (int) Math.round(x * scale);
            int pixelY = (int) Math.round((screenHeight - y - height) * scale);
            int pixelWidth = (int) Math.round(width * scale);
            int pixelHeight = (int) Math.round(height * scale);
            RenderSystem.enableScissorForRenderTypeDraws(pixelX, pixelY, pixelWidth, pixelHeight);
            return true;
        } catch (Throwable throwable) {
            return false;
        }
    }

    private static final class ClaimElementRenderer extends MinimapElementRenderer<ClaimBox, Object> {

        ClaimElementRenderer() {
            super(new ClaimReader(), new ClaimProvider(), new Object());
        }

        @Override
        public boolean renderElement(ClaimBox box, boolean first, boolean clipped, double x, float y, double z,
                                     double depth, MinimapElementRenderInfo info, MinimapElementGraphics graphics,
                                     VertexConsumerProvider.Immediate buffers) {
            if (clipped || !ClientConfig.enabled()) {
                return false;
            }
            double units = unitsPerBlock();
            long now = System.currentTimeMillis();
            if ((lastLoggedScale < 0 || Math.abs(units - lastLoggedScale) > lastLoggedScale * 0.05) && now - lastLogAt > 5000L) {
                lastLoggedScale = units;
                lastLogAt = now;
                LOGGER.info("xaero claim units-per-block=" + units + " visibleRadius=" + visibleBlocks());
            }
            int[] clamped = clampToView(box);
            if (clamped == null) {
                return false;
            }
            double centerX = (box.minX + box.maxX + 1) / 2.0;
            double centerZ = (box.minZ + box.maxZ + 1) / 2.0;
            int left = (int) Math.round((clamped[0] - centerX) * units);
            int top = (int) Math.round((clamped[1] - centerZ) * units);
            int right = (int) Math.round((clamped[2] + 1 - centerX) * units);
            int bottom = (int) Math.round((clamped[3] + 1 - centerZ) * units);
            int width = Math.max(1, right - left);
            int height = Math.max(1, bottom - top);
            int alpha = ClientConfig.claimAlpha();
            int fill = (alpha << 24) | (box.color & 0xFFFFFF);
            int border = 0xE0FFFFFF;
            MatrixStack pose = graphics.pose();
            pose.push();
            try {
                double angle = renderAngleDegrees();
                if (angle != 0.0) {
                    pose.multiplyPositionMatrix(new Matrix4f().rotationZ((float) Math.toRadians(angle)));
                }
                graphics.fill(left, top, right, bottom, fill);
                if (width >= 4 && height >= 4) {
                    int thickness = box.subzone ? 1 : 2;
                    graphics.fill(left, top, right, top + thickness, border);
                    graphics.fill(left, bottom - thickness, right, bottom, border);
                    graphics.fill(left, top, left + thickness, bottom, border);
                    graphics.fill(right - thickness, top, right, bottom, border);
                }
            } finally {
                pose.pop();
            }
            lastRenderedAt = System.currentTimeMillis();
            return true;
        }

        @Override
        public void preRender(MinimapElementRenderInfo info, VertexConsumerProvider.Immediate buffers,
                              xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider providers) {
            scissorActive = ClientConfig.clipToMinimap() && applyMinimapScissor();
        }

        @Override
        public void postRender(MinimapElementRenderInfo info, VertexConsumerProvider.Immediate buffers,
                               xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider providers) {
            if (scissorActive) {
                try {
                    buffers.draw();
                } catch (Throwable ignored) {
                }
                scissorActive = false;
                RenderSystem.disableScissorForRenderTypeDraws();
            }
        }

        @Override
        public boolean shouldRender(MinimapElementRenderLocation location) {
            return location == MinimapElementRenderLocation.OVER_MINIMAP;
        }
    }

    private static final class ClaimCacheIndex {

        record Entry(int minX, int minZ, int maxX, int maxZ, int color, boolean subzone) {
        }

        static List<ClaimBox> collect(String worldId) {
            List<ClaimBox> entries = new ArrayList<>();
            for (ClaimCodec.Claim claim : ClaimCache.claimsFor(worldId)) {
                for (ClaimCodec.Box box : claim.boxes()) {
                    entries.add(new ClaimBox(box.minX(), box.minZ(), box.maxX(), box.maxZ(),
                            claim.color(), (claim.flags() & 1) != 0));
                }
            }
            if (entries.size() > 256) {
                return new ArrayList<>(entries.subList(0, 256));
            }
            return entries;
        }
    }
}