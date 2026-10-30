package com.tianyaworld.pmxxlclaims.publish;

import com.tianyaworld.pmxxlclaims.PmxxlClaims;
import com.tianyaworld.pmxxlclaims.claim.ClaimData;
import com.tianyaworld.pmxxlclaims.claim.ClaimService;
import com.tianyaworld.pmxxlclaims.net.ClaimCodec;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ClaimPublisher {

    private static final long HEARTBEAT_MS = 10000L;
    private static final long TRIM_WARN_MS = 60000L;
    private static final int MAX_BODY = Messenger.MAX_MESSAGE_SIZE - 64;

    private final PmxxlClaims plugin;
    private final Map<UUID, Integer> lastHash = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSignature = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSentAt = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> revisions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTrimWarn = new ConcurrentHashMap<>();
    private ClaimService service;
    private BukkitTask task;
    private boolean refreshScheduled;

    public ClaimPublisher(PmxxlClaims plugin, ClaimService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public void setService(ClaimService service) {
        this.service = service;
        ClaimService.invalidate();
        this.lastHash.clear();
        this.lastSignature.clear();
        this.lastSentAt.clear();
        markDirty();
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 60L, 60L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        lastHash.clear();
        lastSignature.clear();
        lastSentAt.clear();
        revisions.clear();
        lastTrimWarn.clear();
    }

    public void markDirty() {
        if (!plugin.isEnabled()) {
            return;
        }
        ClaimService.invalidate();
        if (refreshScheduled) {
            return;
        }
        refreshScheduled = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            refreshScheduled = false;
            if (plugin.isEnabled()) {
                refreshAll();
            }
        });
    }

    public void refresh(Player player) {
        if (player == null || !player.isOnline() || !plugin.isEnabled() || !accepts(player)) {
            return;
        }
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        boolean heartbeat = now - lastSentAt.getOrDefault(id, 0L) >= HEARTBEAT_MS;
        ClaimService.Snapshot snapshot;
        try {
            snapshot = service.collect(player);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("claim collect failed for " + player.getName() + ": " + exception.getMessage());
            return;
        }
        long signature = signatureOf(snapshot);
        Long previous = lastSignature.get(id);
        if (!heartbeat && previous != null && previous == signature) {
            return;
        }
        String playerName = player.getName();
        int revision = revisions.merge(id, 1, Integer::sum);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!plugin.isEnabled()) {
                return;
            }
            byte[] data;
            try {
                data = encodeSafely(snapshot, id, playerName);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("claim encode failed for " + playerName + ": " + exception.getMessage());
                return;
            }
            if (data == null) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> deliver(player, id, data, revision, heartbeat, signature));
        });
    }

    public void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refresh(player);
        }
    }

    public void forget(UUID uuid) {
        lastHash.remove(uuid);
        lastSignature.remove(uuid);
        lastSentAt.remove(uuid);
        revisions.remove(uuid);
        lastTrimWarn.remove(uuid);
    }

    private void deliver(Player player, UUID id, byte[] data, int revision, boolean heartbeat, long signature) {
        if (!plugin.isEnabled() || !player.isOnline() || revisions.getOrDefault(id, -1) != revision) {
            return;
        }
        int hash = Arrays.hashCode(data);
        Integer previous = lastHash.get(id);
        if (!heartbeat && previous != null && previous == hash) {
            lastSignature.put(id, signature);
            return;
        }
        try {
            player.sendPluginMessage(plugin, PmxxlClaims.CHANNEL, data);
            lastHash.put(id, hash);
            lastSignature.put(id, signature);
            lastSentAt.put(id, System.currentTimeMillis());
        } catch (RuntimeException exception) {
            lastHash.remove(id);
            lastSignature.remove(id);
            plugin.getLogger().warning("failed to send claims to " + player.getName() + ": " + exception.getMessage());
        }
    }

    private boolean accepts(Player player) {
        Set<String> channels = player.getListeningPluginChannels();
        if (channels.contains(PmxxlClaims.CHANNEL)) {
            return true;
        }
        return plugin.assumeClientUnregistered() && channels.isEmpty();
    }

    private long signatureOf(ClaimService.Snapshot snapshot) {
        long hash = 1125899906842597L;
        hash = hash * 31 + snapshot.world().hashCode();
        hash = hash * 31 + snapshot.claims().size();
        for (ClaimData.Claim claim : snapshot.claims()) {
            hash = hash * 31 + claim.name().hashCode();
            hash = hash * 31 + claim.owner().hashCode();
            hash = hash * 31 + claim.color();
            hash = hash * 31 + claim.flags();
            for (ClaimData.Area area : claim.areas()) {
                hash = hash * 31 + area.minX();
                hash = hash * 31 + area.minZ();
                hash = hash * 31 + area.maxX();
                hash = hash * 31 + area.maxZ();
            }
        }
        return hash;
    }

    private byte[] encodeSafely(ClaimService.Snapshot snapshot, UUID id, String playerName) {
        List<ClaimData.Claim> claims = snapshot.claims();
        byte[] data = ClaimCodec.encode(snapshot.world(), claims);
        if (data.length <= MAX_BODY) {
            return data;
        }
        List<ClaimData.Claim> trimmed = new ArrayList<>(claims);
        for (int attempt = 0; attempt < 4 && !trimmed.isEmpty(); attempt++) {
            trimmed = new ArrayList<>(trimmed.subList(0, trimmed.size() / 2));
            data = ClaimCodec.encode(snapshot.world(), trimmed);
            if (data.length <= MAX_BODY) {
                warnTrim(id, playerName, trimmed.size());
                return data;
            }
        }
        warnTrim(id, playerName, 0);
        return null;
    }

    private void warnTrim(UUID id, String playerName, int kept) {
        long now = System.currentTimeMillis();
        Long last = lastTrimWarn.get(id);
        if (last != null && now - last < TRIM_WARN_MS) {
            return;
        }
        lastTrimWarn.put(id, now);
        plugin.getLogger().warning("claim payload too large for " + playerName + ", kept " + kept + " claims");
    }
}
