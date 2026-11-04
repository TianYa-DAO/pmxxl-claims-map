package com.tianyaworld.pmxxlclaims.client.xaero;

import com.tianyaworld.pmxxlclaims.client.ClaimCache;
import com.tianyaworld.pmxxlclaims.client.ClaimCodec;
import com.tianyaworld.pmxxlclaims.client.ClientConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
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
                LOGGER.info("xaero claim units-per-block=" + units);
            }
            int width = Math.max(1, (int) Math.round((box.maxX - box.minX + 1) * units));
            int height = Math.max(1, (int) Math.round((box.maxZ - box.minZ + 1) * units));
            int alpha = ClientConfig.claimAlpha();
            int fill = (alpha << 24) | (box.color & 0xFFFFFF);
            int border = 0xE0FFFFFF;
            int halfW = width / 2;
            int halfH = height / 2;
            MatrixStack pose = graphics.pose();
            pose.push();
            try {
                double angle = renderAngleDegrees();
                if (angle != 0.0) {
                    pose.multiplyPositionMatrix(new Matrix4f().rotationZ((float) Math.toRadians(angle)));
                }
                graphics.fill(-halfW, -halfH, width - halfW, height - halfH, fill);
                if (width >= 4 && height >= 4) {
                    int thickness = box.subzone ? 1 : 2;
                    graphics.fill(-halfW, -halfH, width - halfW, -halfH + thickness, border);
                    graphics.fill(-halfW, height - halfH - thickness, width - halfW, height - halfH, border);
                    graphics.fill(-halfW, -halfH, -halfW + thickness, height - halfH, border);
                    graphics.fill(width - halfW - thickness, -halfH, width - halfW, height - halfH, border);
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
        }

        @Override
        public void postRender(MinimapElementRenderInfo info, VertexConsumerProvider.Immediate buffers,
                               xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider providers) {
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