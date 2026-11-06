package com.tianyaworld.pmxxlclaims.client;

import com.tianyaworld.pmxxlclaims.client.xaero.XaeroClaimsBridge;
import com.tianyaworld.pmxxlclaims.client.xaero.XaeroWorldMapClaimsBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PmxxlClaimsClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("pmxxlclaims");

    @Override
    public void onInitializeClient() {
        ClientConfig.load();
        PayloadTypeRegistry.playS2C().register(ClaimPayload.ID, ClaimPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(ClaimPayload.ID, (payload, context) -> ClaimCache.accept(payload.data()));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(PmxxlClaimsClient::resetSession));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(PmxxlClaimsClient::resetSession));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("xaerominimap")) {
                    XaeroClaimsBridge.tick(client);
                }
                if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("xaeroworldmap")) {
                    XaeroWorldMapClaimsBridge.tick(client);
                }
            } catch (Throwable throwable) {
                LOGGER.warn("claims backend tick failed", throwable);
            }
        });
        LOGGER.info("PmxxlClaims client initialized (xaero overlay only)");
    }

    private static void resetSession() {
        ClaimCache.reset();
    }
}