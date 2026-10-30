package com.tianyaworld.pmxxlclaims.command;

import com.tianyaworld.pmxxlclaims.PmxxlClaims;
import com.tianyaworld.pmxxlclaims.claim.ClaimData;
import com.tianyaworld.pmxxlclaims.claim.ClaimService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ClaimsMapCommand implements CommandExecutor, TabCompleter {

    private final PmxxlClaims plugin;

    public ClaimsMapCommand(PmxxlClaims plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("pmxxlclaims.admin")) {
            sender.sendMessage("没有权限");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage("/claimsmap reload");
            sender.sendMessage("/claimsmap debug [玩家]");
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadPlugin();
            sender.sendMessage("已重载，半径=" + plugin.service().radius() + " 区块");
            return true;
        }
        if (args[0].equalsIgnoreCase("debug")) {
            Player target = null;
            if (args.length > 1) {
                target = Bukkit.getPlayerExact(args[1]);
            } else if (sender instanceof Player) {
                target = (Player) sender;
            }
            if (target == null) {
                sender.sendMessage("找不到该玩家");
                return true;
            }
            long started = System.nanoTime();
            ClaimService.Snapshot snapshot = plugin.service().collect(target);
            long elapsed = (System.nanoTime() - started) / 1000L;
            sender.sendMessage("玩家=" + target.getName() + " 世界=" + snapshot.world());
            sender.sendMessage("领地数=" + snapshot.claims().size() + " 查询耗时=" + elapsed + "us");
            for (ClaimData.Claim claim : snapshot.claims()) {
                sender.sendMessage(" - " + claim.name() + " 区域=" + claim.areas().size() + " 标记=" + claim.flags());
            }
            return true;
        }
        sender.sendMessage("未知子命令");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("reload", "debug");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return names;
        }
        return new ArrayList<>();
    }
}
