package com.polaris.cobbletip.cmd;

import com.polaris.cobbletip.CobbleTipPlugin;
import com.polaris.cobbletip.util.Msg;
import com.polaris.cobbletip.util.TipConfig;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

public final class CobbleTipCommand implements CommandExecutor, TabCompleter {
    private final CobbleTipPlugin plugin;

    public CobbleTipCommand(CobbleTipPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        TipConfig cfg = plugin.getCfg();
        if (!sender.hasPermission("cobbletip.admin")) {
            sender.sendMessage(Msg.color(Msg.apply(cfg.msgNoPermission(), cfg)));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadAll();
            TipConfig newCfg = plugin.getCfg();
            sender.sendMessage(Msg.color(Msg.apply(newCfg.msgReloaded(), newCfg)));
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("cobbletip.admin")) return List.of();
        if (args.length == 1) {
            String p = args[0].toLowerCase();
            if ("reload".startsWith(p)) return List.of("reload");
        }
        return List.of();
    }
}
