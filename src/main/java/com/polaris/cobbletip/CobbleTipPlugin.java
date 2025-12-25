package com.polaris.cobbletip;

import com.polaris.cobbletip.cmd.CobbleTipCommand;
import com.polaris.cobbletip.cmd.CtpCommand;
import com.polaris.cobbletip.listener.PartyViewListener;
import com.polaris.cobbletip.listener.PartyViewInventoryLockListener;
import com.polaris.cobbletip.listener.ProtectionListener;
import com.polaris.cobbletip.listener.SpawnAnnounceListener;
import com.polaris.cobbletip.service.ProtectionService;
import com.polaris.cobbletip.service.TeleportService;
import com.polaris.cobbletip.util.TipConfig;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class CobbleTipPlugin extends JavaPlugin {
    private TipConfig cfg;
    private ProtectionService protectionService;
    private TeleportService teleportService;
    private boolean registered;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadAll();

        // Rebuild protection caches after restart/reload (ensures battles are still blocked).
        try { protectionService.resyncLoadedEntities(); } catch (Throwable ignored) {}

        getLogger().info("CobbleTip enabled. debug=" + cfg.debug());
    }

    @Override
    public void onDisable() {
        getLogger().info("CobbleTip disabled.");
    }

    public void reloadAll() {
        reloadConfig();
        this.cfg = new TipConfig(this);

        if (this.protectionService == null) this.protectionService = new ProtectionService(this, cfg);
        else this.protectionService.reload(cfg);

        if (this.teleportService == null) this.teleportService = new TeleportService(this, cfg);
        else this.teleportService.reload(cfg);

        if (registered) {
            HandlerList.unregisterAll(this);
        }
        registerListenersAndCommands();
        registered = true;
    }

    public TipConfig getCfg() {
        return cfg;
    }

    private void registerListenersAndCommands() {
        Bukkit.getPluginManager().registerEvents(new SpawnAnnounceListener(this, cfg, teleportService, protectionService), this);
        Bukkit.getPluginManager().registerEvents(new ProtectionListener(this, cfg, protectionService), this);
        Bukkit.getPluginManager().registerEvents(new PartyViewListener(this, cfg), this);
        Bukkit.getPluginManager().registerEvents(new PartyViewInventoryLockListener(), this);

        if (getCommand("ctp") != null) {
            getCommand("ctp").setExecutor(new CtpCommand(this, cfg, teleportService, protectionService));
        }
        if (getCommand("cobbletip") != null) {
            CobbleTipCommand cmd = new CobbleTipCommand(this);
            getCommand("cobbletip").setExecutor(cmd);
            getCommand("cobbletip").setTabCompleter(cmd);
        }
    }
}
