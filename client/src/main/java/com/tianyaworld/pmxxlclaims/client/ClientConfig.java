package com.tianyaworld.pmxxlclaims.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ClientConfig {

    private static boolean autoScan = true;
    private static long intervalMs = 1500L;
    private static boolean showCount = true;
    private static boolean minimapVisible = true;
    private static int minimapSize = 128;
    private static int minimapMargin = 6;
    private static int claimAlpha = 0x66;

    private ClientConfig() {
    }

    public static boolean autoScan() {
        return autoScan;
    }

    public static long intervalMs() {
        return intervalMs;
    }

    public static boolean showCount() {
        return showCount;
    }

    public static boolean minimapVisible() {
        return minimapVisible;
    }

    public static void minimapVisible(boolean value) {
        minimapVisible = value;
    }

    public static int minimapSize() {
        return minimapSize;
    }

    public static int minimapMargin() {
        return minimapMargin;
    }

    public static int claimAlpha() {
        return claimAlpha;
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
            if (root.has("autoScan")) {
                autoScan = root.get("autoScan").getAsBoolean();
            }
            if (root.has("intervalMs")) {
                intervalMs = Math.max(500L, root.get("intervalMs").getAsLong());
            }
            if (root.has("showCount")) {
                showCount = root.get("showCount").getAsBoolean();
            }
            if (root.has("minimapVisible")) {
                minimapVisible = root.get("minimapVisible").getAsBoolean();
            }
            if (root.has("minimapSize")) {
                minimapSize = Math.max(64, Math.min(256, root.get("minimapSize").getAsInt()));
            }
            if (root.has("minimapMargin")) {
                minimapMargin = Math.max(0, Math.min(64, root.get("minimapMargin").getAsInt()));
            }
            if (root.has("claimAlpha")) {
                claimAlpha = Math.max(0, Math.min(255, root.get("claimAlpha").getAsInt()));
            }
        } catch (Exception ignored) {
        }
    }

    public static void save() {
        JsonObject root = new JsonObject();
        root.addProperty("autoScan", autoScan);
        root.addProperty("intervalMs", intervalMs);
        root.addProperty("showCount", showCount);
        root.addProperty("minimapVisible", minimapVisible);
        root.addProperty("minimapSize", minimapSize);
        root.addProperty("minimapMargin", minimapMargin);
        root.addProperty("claimAlpha", claimAlpha);
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }
}
