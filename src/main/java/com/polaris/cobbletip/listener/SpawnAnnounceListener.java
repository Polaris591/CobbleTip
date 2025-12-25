package com.polaris.cobbletip.listener;

import com.polaris.cobblecore.bukkit.CobblePokemonSpawnEvent;
import com.polaris.cobbletip.CobbleTipPlugin;
import com.polaris.cobbletip.service.ProtectionService;
import com.polaris.cobbletip.service.TeleportService;
import com.polaris.cobbletip.util.CobblemonZhCn;
import com.polaris.cobbletip.util.Msg;
import com.polaris.cobbletip.util.TipConfig;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class SpawnAnnounceListener implements Listener {

    private final CobbleTipPlugin plugin;
    private final TipConfig cfg;
    private final TeleportService tp;
    private final ProtectionService protection;

    public SpawnAnnounceListener(CobbleTipPlugin plugin, TipConfig cfg, TeleportService tp, ProtectionService protection) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.tp = tp;
        this.protection = protection;
    }

    @EventHandler
    public void onSpawn(CobblePokemonSpawnEvent e) {
        if (!cfg.announceEnabled()) return;

        UUID pokemonUuid = e.getPokemonUuid();
        if (pokemonUuid == null) return;

        String speciesId = e.getSpeciesId();
        if (speciesId == null || speciesId.isBlank()) speciesId = "unknown";
        String species = prettySpeciesName(speciesId);

        World world = mapWorld(e.getWorldName());
        if (world == null) {
            if (cfg.debug()) plugin.getLogger().warning("[CobbleTip] Unknown world: " + e.getWorldName());
            return;
        }

        Location loc = new Location(world, e.getX(), e.getY(), e.getZ());

        if (cfg.isBlockedSpecies(speciesId)) return;

        Set<String> labels = e.getLabels();
        boolean force = cfg.isForceSpecies(speciesId);
        if (!force && !cfg.shouldAnnounceLabels(labels)) return;
        if (!force && !cfg.shouldAnnounceSource(e.getSpawnSource())) return;

        String worldKey = world.getName();
        String worldName = worldDisplayName(world);
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();

        Player nearbyPlayer = nearestPlayer(world, loc);
        String nearbyName = nearbyPlayer == null ? "无" : nearbyPlayer.getName();

        String spawnSource = e.getSpawnSource() == null ? "OTHER" : e.getSpawnSource().name();
        String spawnSourceZh = switch (spawnSource) {
            case "COMMAND" -> "命令";
            case "BAIT" -> "诱饵";
            case "BOBBER" -> "钓鱼";
            default -> "";
        };
        String sourcePart = spawnSourceZh.isBlank() ? "" : " &8(&b" + spawnSourceZh + "&8)";

        final HoverEvent hoverEvent;
        List<String> hoverLore = e.getPokemonLore();
        if (hoverLore != null && !hoverLore.isEmpty()) {
            String hover = String.join("\n", hoverLore);
            hoverEvent = new HoverEvent(HoverEvent.Action.SHOW_TEXT, TextComponent.fromLegacyText(Msg.color(hover)));
        } else {
            hoverEvent = null;
        }

        for (String line : cfg.msgAnnounceLines()) {
            String rendered = Msg.apply(line, cfg,
                    "species", species,
                    "speciesId", speciesId,
                    "world", worldName,
                    "x", String.valueOf(bx),
                    "y", String.valueOf(by),
                    "z", String.valueOf(bz),
                    "uuid", pokemonUuid.toString(),
                    "nearby", nearbyName,
                    "source", spawnSource,
                    "sourceZh", spawnSourceZh,
                    "sourcePart", sourcePart
            );
            String legacy = Msg.color(rendered);
            Bukkit.getConsoleSender().sendMessage(legacy);

            BaseComponent[] msg = TextComponent.fromLegacyText(legacy);
            if (hoverEvent != null) {
                applyHoverToSubstring(msg, species, hoverEvent);
            }
            Bukkit.getOnlinePlayers().forEach(p -> p.spigot().sendMessage(msg));
        }

        if (cfg.tpEnabled()) {
            tp.trackLocation(world, bx, by, bz);

            String cmd = "/ctp " + worldKey + " " + bx + " " + by + " " + bz;
            String clickLine = Msg.apply(cfg.msgClickLine(), cfg,
                    "species", species,
                    "speciesId", speciesId,
                    "world", worldName,
                    "x", String.valueOf(bx),
                    "y", String.valueOf(by),
                    "z", String.valueOf(bz),
                    "uuid", pokemonUuid.toString(),
                    "nearby", nearbyName,
                    "source", spawnSource,
                    "sourceZh", spawnSourceZh,
                    "sourcePart", sourcePart
            );

            BaseComponent[] msg = TextComponent.fromLegacyText(Msg.color(clickLine));
            ClickEvent click = new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd);

            HoverEvent hover = null;
            String hoverText = cfg.msgClickHover();
            if (hoverText != null && !hoverText.isBlank()) {
                String hoverRendered = Msg.apply(hoverText, cfg,
                        "species", species,
                        "speciesId", speciesId,
                        "world", worldName,
                        "x", String.valueOf(bx),
                        "y", String.valueOf(by),
                        "z", String.valueOf(bz),
                        "uuid", pokemonUuid.toString(),
                        "nearby", nearbyName,
                        "source", spawnSource,
                        "sourceZh", spawnSourceZh,
                        "sourcePart", sourcePart
                );
                hover = new HoverEvent(HoverEvent.Action.SHOW_TEXT, TextComponent.fromLegacyText(Msg.color(hoverRendered)));
            }

            applyClickToClickTextOnly(msg, cfg.msgClickText(), click, hover);
            Bukkit.getOnlinePlayers().forEach(p -> p.spigot().sendMessage(msg));
        }

        if (cfg.protectEnabled()) {
            UUID bukkitEntityUuid = e.getBukkitEntityUuid();
            scheduleProtect(world, loc, pokemonUuid, bukkitEntityUuid, species, worldName, bx, by, bz);
        }

        if (cfg.debug()) {
            plugin.getLogger().info("[CobbleTip] Announced: " + species + " @" + worldName + " " + bx + " " + by + " " + bz
                    + " source=" + spawnSource + " bridge=" + e.getBridgeSource() + " labels=" + labels + " uuid=" + pokemonUuid + " speciesId=" + speciesId
                    + " nearby=" + nearbyName);
        }
    }

    private World mapWorld(String mcWorldId) {
        if (mcWorldId == null) return null;

        return switch (mcWorldId) {
            case "minecraft:overworld" -> Bukkit.getWorld("world");
            case "minecraft:the_nether" -> Bukkit.getWorld("world_nether");
            case "minecraft:the_end" -> Bukkit.getWorld("world_the_end");
            default -> {
                World w = Bukkit.getWorld(mcWorldId);
                if (w == null && mcWorldId.startsWith("minecraft:")) {
                    w = Bukkit.getWorld(mcWorldId.substring("minecraft:".length()));
                }
                yield w;
            }
        };
    }

    private static Player nearestPlayer(World world, Location loc) {
        if (world == null || loc == null) return null;
        Player best = null;
        double bestD = Double.MAX_VALUE;
        for (Player p : world.getPlayers()) {
            double d = p.getLocation().distanceSquared(loc);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private static Entity resolveEntity(World world, UUID pokemonUuid, Location loc) {
        if (pokemonUuid == null || world == null || loc == null) return null;

        Entity direct = Bukkit.getEntity(pokemonUuid);
        if (direct != null) return direct;

        for (Entity e : world.getNearbyEntities(loc, 12, 12, 12)) {
            Object handle = tryGetHandle(e);
            if (handle == null) continue;
            Object hu = tryInvoke(handle, "getUuid");
            if (hu instanceof UUID id && id.equals(pokemonUuid)) return e;
            Object pokemon = tryInvoke(handle, "getPokemon");
            if (pokemon != null) {
                Object pid = tryInvoke(pokemon, "getUuid");
                if (pid instanceof UUID id && id.equals(pokemonUuid)) return e;
            }
        }
        return null;
    }

    private void scheduleProtect(World world, Location loc, UUID pokemonUuid, UUID bukkitEntityUuid, String species,
                                 String worldName, int bx, int by, int bz) {
        final String speciesFinal = species;
        final String worldNameFinal = worldName;
        final int bxFinal = bx;
        final int byFinal = by;
        final int bzFinal = bz;
        final long durationSeconds = cfg.protectDurationSeconds();

        Player nearestAtDiscover = nearestPlayer(world, loc);
        UUID owner = nearestAtDiscover == null ? null : nearestAtDiscover.getUniqueId();

        if (pokemonUuid != null) {
            protection.trackAndProtect(null, owner, durationSeconds, pokemonUuid);
        }

        final int[] left = {20};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            Entity entity = null;
            if (bukkitEntityUuid != null) entity = Bukkit.getEntity(bukkitEntityUuid);
            if (entity == null) entity = resolveEntity(world, pokemonUuid, loc);

            left[0]--;
            if (entity == null && left[0] > 0) return;
            task.cancel();
            if (entity == null) {
                if (cfg.debug()) {
                    plugin.getLogger().warning("[CobbleTip] Failed to resolve spawned pokemon entity for protection: "
                            + speciesFinal + " @" + worldNameFinal + " " + bxFinal + " " + byFinal + " " + bzFinal);
                }
                return;
            }

            protection.trackAndProtect(entity.getUniqueId(), owner, durationSeconds, pokemonUuid);
            try { entity.setGlowing(true); } catch (Throwable ignored) {}

            final Entity entityFinal = entity;
            long ticks = Math.max(1, durationSeconds * 20L);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    if (entityFinal.isValid()) entityFinal.setGlowing(false);
                } catch (Throwable ignored) {}
                protection.untrack(entityFinal);
                Bukkit.broadcastMessage(Msg.color(Msg.apply(cfg.msgProtectExpired(), cfg,
                        "species", speciesFinal,
                        "world", worldNameFinal,
                        "x", String.valueOf(bxFinal),
                        "y", String.valueOf(byFinal),
                        "z", String.valueOf(bzFinal)
                )));
            }, ticks);
        }, 1L, 5L);
    }

    private static Object tryGetHandle(Entity bukkitEntity) {
        if (bukkitEntity == null) return null;
        try {
            Method m = bukkitEntity.getClass().getMethod("getHandle");
            m.setAccessible(true);
            return m.invoke(bukkitEntity);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object tryInvoke(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String prettySpeciesName(String speciesId) {
        if (speciesId == null) return "unknown";
        String s = speciesId.trim();
        if (s.isBlank()) return "unknown";
        int colon = s.indexOf(':');
        if (colon >= 0 && colon + 1 < s.length()) s = s.substring(colon + 1);
        s = s.replace('_', ' ').replace('-', ' ').trim();
        if (s.isBlank()) return "unknown";
        String fallback = Character.toUpperCase(s.charAt(0)) + s.substring(1);
        return CobblemonZhCn.speciesNameFromSpeciesId(speciesId, fallback);
    }

    private static String worldDisplayName(World world) {
        if (world == null) return "unknown";
        try {
            Object mv = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
            if (mv != null) {
                Method m1 = mv.getClass().getMethod("getMVWorldManager");
                Object manager = m1.invoke(mv);
                if (manager != null) {
                    Method m2 = manager.getClass().getMethod("getMVWorld", World.class);
                    Object mvWorld = m2.invoke(manager, world);
                    if (mvWorld != null) {
                        Method m3 = mvWorld.getClass().getMethod("getAlias");
                        Object alias = m3.invoke(mvWorld);
                        if (alias instanceof String s && !s.isBlank()) return s;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return world.getName();
    }

    private static void applyHoverToSubstring(BaseComponent[] msg, String needle, HoverEvent hoverEvent) {
        if (msg == null || msg.length == 0) return;
        if (needle == null || needle.isBlank() || hoverEvent == null) return;

        for (BaseComponent c : msg) {
            if (c == null) continue;
            String plain;
            try { plain = c.toPlainText(); } catch (Throwable t) { plain = null; }
            if (plain == null || plain.isBlank()) continue;
            if (plain.contains(needle)) {
                c.setHoverEvent(hoverEvent);
            }
        }
    }

    private static void applyClickToClickTextOnly(BaseComponent[] msg, String clickTextCfg, ClickEvent click, HoverEvent hover) {
        if (msg == null || msg.length == 0 || click == null) return;
        String needle = stripColors(Msg.color(clickTextCfg == null ? "" : clickTextCfg));
        if (needle.isBlank()) needle = "点击传送";

        boolean matched = false;
        for (BaseComponent c : msg) {
            if (c == null) continue;
            String plain;
            try { plain = c.toPlainText(); } catch (Throwable t) { plain = null; }
            if (plain == null) continue;
            if (plain.contains(needle)) {
                c.setClickEvent(click);
                if (hover != null) c.setHoverEvent(hover);
                matched = true;
            }
        }

        if (!matched) {
            for (BaseComponent c : msg) {
                if (c == null) continue;
                c.setClickEvent(click);
                if (hover != null) c.setHoverEvent(hover);
            }
        }
    }

    private static String stripColors(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '\u00A7' || c == '&') && i + 1 < s.length()) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}
