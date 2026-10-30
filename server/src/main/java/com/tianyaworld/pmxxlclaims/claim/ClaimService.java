package com.tianyaworld.pmxxlclaims.claim;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.CuboidArea;
import com.bekvon.bukkit.residence.protection.ResidenceManager;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public final class ClaimService {

    public record Snapshot(String world, List<ClaimData.Claim> claims) {
    }

    private record Cached(Snapshot snapshot, long at, int generation) {
    }

    private static final long CACHE_TTL_MS = 5000L;
    private static final int CACHE_MAX = 1024;
    private static final long WARN_INTERVAL_MS = 60000L;
    private static final int[] PALETTE = {
            0x4FC3F7, 0x81C784, 0xFFB74D, 0xE57373,
            0xBA68C8, 0x4DB6AC, 0xF06292, 0x9575CD
    };
    private static final Map<String, List<ResidenceManager.ChunkRef>> REF_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Cached> SNAPSHOT_CACHE = new ConcurrentHashMap<>();
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final Map<String, Long> WARNED_AT = new ConcurrentHashMap<>();

    private final int radius;
    private final boolean sendOwner;
    private final Logger logger;

    public ClaimService(int radius, boolean sendOwner, Logger logger) {
        this.radius = Math.max(1, Math.min(32, radius));
        this.sendOwner = sendOwner;
        this.logger = logger;
    }

    public int radius() {
        return radius;
    }

    public static void invalidate() {
        GENERATION.incrementAndGet();
        SNAPSHOT_CACHE.clear();
    }

    public Snapshot collect(Player player) {
        String worldName = player.getWorld().getName();
        String worldKey = player.getWorld().getKey().toString();
        int chunkX = player.getLocation().getBlockX() >> 4;
        int chunkZ = player.getLocation().getBlockZ() >> 4;
        String key = worldKey + "|" + chunkX + "|" + chunkZ;
        int generation = GENERATION.get();
        long now = System.currentTimeMillis();
        Cached cached = SNAPSHOT_CACHE.get(key);
        if (cached != null && cached.generation() == generation && now - cached.at() < CACHE_TTL_MS) {
            return cached.snapshot();
        }
        Snapshot snapshot = build(worldName, worldKey, chunkX, chunkZ);
        if (SNAPSHOT_CACHE.size() > CACHE_MAX) {
            SNAPSHOT_CACHE.clear();
        }
        SNAPSHOT_CACHE.put(key, new Cached(snapshot, now, generation));
        return snapshot;
    }

    private Snapshot build(String worldName, String worldKey, int chunkX, int chunkZ) {
        ResidenceManager manager = Residence.getInstance().getResidenceManager();
        Collection<ClaimedResidence> found = manager.getResidences(worldName, refs(chunkX, chunkZ));
        Map<String, ClaimedResidence> unique = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (ClaimedResidence residence : found) {
            flatten(residence, unique, seen);
        }
        List<ClaimData.Claim> claims = new ArrayList<>(unique.size());
        for (Map.Entry<String, ClaimedResidence> entry : unique.entrySet()) {
            ClaimedResidence residence = entry.getValue();
            List<ClaimData.Area> areas = new ArrayList<>(2);
            Map<String, CuboidArea> areaMap = residence.getAreaMap();
            if (areaMap != null) {
                for (CuboidArea area : areaMap.values()) {
                    if (!worldName.equals(area.getWorldName()) || area.getLowVector() == null || area.getHighVector() == null) {
                        continue;
                    }
                    int x1 = area.getLowVector().getBlockX();
                    int z1 = area.getLowVector().getBlockZ();
                    int x2 = area.getHighVector().getBlockX();
                    int z2 = area.getHighVector().getBlockZ();
                    areas.add(new ClaimData.Area(Math.min(x1, x2), Math.min(z1, z2), Math.max(x1, x2), Math.max(z1, z2)));
                }
            }
            if (areas.isEmpty()) {
                continue;
            }
            String owner = residence.getOwner() == null ? "" : residence.getOwner();
            int flags = residence.getParent() == null ? 0 : ClaimData.FLAG_SUBZONE;
            claims.add(new ClaimData.Claim(entry.getKey(), sendOwner ? owner : "", colorOf(owner), flags, areas));
        }
        if (claims.isEmpty()) {
            long now = System.currentTimeMillis();
            Long last = WARNED_AT.get(worldName);
            if (last == null || now - last > WARN_INTERVAL_MS) {
                WARNED_AT.put(worldName, now);
                logger.fine("no residences within " + radius + " chunks of " + worldName);
            }
        }
        return new Snapshot(worldKey, claims);
    }

    private List<ResidenceManager.ChunkRef> refs(int chunkX, int chunkZ) {
        String refKey = chunkX + ":" + chunkZ + ":" + radius;
        List<ResidenceManager.ChunkRef> refs = REF_CACHE.get(refKey);
        if (refs != null) {
            return refs;
        }
        int side = radius * 2 + 1;
        List<ResidenceManager.ChunkRef> built = new ArrayList<>(side * side);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                built.add(new ResidenceManager.ChunkRef(chunkX + dx, chunkZ + dz));
            }
        }
        if (REF_CACHE.size() > 256) {
            REF_CACHE.clear();
        }
        REF_CACHE.put(refKey, built);
        return built;
    }

    private void flatten(ClaimedResidence residence, Map<String, ClaimedResidence> out, Set<String> seen) {
        String path = pathOf(residence);
        if (path == null || !seen.add(path)) {
            return;
        }
        out.putIfAbsent(path, residence);
        List<ClaimedResidence> subzones = residence.getSubzones();
        if (subzones != null) {
            for (ClaimedResidence subzone : subzones) {
                flatten(subzone, out, seen);
            }
        }
    }

    private String pathOf(ClaimedResidence residence) {
        String name = residence.getResidenceName();
        if (name == null || name.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder(name);
        ClaimedResidence parent = residence.getParent();
        int guard = 0;
        while (parent != null && guard++ < 16) {
            String parentName = parent.getResidenceName();
            if (parentName == null || parentName.isEmpty()) {
                break;
            }
            builder.insert(0, parentName + "/");
            parent = parent.getParent();
        }
        return builder.toString();
    }

    private int colorOf(String owner) {
        return PALETTE[Math.floorMod(owner.hashCode(), PALETTE.length)];
    }
}
