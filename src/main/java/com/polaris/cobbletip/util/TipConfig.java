package com.polaris.cobbletip.util;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class TipConfig {
    private final Plugin plugin;
    private final FileConfiguration c;

    public TipConfig(Plugin plugin) {
        this.plugin = plugin;
        this.c = plugin.getConfig();
    }

    public boolean debug() { return c.getBoolean("debug", false); }

    // announce
    public boolean announceEnabled() { return c.getBoolean("announce.enabled", true); }
    public Set<String> announceSources() {
        return c.getStringList("announce.sources").stream().map(String::toUpperCase).collect(Collectors.toSet());
    }
    public Set<String> announceOnlyLabels() {
        return c.getStringList("announce.onlyLabels").stream()
                .map(s -> s == null ? "" : s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }
    public boolean shouldAnnounceSource(Object spawnSourceEnumOrNull) {
        if (spawnSourceEnumOrNull == null) return false;
        return announceSources().contains(spawnSourceEnumOrNull.toString().toUpperCase());
    }
    public boolean shouldAnnounceLabels(Set<String> labelsOrNull) {
        Set<String> only = announceOnlyLabels();
        if (only.isEmpty()) return true;
        if (labelsOrNull == null || labelsOrNull.isEmpty()) return false;
        for (String l : labelsOrNull) {
            if (l == null) continue;
            if (only.contains(l.trim().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
    public boolean isForceSpecies(String species) { return matchSpeciesList("announce.forceSpecies", species); }
    public boolean isBlockedSpecies(String species) { return matchSpeciesList("announce.blockSpecies", species); }

    private boolean matchSpeciesList(String path, String species) {
        if (species == null) return false;
        List<String> list = c.getStringList(path);
        if (list == null || list.isEmpty()) return false;
        if (list.contains(species)) return true;

        // Back-compat / convenience: allow entries without namespace (mewtwo) to match cobblemon:mewtwo
        String shortName = species;
        int colon = shortName.indexOf(':');
        if (colon >= 0 && colon + 1 < shortName.length()) shortName = shortName.substring(colon + 1);
        for (String s : list) {
            if (s == null) continue;
            if (s.equals(shortName)) return true;
        }
        return false;
    }

    public List<String> msgAnnounceLines() { return c.getStringList("messages.announce"); }
    public String msgClickText() { return c.getString("messages.clickText", "&a[点我]"); }
    public String msgClickLine() { return c.getString("messages.clickLine", "{prefix}&a[点我]"); }
    public String msgClickHover() { return c.getString("messages.clickHover", ""); }

    // protection
    public boolean protectEnabled() { return c.getBoolean("protection.enabled", true); }
    public long protectDurationSeconds() { return c.getLong("protection.durationSeconds", 600); }
    public boolean denyAttack() { return c.getBoolean("protection.deny.attack", true); }
    public boolean denyInteract() { return c.getBoolean("protection.deny.interact", true); }
    public boolean denyFish() { return c.getBoolean("protection.deny.fish", true); }
    public long protectMsgCooldownMs() { return c.getLong("protection.messageCooldownMs", 1200); }

    public String msgProtectDeniedAttack() { return c.getString("messages.protectDeniedAttack", "{prefix}&c该宝可梦处于保护中，无法攻击。"); }
    public String msgProtectDeniedInteract() { return c.getString("messages.protectDeniedInteract", "{prefix}&c该宝可梦处于保护中，无法交互。"); }
    public String msgProtectDeniedFish() { return c.getString("messages.protectDeniedFish", "{prefix}&c该宝可梦处于保护中，无法钓起。"); }
    public String msgProtectExpired() { return c.getString("messages.protectExpired", "{prefix}&a{species} 的保护已解除。"); }

    // teleport
    public boolean tpEnabled() { return c.getBoolean("teleport.enabled", true); }
    public int tpCooldownSeconds() { return c.getInt("teleport.cooldownSeconds", 3); }
    public boolean tpRequirePerm() { return c.getBoolean("teleport.requirePermission", true); }
    public boolean tpTrackedOnly() { return c.getBoolean("teleport.trackedOnly", true); }
    public boolean tpEcoEnabled() { return c.getBoolean("teleport.economy.enabled", true); }
    public double tpEcoCost() { return c.getDouble("teleport.economy.cost", 100.0); }

    public String msgTeleportSuccess() { return c.getString("messages.teleportSuccess", "{prefix}&a已传送。"); }
    public String msgTeleportCooldown() { return c.getString("messages.teleportCooldown", "{prefix}&e请等待 &f{seconds}&es 后再传送。"); }
    public String msgTeleportNoMoney() { return c.getString("messages.teleportNoMoney", "{prefix}&c余额不足，需要 &f{cost}&c。"); }

    // party
    public boolean partyEnabled() { return c.getBoolean("partyView.enabled", true); }
    public boolean partyRequireSneak() { return c.getBoolean("partyView.requireSneak", true); }
    public int partySize() { return c.getInt("partyView.size", 54); }
    public String msgPartyViewTitle() { return c.getString("messages.partyViewTitle", "{prefix}&a{player} 的队伍"); }
    public String msgPartyViewFailed() { return c.getString("messages.partyViewFailed", "{prefix}&c无法获取该玩家的队伍。"); }
    public String msgPartyViewEmpty() { return c.getString("messages.partyViewEmpty", "{prefix}&e该玩家队伍为空或未加载。"); }

    // common messages
    public String msgPrefix() { return c.getString("messages.prefix", "&7[&aCobbleTip&7] "); }
    public String msgNoPermission() { return c.getString("messages.noPermission", "{prefix}&c你没有权限。"); }
    public String msgReloaded() { return c.getString("messages.reloaded", "{prefix}&a配置已重载。"); }
    public String msgEntityNotFound() { return c.getString("messages.entityNotFound", "{prefix}&c目标已消失。"); }
    public String msgNotTracked() { return c.getString("messages.notTracked", "{prefix}&c该目标已过期或未记录。"); }

    public Plugin plugin() { return plugin; }
}
