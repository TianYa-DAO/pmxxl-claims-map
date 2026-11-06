package com.tianyaworld.pmxxlclaims.client.mixin;

import com.tianyaworld.pmxxlclaims.client.xaero.XaeroFboClaims;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.common.minimap.render.MinimapFBORenderer;
import xaero.common.minimap.render.MinimapRendererHelper;
import xaero.common.mods.SupportXaeroWorldmap;
import xaero.hud.minimap.module.MinimapSession;

@Mixin(value = MinimapFBORenderer.class, remap = false)
public abstract class MinimapFBORendererMixin {

    @Redirect(
            method = "renderChunksToFBO",
            at = @At(
                    value = "INVOKE",
                    target = "Lxaero/common/mods/SupportXaeroWorldmap;drawMinimap"
            ),
            require = 0
    )
    private void pmxxlclaims$drawClaimsIntoMap(SupportXaeroWorldmap instance, MinimapSession session, MatrixStack poseStack,
                                               MinimapRendererHelper helper, int xFloored, int zFloored,
                                               int minViewX, int minViewZ, int maxViewX, int maxViewZ,
                                               boolean zooming, double zoom, double dimensionScale,
                                               VertexConsumer overlayBuffer, MultiTextureRenderTypeRendererProvider providers) {
        instance.drawMinimap(session, poseStack, helper, xFloored, zFloored, minViewX, minViewZ, maxViewX, maxViewZ,
                zooming, zoom, dimensionScale, overlayBuffer, providers);
        XaeroFboClaims.draw(poseStack, helper, xFloored, zFloored, dimensionScale);
    }
}