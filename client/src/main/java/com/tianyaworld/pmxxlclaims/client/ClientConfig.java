package com.tianyaworld.pmxxlclaims.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ClientConfig {

    private static boolean enabled = true;
    private static int claimAlpha = 0x66;
    private static float xaeroScale = 1.0f;

    private ClientConfig() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static int claimAlpha() {
        return claimAlpha;
    }

    public static float xaeroScale() {
        return xaeroScale;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("pmxxlclaims.json");
    }

    public static void load() {
        Path path = file();
        if (!Files.exists(path)) {
            save();
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("enabled")) {
                enabled = root.get("enabled").getAsBoolean();
            }
            if (root.has("claimAlpha")) {
                claimAlpha = Math.max(0, Math.min(255, root.get("claimAlpha").getAsInt()));
            }
            if (root.has("xaeroScale")) {
                xaeroScale = Math.max(0.05f, Math.min(16.0f, root.get("xaeroScale").getAsFloat()));
            }
        } catch (Exception ignored) {
        }
    }

    public static void save() {
        JsonObject root = new JsonObject();
        root.addProperty("enabled", enabled);
        root.addProperty("claimAlpha", claimAlpha);
        root.addProperty("xaeroScale", xaeroScale);
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}