package com.tianyaworld.pmxxlclaims.client.xaero;

import com.tianyaworld.pmxxlclaims.client.ClaimCache;
import com.tianyaworld.pmxxlclaims.client.ClaimCodec;
import com.tianyaworld.pmxxlclaims.client.ClientConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xaero.map.WorldMap;
import xaero.map.element.MapElementGraphics;
import xaero.map.element.MapElementRenderHandler;
import xaero.map.element.render.ElementReader;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderProvider;
import xaero.map.element.render.ElementRenderer;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;

import java.util.ArrayList;
import java.util.List;

public final class XaeroWorldMapClaimsBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("pmxxlclaims");
    private static final long RETRY_MS = 3000L;
    private static final int MAX_ATTEMPTS = 40;

    private static volatile boolean registered;
    private static volatile boolean failed;
    private static volatile long lastAttempt;
    private static volatile int attempts;
    private static volatile long lastLogAt;

    private XaeroWorldMapClaimsBridge() {
    }

    public static void tick(MinecraftClient client) {
        if (registered || failed || !ClientConfig.enabled()) {
            return;
        }
        if (!FabricLoader.getInstance().isModLoaded("xaeroworldmap")) {
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
            LOGGER.warn("xaero world map claims registration gave up after " + attempts + " attempts");
            return;
        }
        try {
            MapElementRenderHandler handler = WorldMap.mapElementRenderHandler;
            if (handler == null) {
                return;
            }
            handler.add(new ClaimMapRenderer());
            registered = true;
            LOGGER.info("xaero world map claims overlay registered");
        } catch (Throwable throwable) {
            failed = true;
            LOGGER.warn("failed to register xaero world map claims overlay", throwable);
        }
    }

    record ClaimBox(int minX, int minZ, int maxX, int maxZ, int color, boolean subzone) {
    }

    private static final class ClaimProvider extends ElementRenderProvider<ClaimBox, Object> {

        private List<ClaimBox> boxes = List.of();
        private int index;
        private int cachedVersion = -1;
        private String cachedWorld = "";

        @Override
        public void begin(ElementRenderLocation location, Object context) {
            index = 0;
            if (location != ElementRenderLocation.WORLD_MAP) {
                boxes = List.of();
                return;
            }
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
            boxes = collect(worldId);
        }

        @Override
        public boolean hasNext(ElementRenderLocation location, Object context) {
            return index < boxes.size();
        }

        @Override
        public ClaimBox getNext(ElementRenderLocation location, Object context) {
            return boxes.get(index++);
        }

        @Override
        public void end(ElementRenderLocation location, Object context) {
            index = 0;
        }
    }

    private static final class ClaimReader extends ElementReader<ClaimBox, Object, ClaimMapRenderer> {

        @Override
        public boolean isHidden(ClaimBox box, Object context) {
            return false;
        }

        @Override
        public double getRenderX(ClaimBox box, Object context, float partialTicks) {
            return (box.minX + box.maxX + 1) / 2.0;
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
        public int getLeftSideLength(ClaimBox box, MinecraftClient client) {
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
    }

    private static final class ClaimMapRenderer extends ElementRenderer<ClaimBox, Object, ClaimMapRenderer> {

        ClaimMapRenderer() {
            super(new Object(), new ClaimProvider(), new ClaimReader());
        }

        @Override
        public void preRender(ElementRenderInfo info, VertexConsumerProvider.Immediate buffers,
                              MultiTextureRenderTypeRendererProvider providers, boolean hovered) {
        }

        @Override
        public void postRender(ElementRenderInfo info, VertexConsumerProvider.Immediate buffers,
                               MultiTextureRenderTypeRendererProvider providers, boolean hovered) {
        }

        @Override
        public void renderElementShadow(ClaimBox box, boolean hovered, float partialTicks, double x, double z,
                                        ElementRenderInfo info, MapElementGraphics graphics,
                                        VertexConsumerProvider.Immediate buffers,
                                        MultiTextureRenderTypeRendererProvider providers) {
        }

        @Override
        public boolean renderElement(ClaimBox box, boolean hovered, double x, float y, double z, double depth,
                                     ElementRenderInfo info, MapElementGraphics graphics,
                                     VertexConsumerProvider.Immediate buffers,
                                     MultiTextureRenderTypeRendererProvider providers) {
            if (!ClientConfig.enabled()) {
                return false;
            }
            double units = info.scale > 0.0001 && info.scale < 1000.0 ? 1.0 / info.scale : 1.0;
            units *= ClientConfig.xaeroScale();
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
            long now = System.currentTimeMillis();
            if (now - lastLogAt > 5000L) {
                lastLogAt = now;
                LOGGER.info("xaero world map element: units=" + units + " scale=" + info.scale + " screenScale=" + info.screenSizeBasedScale + " bgScale=" + info.backgroundCoordinateScale);
            }
            return true;
        }

        @Override
        public boolean shouldRender(ElementRenderLocation location, boolean hovered) {
            return location == ElementRenderLocation.WORLD_MAP || location == ElementRenderLocation.WORLD_MAP_MENU;
        }
    }

    private static List<ClaimBox> collect(String worldId) {
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