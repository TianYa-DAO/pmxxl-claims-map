package com.tianyaworld.pmxxlclaims.client.xaero;

import com.tianyaworld.pmxxlclaims.client.ClaimCache;
import com.tianyaworld.pmxxlclaims.client.ClaimCodec;
import com.tianyaworld.pmxxlclaims.client.ClientConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.world.ClientWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xaero.common.HudMod;
import xaero.hud.minimap.Minimap;
import xaero.hud.minimap.element.render.MinimapElementGraphics;
import xaero.hud.minimap.element.render.MinimapElementReader;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
import xaero.hud.minimap.element.render.MinimapElementRenderProvider;
import xaero.hud.minimap.element.render.MinimapElementRenderer;
import xaero.hud.minimap.element.render.over.MinimapElementOverMapRendererHandler;

import java.util.ArrayList;
import java.util.List;

public final class XaeroClaimsBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("pmxxlclaims");
    private static final long RETRY_MS = 3000L;

    private static volatile boolean registered;
    private static volatile boolean failed;
    private static volatile long lastAttempt;
    private static volatile double lastLoggedScale = -1.0;

    private XaeroClaimsBridge() {
    }

    public static boolean isActive() {
        return registered;
    }

    public static void tick(MinecraftClient client) {
        if (registered || failed) {
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
            LOGGER.warn("failed to register xaero claims overlay: " + throwable);
        }
    }

    public record ClaimBox(int minX, int minZ, int maxX, int maxZ, int color, boolean subzone) {
    }

    private static final class ClaimProvider extends MinimapElementRenderProvider<ClaimBox, Object> {

        private final List<ClaimBox> boxes = new ArrayList<>();
        private int index;

        @Override
        public void begin(MinimapElementRenderLocation location, Object context) {
            boxes.clear();
            index = 0;
            if (location != MinimapElementRenderLocation.OVER_MINIMAP) {
                return;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null) {
                return;
            }
            ClientWorld world = client.world;
            String worldId = world.getRegistryKey().getValue().toString();
            List<ClaimCacheIndex.Entry> entries = ClaimCacheIndex.collect(worldId, client.player.getBlockX(), client.player.getBlockZ());
            for (ClaimCacheIndex.Entry entry : entries) {
                boxes.add(new ClaimBox(entry.minX, entry.minZ, entry.maxX, entry.maxZ, entry.color, entry.subzone));
            }
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
            boxes.clear();
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
        public boolean renderElement(ClaimBox box, boolean first, boolean second, double x, float y, double z,
                                     double depth, MinimapElementRenderInfo info, MinimapElementGraphics graphics,
                                     VertexConsumerProvider.Immediate buffers) {
            double coordinateScale = info.backgroundCoordinateScale;
            if (!(coordinateScale > 0.01) || coordinateScale > 64.0) {
                coordinateScale = 1.0;
            }
            double scale = coordinateScale * ClientConfig.xaeroScale();
            int width = Math.max(1, (int) Math.round((box.maxX - box.minX + 1) * scale));
            int height = Math.max(1, (int) Math.round((box.maxZ - box.minZ + 1) * scale));
            if (scale != lastLoggedScale) {
                lastLoggedScale = scale;
                LOGGER.info("xaero claim render scale=" + scale + " (coordinate=" + coordinateScale + ", config=" + ClientConfig.xaeroScale() + ")");
            }
            int alpha = ClientConfig.claimAlpha();
            int fill = (alpha << 24) | (box.color & 0xFFFFFF);
            int border = 0xE0FFFFFF;
            int left = -width / 2;
            int top = -height / 2;
            int right = left + width;
            int bottom = top + height;
            graphics.fill(left, top, right, bottom, fill);
            int thickness = box.subzone ? 1 : 2;
            graphics.fill(left, top, right, top + thickness, border);
            graphics.fill(left, bottom - thickness, right, bottom, border);
            graphics.fill(left, top, left + thickness, bottom, border);
            graphics.fill(right - thickness, top, right, bottom, border);
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

        static List<Entry> collect(String worldId, int playerX, int playerZ) {
            List<Entry> entries = new ArrayList<>();
            for (ClaimCodec.Claim claim : ClaimCache.claimsFor(worldId)) {
                for (ClaimCodec.Box box : claim.boxes()) {
                    entries.add(new Entry(box.minX(), box.minZ(), box.maxX(), box.maxZ(),
                            claim.color(), (claim.flags() & 1) != 0));
                }
            }
            entries.sort((a, b) -> {
                long da = dist2(a, playerX, playerZ);
                long db = dist2(b, playerX, playerZ);
                return Long.compare(da, db);
            });
            if (entries.size() > 512) {
                return new ArrayList<>(entries.subList(0, 512));
            }
            return entries;
        }

        private static long dist2(Entry entry, int playerX, int playerZ) {
            long dx = (entry.minX + entry.maxX) / 2L - playerX;
            long dz = (entry.minZ + entry.maxZ) / 2L - playerZ;
            return dx * dx + dz * dz;
        }
    }
}