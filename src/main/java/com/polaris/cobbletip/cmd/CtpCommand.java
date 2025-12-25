package com.polaris.cobbletip.cmd;

import com.polaris.cobbletip.service.ProtectionService;
import com.polaris.cobbletip.service.TeleportService;
import com.polaris.cobbletip.util.Msg;
import com.polaris.cobbletip.util.TipConfig;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * 极简安全版：只做坐标传送，不碰实体UUID，避免“点了没反应”。
 * 用法：/ctp <world> <x> <y> <z>
 */
public final class CtpCommand implements CommandExecutor {
    private final Object plugin; // 保持你原构造器签名
    private final TipConfig cfg;
    private final TeleportService tp;
    private final ProtectionService protection;

    public CtpCommand(Object plugin, TipConfig cfg, TeleportService tp, ProtectionService protection) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.tp = tp;
        this.protection = protection;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("只能玩家使用");
            return true;
        }

        // ✅ 先只做权限（其余费用/冷却先不做，保证链路100%通）
        if (cfg.tpRequirePerm() && !p.hasPermission("cobbletip.tp")) {
            p.sendMessage(Msg.color("&c你没有权限: cobbletip.tp"));
            return true;
        }

        if (args.length != 4) {
            p.sendMessage(Msg.color("&e用法: /ctp <world> <x> <y> <z>"));
            return true;
        }

        World w = Bukkit.getWorld(args[0]);
        if (w == null) {
            p.sendMessage(Msg.color("&c世界不存在: " + args[0]));
            return true;
        }

        double x, y, z;
        try {
            x = Double.parseDouble(args[1]);
            y = Double.parseDouble(args[2]);
            z = Double.parseDouble(args[3]);
        } catch (NumberFormatException ex) {
            p.sendMessage(Msg.color("&c坐标必须是数字"));
            return true;
        }

        // ✅ 传送动作必须主线程，TeleportService 内部如果已有封装就用它
        tp.teleport(p, w, x, y, z);

        return true;
    }
}
