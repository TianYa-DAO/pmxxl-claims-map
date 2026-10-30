package com.tianyaworld.pmxxlclaims;

import com.tianyaworld.pmxxlclaims.claim.ClaimService;
import com.tianyaworld.pmxxlclaims.command.ClaimsMapCommand;
import com.tianyaworld.pmxxlclaims.listener.ClaimChangeListener;
import com.tianyaworld.pmxxlclaims.listener.PlayerListener;
import com.tianyaworld.pmxxlclaims.publish.ClaimPublisher;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class PmxxlClaims extends JavaPlugin {

    public static final String CHANNEL = "pmxxl:claims";
    private static final int MAX_RADIUS = 32;

    private ClaimService service;
    private ClaimPublisher publisher;
    private boolean assumeClientUnregistered;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (Bukkit.getPluginManager().getPlugin("Residence") == null) {
            getLogger().severe("Residence not found, disabling");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        service = createService();
        publisher = new ClaimPublisher(this, service);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this, publisher), this);
        Bukkit.getPluginManager().registerEvents(new ClaimChangeListener(publisher), this);
        PluginCommand command = getCommand("claimsmap");
        if (command != null) {
            ClaimsMapCommand executor = new ClaimsMapCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        publisher.start();
        getLogger().info("PmxxlClaims enabled, Residence hook ok, radius=" + service.radius());
    }

    @Override
    public void onDisable() {
        if (publisher != null) {
            publisher.stop();
        }
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(this);
    }

    public ClaimService service() {
        return service;
    }

    public boolean assumeClientUnregistered() {
        return assumeClientUnregistered;
    }

    public void reloadPlugin() {
        reloadConfig();
        service = createService();
        publisher.setService(service);
    }

    private ClaimService createService() {
        int configured = getConfig().getInt("radius-chunks", 16);
        int radius = Math.max(1, Math.min(MAX_RADIUS, configured));
        if (radius != configured) {
            getLogger().warning("radius-chunks=" + configured + " out of range, clamped to " + radius + " (max " + MAX_RADIUS + ")");
        }
        assumeClientUnregistered = getConfig().getBoolean("assume-client-unregistered", false);
        return new ClaimService(radius, getConfig().getBoolean("send-owner", false), getLogger());
    }
}
