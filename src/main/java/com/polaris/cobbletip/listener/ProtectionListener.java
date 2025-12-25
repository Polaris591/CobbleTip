package com.polaris.cobbletip.listener;

import com.polaris.cobbletip.service.ProtectionService;
import com.polaris.cobbletip.util.Msg;
import com.polaris.cobbletip.util.TipConfig;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.projectiles.ProjectileSource;

public final class ProtectionListener implements Listener {
    private final TipConfig cfg;
    private final ProtectionService protection;

    public ProtectionListener(Object pluginIgnored, TipConfig cfg, ProtectionService protection) {
        this.cfg = cfg;
        this.protection = protection;
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!cfg.protectEnabled() || !cfg.denyAttack()) return;
        if (!(e.getDamager() instanceof Player p)) return;

        Entity victim = e.getEntity();
        var res = protection.checkDenied(victim, p);
        if (!res.denied()) return;

        e.setCancelled(true);
        protection.notifyOnce(p, Msg.color(Msg.apply(cfg.msgProtectDeniedAttack(), cfg)));
    }

    @EventHandler
    public void onAnyDamage(EntityDamageEvent e) {
        if (!cfg.protectEnabled()) return;
        Entity victim = e.getEntity();
        if (victim == null) return;
        // 环境伤害也要挡住
        if (!protection.isTracked(victim.getUniqueId())) return;
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (!cfg.protectEnabled() || !cfg.denyInteract()) return;

        Player p = e.getPlayer();
        Entity target = e.getRightClicked();
        var res = protection.checkDenied(target, p);
        if (!res.denied()) return;

        e.setCancelled(true);
        protection.notifyOnce(p, Msg.color(Msg.apply(cfg.msgProtectDeniedInteract(), cfg)));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteractAt(PlayerInteractAtEntityEvent e) {
        if (!cfg.protectEnabled() || !cfg.denyInteract()) return;

        Player p = e.getPlayer();
        Entity target = e.getRightClicked();
        var res = protection.checkDenied(target, p);
        if (!res.denied()) return;

        e.setCancelled(true);
        protection.notifyOnce(p, Msg.color(Msg.apply(cfg.msgProtectDeniedInteract(), cfg)));
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        if (!cfg.protectEnabled()) return;
        if (e.getHitEntity() == null) return;
        Entity target = e.getHitEntity();
        if (!protection.isTracked(target.getUniqueId())) return;

        ProjectileSource src = e.getEntity().getShooter();
        if (src instanceof Player p) {
            var res = protection.checkDenied(target, p);
            if (!res.denied()) return;
            e.setCancelled(true);
            protection.notifyOnce(p, Msg.color(Msg.apply(cfg.msgProtectDeniedInteract(), cfg)));
        } else {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onFish(PlayerFishEvent e) {
        if (!cfg.protectEnabled() || !cfg.denyFish()) return;
        if (e.getCaught() == null) return;

        Player p = e.getPlayer();
        Entity caught = e.getCaught();
        var res = protection.checkDenied(caught, p);
        if (!res.denied()) return;

        e.setCancelled(true);
        protection.notifyOnce(p, Msg.color(Msg.apply(cfg.msgProtectDeniedFish(), cfg)));
    }
}
