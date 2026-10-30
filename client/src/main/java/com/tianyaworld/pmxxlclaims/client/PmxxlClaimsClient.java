package com.tianyaworld.pmxxlclaims.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PmxxlClaimsClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("pmxxlclaims");

    private static KeyBinding toggleKey;

    @Override
    public void onInitializeClient() {
        ClientConfig.load();
        ClaimStore.load();
        MinimapRenderer.setVisible(ClientConfig.minimapVisible());
        PayloadTypeRegistry.playS2C().register(ClaimPayload.ID, ClaimPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(ClaimPayload.ID, (payload, context) -> ClaimCache.accept(payload.data()));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(PmxxlClaimsClient::resetSession));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(PmxxlClaimsClient::resetSession));
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            String line = message.getString();
            MinecraftClient client = MinecraftClient.getInstance();
            if (!overlay && client.world != null) {
                ChatParser.accept(line, MinimapRenderer.worldId(client.world));
            }
            return !AutoScanner.suppress(line, overlay);
        });
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.pmxxlclaims.minimap",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                KeyBinding.Category.MISC
        ));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                MinimapRenderer.toggle();
                ClientConfig.minimapVisible(MinimapRenderer.isVisible());
                ClientConfig.save();
            }
            AutoScanner.tick(client);
            ChatParser.tick();
        });
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Identifier.of("pmxxlclaims", "minimap"), new MinimapRenderer());
        LOGGER.info("PmxxlClaims client initialized (server push + chat fallback)");
    }

    private static void resetSession() {
        ClaimCache.reset();
        AutoScanner.reset();
        ChunkColorCache.clear();
        ChatParser.flush();
        ClaimStore.saveIfDirty();
    }
}
