package com.tianyaworld.pmxxlclaims.client.jm;

import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.display.Context;
import journeymap.api.v2.common.JourneyMapPlugin;
import journeymap.api.v2.common.event.ClientEventRegistry;

@JourneyMapPlugin(apiVersion = "2.0.0")
public final class ClaimsJourneyMapPlugin implements IClientPlugin {

    private static volatile IClientAPI api;

    public static IClientAPI api() {
        return api;
    }

    @Override
    public String getModId() {
        return "pmxxlclaims";
    }

    @Override
    public void initialize(IClientAPI jmClientApi) {
        api = jmClientApi;
        ClaimsOverlayManager.setJmMinimapActive(true);
        ClientEventRegistry.DISPLAY_UPDATE_EVENT.subscribe(getModId(), event -> {
            if (event.uiState != null && event.uiState.ui == Context.UI.Minimap) {
                ClaimsOverlayManager.setJmMinimapActive(event.uiState.active);
            }
        });
        ClaimsOverlayManager.requestRebuild();
    }
}
