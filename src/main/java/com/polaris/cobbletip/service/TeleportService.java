package com.polaris.cobbletip.service;

import com.polaris.cobbletip.util.Msg;
import com.polaris.cobbletip.util.TipConfig;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TeleportService {
    private final Plugin plugin;
    private TipConfig cfg;
    private Economy economy;
    private final Map<UUID, Long> cooldown = new ConcurrentHashMap<>();
    private final Map<String, Long> trackedLocations = new ConcurrentHashMap<>();

    public TeleportService(Plugin plugin, TipConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
        hookVault();
    }

    public void reload(TipConfig cfg) {
        this.cfg = cfg;
        hookVault();
    }

    private void hookVault() {
        economy = null;
        if (!cfg.tpEcoEnabled()) return;
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
        var rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
    }

    public void teleportToEntity(Player p, UUID entityUuid) {
        if (p == null) return;
        if (!cfg.tpEnabled()) {
            p.sendMessage(Msg.color(Msg.apply("{prefix}&c传送功能已关闭。", cfg)));
            return;
        }
        if (p.hasPermission("cobbletip.teleport.bypass")) {
            doTeleportToEntity(p, entityUuid);
            return;
        }

        if (!checkAndConsumeCost(p)) return;
        doTeleportToEntity(p, entityUuid);
    }

    // 兼容旧调用（CtpCommand 里历史遗留的 tp.teleport(...)）
    public void teleport(Player p, World world, double x, double y, double z) {
        teleportToLocation(p, world, x, y, z);
    }

    public void trackLocation(World world, int x, int y, int z) {
        if (world == null) return;
        long expireAt = System.currentTimeMillis() + cfg.protectDurationSeconds() * 1000L;
        trackedLocations.put(locKey(world, x, y, z), expireAt);

        if (trackedLocations.size() > 4096) {
            long now = System.currentTimeMillis();
            trackedLocations.entrySet().removeIf(e -> e.getValue() < now);
        }
    }

    public void teleportToLocation(Player p, World world, double x, double y, double z) {
        if (p == null || world == null) return;
        if (!cfg.tpEnabled()) {
            p.sendMessage(Msg.color(Msg.apply("{prefix}&c传送功能已关闭。", cfg)));
            return;
        }

        if (cfg.tpTrackedOnly() && !p.hasPermission("cobbletip.teleport.bypass")) {
            int bx = (int) Math.floor(x);
            int by = (int) Math.floor(y);
            int bz = (int) Math.floor(z);
            if (!isTrackedLocation(world, bx, by, bz)) {
                p.sendMessage(Msg.color(Msg.apply(cfg.msgNotTracked(), cfg)));
                return;
            }
        }

        if (!p.hasPermission("cobbletip.teleport.bypass")) {
            if (!checkAndConsumeCost(p)) return;
        }

        Location raw = new Location(world, x, y, z, p.getLocation().getYaw(), p.getLocation().getPitch());
        doTeleportToLocation(p, raw);
    }

    private boolean isTrackedLocation(World world, int x, int y, int z) {
        String key = locKey(world, x, y, z);
        Long expire = trackedLocations.get(key);
        if (expire == null) return false;
        long now = System.currentTimeMillis();
        if (expire < now) {
            trackedLocations.remove(key);
            return false;
        }
        return true;
    }

    private static String locKey(World world, int x, int y, int z) {
        return world.getName() + "|" + x + "|" + y + "|" + z;
    }

    private boolean checkAndConsumeCost(Player p) {
        long now = System.currentTimeMillis();
        long cdMs = cfg.tpCooldownSeconds() * 1000L;
        long last = cooldown.getOrDefault(p.getUniqueId(), 0L);
        long leftMs = (last + cdMs) - now;
        if (leftMs > 0) {
            long leftSec = (leftMs + 999) / 1000;
            p.sendMessage(Msg.color(Msg.apply(cfg.msgTeleportCooldown(), cfg, "seconds", String.valueOf(leftSec))));
            return false;
        }

        if (cfg.tpEcoEnabled() && economy != null && !p.hasPermission("cobbletip.teleport.free")) {
            double cost = cfg.tpEcoCost();
            if (!economy.has(p, cost)) {
                p.sendMessage(Msg.color(Msg.apply(cfg.msgTeleportNoMoney(), cfg, "cost", String.valueOf(cost))));
                return false;
            }
            economy.withdrawPlayer(p, cost);
        }

        cooldown.put(p.getUniqueId(), now);
        return true;
    }

    private void doTeleportToEntity(Player p, UUID entityUuid) {
        Entity e = Bukkit.getEntity(entityUuid);
        if (e == null) {
            p.sendMessage(Msg.color(Msg.apply(cfg.msgEntityNotFound(), cfg)));
            return;
        }
        Location loc = e.getLocation().clone().add(0, 1.0, 0);
        loc.setYaw(p.getLocation().getYaw());
        loc.setPitch(p.getLocation().getPitch());
        loc = toSafeLocation(loc);

        p.teleport(loc);
        p.sendMessage(Msg.color(Msg.apply(cfg.msgTeleportSuccess(), cfg)));
    }

    private void doTeleportToLocation(Player p, Location raw) {
        Location loc = toSafeLocation(raw);
        p.teleport(loc);
        p.sendMessage(Msg.color(Msg.apply(cfg.msgTeleportSuccess(), cfg)));
    }

    private static Location toSafeLocation(Location base) {
        World w = base.getWorld();
        if (w == null) return base;

        int bx = base.getBlockX();
        int bz = base.getBlockZ();

        int minY = w.getMinHeight() + 1;
        int maxY = w.getMaxHeight() - 2;
        int startY = Math.min(Math.max(base.getBlockY(), minY), maxY);

        int endY = Math.min(startY + 6, maxY);
        for (int y = startY; y <= endY; y++) {
            Block below = w.getBlockAt(bx, y - 1, bz);
            Block feet = w.getBlockAt(bx, y, bz);
            Block head = w.getBlockAt(bx, y + 1, bz);
            if (!below.getType().isSolid()) continue;
            if (!feet.isPassable()) continue;
            if (!head.isPassable()) continue;
            return new Location(w, bx + 0.5, y, bz + 0.5, base.getYaw(), base.getPitch());
        }

        Location top = w.getHighestBlockAt(bx, bz).getLocation().add(0.5, 1.0, 0.5);
        top.setYaw(base.getYaw());
        top.setPitch(base.getPitch());
        return top;
    }
}
