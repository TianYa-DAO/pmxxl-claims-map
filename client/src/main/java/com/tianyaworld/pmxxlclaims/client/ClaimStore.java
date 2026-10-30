package com.tianyaworld.pmxxlclaims.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ClaimStore {

    public record Box(int minX, int minZ, int maxX, int maxZ) {
    }

    public record KnownClaim(String server, String name, String world, String owner, List<Box> boxes, long updatedAt) {
    }

    private static final int MAX_CLAIMS = 2000;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, KnownClaim> CLAIMS = new LinkedHashMap<>();
    private static final AtomicBoolean SAVING = new AtomicBoolean();
    private static boolean dirty;
    private static int version;

    private ClaimStore() {
    }

    public static String currentServer() {
        MinecraftClient client = MinecraftClient.getInstance();
        ServerInfo info = client == null ? null : client.getCurrentServerEntry();
        return info == null || info.address == null ? "local" : info.address;
    }

    private static String key(String server, String world, String name) {
        return server + "|" + world + "|" + name;
    }

    public static synchronized void put(String name, String world, String owner, List<Box> boxes) {
        if (name == null || name.isEmpty() || boxes.isEmpty()) {
            return;
        }
        String server = currentServer();
        KnownClaim existing = CLAIMS.get(key(server, world, name));
        String resolvedOwner = owner != null && !owner.isEmpty() ? owner : (existing == null ? "" : existing.owner());
        CLAIMS.put(key(server, world, name), new KnownClaim(server, name, world, resolvedOwner, List.copyOf(boxes), System.currentTimeMillis()));
        if (CLAIMS.size() > MAX_CLAIMS) {
            evictOldest();
        }
        dirty = true;
        version++;
    }

    public static synchronized void setOwner(String name, String world, String owner) {
        String server = currentServer();
        String key = key(server, world, name);
        KnownClaim existing = CLAIMS.get(key);
        if (existing == null) {
            return;
        }
        CLAIMS.put(key, new KnownClaim(existing.server(), existing.name(), existing.world(), owner, existing.boxes(), System.currentTimeMillis()));
        dirty = true;
        version++;
    }

    public static synchronized List<KnownClaim> forWorld(String world) {
        String server = currentServer();
        List<KnownClaim> out = new ArrayList<>();
        for (KnownClaim claim : CLAIMS.values()) {
            if (claim.world().equals(world) && claim.server().equals(server)) {
                out.add(claim);
            }
        }
        return out;
    }

    public static synchronized Collection<KnownClaim> all() {
        return List.copyOf(CLAIMS.values());
    }

    public static synchronized int version() {
        return version;
    }

    public static synchronized void forgetWorld(String world) {
        String server = currentServer();
        boolean removed = CLAIMS.entrySet().removeIf(entry -> entry.getValue().server().equals(server) && entry.getValue().world().equals(world));
        if (removed) {
            dirty = true;
            version++;
        }
    }

    private static void evictOldest() {
        String oldestKey = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, KnownClaim> entry : CLAIMS.entrySet()) {
            long at = entry.getValue().updatedAt();
            if (at < oldest) {
                oldest = at;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            CLAIMS.remove(oldestKey);
        }
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("pmxxlclaims-claims.json");
    }

    public static synchronized void load() {
        Path path = file();
        if (!Files.exists(path)) {
            return;
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            JsonArray claims = root.getAsJsonArray("claims");
            if (claims == null) {
                return;
            }
            for (JsonElement element : claims) {
                JsonObject object = element.getAsJsonObject();
                String server = object.has("server") ? object.get("server").getAsString() : currentServer();
                String name = object.get("name").getAsString();
                String world = object.get("world").getAsString();
                String owner = object.has("owner") ? object.get("owner").getAsString() : "";
                JsonArray boxes = object.getAsJsonArray("boxes");
                List<Box> parsed = new ArrayList<>();
                for (JsonElement boxElement : boxes) {
                    JsonArray box = boxElement.getAsJsonArray();
                    parsed.add(new Box(box.get(0).getAsInt(), box.get(1).getAsInt(), box.get(2).getAsInt(), box.get(3).getAsInt()));
                }
                CLAIMS.put(key(server, world, name), new KnownClaim(server, name, world, owner, List.copyOf(parsed), 0L));
            }
            version++;
        } catch (Exception ignored) {
        }
    }

    public static void saveIfDirty() {
        Map<String, KnownClaim> snapshot;
        synchronized (ClaimStore.class) {
            if (!dirty || !SAVING.compareAndSet(false, true)) {
                return;
            }
            dirty = false;
            snapshot = new HashMap<>(CLAIMS);
        }
        List<KnownClaim> ordered = new ArrayList<>(snapshot.values());
        ordered.sort(Comparator.comparingLong(KnownClaim::updatedAt));
        CompletableFuture.runAsync(() -> write(ordered)).whenComplete((ignored, error) -> {
            SAVING.set(false);
            if (error != null) {
                synchronized (ClaimStore.class) {
                    dirty = true;
                }
            }
        });
    }

    private static void write(List<KnownClaim> ordered) {
        JsonObject root = new JsonObject();
        JsonArray claims = new JsonArray();
        for (KnownClaim claim : ordered) {
            JsonObject object = new JsonObject();
            object.addProperty("server", claim.server());
            object.addProperty("name", claim.name());
            object.addProperty("world", claim.world());
            object.addProperty("owner", claim.owner());
            JsonArray boxes = new JsonArray();
            for (Box box : claim.boxes()) {
                JsonArray array = new JsonArray();
                array.add(box.minX());
                array.add(box.minZ());
                array.add(box.maxX());
                array.add(box.maxZ());
                boxes.add(array);
            }
            object.add("boxes", boxes);
            claims.add(object);
        }
        root.add("claims", claims);
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
