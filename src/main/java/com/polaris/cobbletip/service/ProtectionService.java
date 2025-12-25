package com.polaris.cobbletip.service;

import com.polaris.cobblecore.CobbleCore;
import com.polaris.cobblecore.api.CobbleCoreApi;
import com.polaris.cobbletip.util.TipConfig;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ProtectionService {
    private final Plugin plugin;
    private TipConfig cfg;

    private final NamespacedKey kTracked;
    private final NamespacedKey kOwner;
    private final NamespacedKey kExpire;
    private final NamespacedKey kPokemonUuid;

    private CobbleCoreApi coreApi;
    private final Map<UUID, Long> msgCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Long> tracked = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> entityToPokemon = new ConcurrentHashMap<>();
    private final Map<UUID, Long> protectedPokemon = new ConcurrentHashMap<>();

    // Persist protection by Pokemon UUID so CobbleCore can keep blocking battles after restart even if entity isn't loaded.
    private final File persistentFile;
    private final Object fileLock = new Object();
    private volatile boolean saveQueued = false;
    private volatile YamlConfiguration persistentYaml = null;

    public ProtectionService(Plugin plugin, TipConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.kTracked = new NamespacedKey(plugin, "tracked");
        this.kOwner = new NamespacedKey(plugin, "owner");
        this.kExpire = new NamespacedKey(plugin, "expire");
        this.kPokemonUuid = new NamespacedKey(plugin, "pokemon_uuid");
        this.persistentFile = new File(plugin.getDataFolder(), "protection-cache.yml");
    }

    public void reload(TipConfig cfg) {
        this.cfg = cfg;
    }

    private CobbleCoreApi resolveCoreApi() {
        if (coreApi != null) return coreApi;
        var core = Bukkit.getPluginManager().getPlugin("CobbleCore");
        if (core instanceof CobbleCore cobbleCore) {
            coreApi = cobbleCore.api();
        }
        return coreApi;
    }

    private void clearCoreProtection(UUID pokemonUuid) {
        CobbleCoreApi api = resolveCoreApi();
        if (api == null || pokemonUuid == null) return;
        try { api.clearProtectedPokemon(pokemonUuid); } catch (Throwable ignored) {}
    }

    private void syncCoreProtection(UUID pokemonUuid, long expireAtMs, UUID ownerUuid) {
        CobbleCoreApi api = resolveCoreApi();
        if (api == null || pokemonUuid == null) return;

        // Prefer new API: markProtectedPokemon(UUID,long,UUID)
        try {
            Method m = api.getClass().getMethod("markProtectedPokemon", UUID.class, long.class, UUID.class);
            m.setAccessible(true);
            m.invoke(api, pokemonUuid, expireAtMs, ownerUuid);
            return;
        } catch (Throwable ignored) {}

        // Fallback to old API: markProtectedPokemon(UUID,long)
        try { api.markProtectedPokemon(pokemonUuid, expireAtMs); } catch (Throwable ignored) {}
    }

    private void removeTracking(UUID entityUuid, Entity entity) {
        tracked.remove(entityUuid);
        UUID pokemonUuid = entityToPokemon.remove(entityUuid);
        if (pokemonUuid != null) {
            protectedPokemon.remove(pokemonUuid);
            clearCoreProtection(pokemonUuid);
            removePersistentProtection(pokemonUuid);
        }
        setUnbattleable(entity, false);
        clearMarks(entity);
    }

    public void trackAndProtect(UUID entityUuid, UUID ownerUuid, long durationSeconds) {
        trackAndProtect(entityUuid, ownerUuid, durationSeconds, null);
    }

    public void trackAndProtect(UUID entityUuid, UUID ownerUuid, long durationSeconds, UUID pokemonUuid) {
        if (entityUuid == null && pokemonUuid == null) return;
        long now = System.currentTimeMillis();
        cleanupExpired(now);
        long expireAt = now + durationSeconds * 1000L;
        if (entityUuid != null) {
            tracked.put(entityUuid, expireAt);
            if (pokemonUuid != null) entityToPokemon.put(entityUuid, pokemonUuid);
        }
        if (pokemonUuid != null) {
            protectedPokemon.put(pokemonUuid, expireAt);
        }

        if (pokemonUuid != null) {
            syncCoreProtection(pokemonUuid, expireAt, ownerUuid);
            upsertPersistentProtection(pokemonUuid, expireAt, ownerUuid);
        }

        if (entityUuid == null) return;
        Entity e = Bukkit.getEntity(entityUuid);
        if (e == null) return;

        // Only hard-block battles for everyone if we couldn't determine an owner.
        setUnbattleable(e, ownerUuid == null);

        var pdc = e.getPersistentDataContainer();
        pdc.set(kTracked, PersistentDataType.BYTE, (byte) 1);
        if (ownerUuid != null) pdc.set(kOwner, PersistentDataType.STRING, ownerUuid.toString());
        pdc.set(kExpire, PersistentDataType.LONG, expireAt);
        if (pokemonUuid != null) pdc.set(kPokemonUuid, PersistentDataType.STRING, pokemonUuid.toString());
    }

    public boolean isTracked(UUID entityUuid) {
        if (entityUuid == null) return false;
        cleanupExpired(System.currentTimeMillis());
        long now = System.currentTimeMillis();

        Long cachedExpire = tracked.get(entityUuid);
        if (cachedExpire != null) {
            if (cachedExpire < now) {
                Entity e = Bukkit.getEntity(entityUuid);
                removeTracking(entityUuid, e);
                return false;
            }
            return true;
        }

        Entity e = Bukkit.getEntity(entityUuid);
        if (e == null) return false;
        var pdc = e.getPersistentDataContainer();
        Byte t = pdc.get(kTracked, PersistentDataType.BYTE);
        if (t == null || t != (byte) 1) return false;

        Long expire = pdc.get(kExpire, PersistentDataType.LONG);
        if (expire != null && expire < now) {
            removeTracking(entityUuid, e);
            return false;
        }
        if (expire != null) tracked.put(entityUuid, expire);
        return true;
    }

    public record DenyResult(boolean denied) {}

    public DenyResult checkDenied(Entity entity, Player actor) {
        if (entity == null) return new DenyResult(false);
        if (!cfg.protectEnabled()) return new DenyResult(false);
        if (actor.hasPermission("cobbletip.protect.bypass") || actor.hasPermission("cobbletip.admin")) return new DenyResult(false);
        cleanupExpired(System.currentTimeMillis());

        UUID entityId = entity.getUniqueId();
        var pdc = entity.getPersistentDataContainer();
        Byte t = pdc.get(kTracked, PersistentDataType.BYTE);
        if (t == null || t != (byte) 1) return new DenyResult(false);

        Long expire = pdc.get(kExpire, PersistentDataType.LONG);
        if (expire != null && expire < System.currentTimeMillis()) {
            removeTracking(entityId, entity);
            return new DenyResult(false);
        } else if (expire != null) {
            tracked.put(entityId, expire);
        }

        String ownerStr = pdc.get(kOwner, PersistentDataType.STRING);
        if (ownerStr == null) {
            // 公共保护：所有人都禁止（最简单规则）
            return new DenyResult(true);
        }

        try {
            UUID owner = UUID.fromString(ownerStr);
            if (actor.getUniqueId().equals(owner)) return new DenyResult(false);
            return new DenyResult(true);
        } catch (Exception ignored) {
            return new DenyResult(true);
        }
    }

    public void notifyOnce(Player p, String msg) {
        long now = System.currentTimeMillis();
        long last = msgCooldown.getOrDefault(p.getUniqueId(), 0L);
        if (now - last < cfg.protectMsgCooldownMs()) return;
        msgCooldown.put(p.getUniqueId(), now);
        p.sendMessage(msg);
    }

    public void untrack(Entity entity) {
        if (entity == null) return;
        removeTracking(entity.getUniqueId(), entity);
    }

    private void cleanupExpired(long now) {
        var expiredEntities = new java.util.ArrayList<UUID>();
        tracked.forEach((id, expire) -> {
            if (expire != null && expire < now) expiredEntities.add(id);
        });
        for (UUID id : expiredEntities) {
            removeTracking(id, Bukkit.getEntity(id));
        }

        var expiredPokemon = new java.util.ArrayList<UUID>();
        protectedPokemon.forEach((id, expire) -> {
            if (expire != null && expire < now) expiredPokemon.add(id);
        });
        for (UUID pid : expiredPokemon) {
            protectedPokemon.remove(pid);
            clearCoreProtection(pid);
            removePersistentProtection(pid);
            entityToPokemon.entrySet().removeIf(e -> pid.equals(e.getValue()));
        }
    }

    private void clearMarks(Entity entity) {
        if (entity == null) return;
        var pdc = entity.getPersistentDataContainer();
        pdc.remove(kTracked);
        pdc.remove(kOwner);
        pdc.remove(kExpire);
        pdc.remove(kPokemonUuid);
    }

    /**
     * After server restart or plugin reload, rebuild in-memory caches and re-sync CobbleCore protection table
     * from persistent entity marks.
     */
    public void resyncLoadedEntities() {
        if (!cfg.protectEnabled()) return;

        // 1) Restore persisted protection table (Pokemon UUID -> expire/owner) so Core can block battles even if entity isn't loaded.
        resyncPersistentProtectionToCore();

        long now = System.currentTimeMillis();

        for (var world : Bukkit.getWorlds()) {
            try {
                for (Entity e : world.getEntities()) {
                    if (e == null) continue;
                    var pdc = e.getPersistentDataContainer();
                    Byte t = pdc.get(kTracked, PersistentDataType.BYTE);
                    if (t == null || t != (byte) 1) continue;

                    Long expire = pdc.get(kExpire, PersistentDataType.LONG);
                    if (expire == null || expire < now) {
                        removeTracking(e.getUniqueId(), e);
                        continue;
                    }

                    UUID owner = null;
                    String ownerStr = pdc.get(kOwner, PersistentDataType.STRING);
                    if (ownerStr != null) {
                        try { owner = UUID.fromString(ownerStr); } catch (Exception ignored) {}
                    }

                    UUID pokemonUuid = null;
                    String pidStr = pdc.get(kPokemonUuid, PersistentDataType.STRING);
                    if (pidStr != null) {
                        try { pokemonUuid = UUID.fromString(pidStr); } catch (Exception ignored) {}
                    }
                    if (pokemonUuid == null) pokemonUuid = tryReadPokemonUuidFromEntity(e);

                    tracked.put(e.getUniqueId(), expire);
                    if (pokemonUuid != null) {
                        entityToPokemon.put(e.getUniqueId(), pokemonUuid);
                        protectedPokemon.put(pokemonUuid, expire);
                        syncCoreProtection(pokemonUuid, expire, owner);
                    }

                    setUnbattleable(e, owner == null);
                }
            } catch (Throwable ignored) {}
        }
    }

    private void resyncPersistentProtectionToCore() {
        long now = System.currentTimeMillis();
        var cfg = yaml();
        if (cfg == null) return;

        var section = cfg.getConfigurationSection("records");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            UUID pid;
            try { pid = UUID.fromString(key); } catch (Exception ignored) { continue; }

            long expireAt = section.getLong(key + ".expireAtMs", 0L);
            if (expireAt <= 0L || expireAt < now) {
                removePersistentProtectionSync(pid);
                continue;
            }

            UUID owner = null;
            String ownerStr = section.getString(key + ".owner", null);
            if (ownerStr != null && !ownerStr.isBlank()) {
                try { owner = UUID.fromString(ownerStr); } catch (Exception ignored) {}
            }

            protectedPokemon.put(pid, expireAt);
            syncCoreProtection(pid, expireAt, owner);
        }

        queueSave();
    }

    private void upsertPersistentProtection(UUID pokemonUuid, long expireAtMs, UUID ownerUuid) {
        if (pokemonUuid == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            synchronized (fileLock) {
                var yml = yaml();
                if (yml == null) return;
                String k = "records." + pokemonUuid;
                yml.set(k + ".expireAtMs", expireAtMs);
                yml.set(k + ".owner", ownerUuid == null ? "" : ownerUuid.toString());
                queueSave();
            }
        });
    }

    private void removePersistentProtection(UUID pokemonUuid) {
        if (pokemonUuid == null) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            synchronized (fileLock) {
                removePersistentProtectionSync(pokemonUuid);
                queueSave();
            }
        });
    }

    private void removePersistentProtectionSync(UUID pokemonUuid) {
        if (pokemonUuid == null) return;
        var yml = yaml();
        if (yml == null) return;
        yml.set("records." + pokemonUuid, null);
    }

    private YamlConfiguration yaml() {
        YamlConfiguration y = persistentYaml;
        if (y != null) return y;
        synchronized (fileLock) {
            if (persistentYaml != null) return persistentYaml;
            persistentYaml = loadYamlQuietly();
            return persistentYaml;
        }
    }

    private YamlConfiguration loadYamlQuietly() {
        try {
            if (!plugin.getDataFolder().exists()) {
                //noinspection ResultOfMethodCallIgnored
                plugin.getDataFolder().mkdirs();
            }
            if (!persistentFile.exists()) {
                YamlConfiguration fresh = new YamlConfiguration();
                fresh.set("records", new java.util.LinkedHashMap<String, Object>());
                return fresh;
            }
            YamlConfiguration loaded = YamlConfiguration.loadConfiguration(persistentFile);
            if (loaded.get("records") == null) loaded.set("records", new java.util.LinkedHashMap<String, Object>());
            return loaded;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void queueSave() {
        if (yaml() == null) return;
        if (saveQueued) return;
        saveQueued = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            synchronized (fileLock) {
                try {
                    var yml = yaml();
                    if (yml != null) yml.save(persistentFile);
                } catch (Throwable ignored) {
                } finally {
                    saveQueued = false;
                }
            }
        }, 1L);
    }

    private UUID tryReadPokemonUuidFromEntity(Entity bukkitEntity) {
        if (bukkitEntity == null) return null;
        try {
            Object handle = bukkitEntity.getClass().getMethod("getHandle").invoke(bukkitEntity);
            if (handle == null) return null;
            Object pokemon = handle.getClass().getMethod("getPokemon").invoke(handle);
            if (pokemon == null) return null;
            Object pu = pokemon.getClass().getMethod("getUuid").invoke(pokemon);
            return pu instanceof UUID id ? id : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Cobblemon PokemonEntity has a server-side UNBATTLEABLE flag checked by PokemonEntity#canBattle.
     * We set/unset it via reflection to ensure protection can hard-block battle initiation even if
     * battle pre-events are skipped by some battle starters.
     */
    private void setUnbattleable(Entity bukkitEntity, boolean on) {
        if (bukkitEntity == null) return;
        try {
            Object handle = bukkitEntity.getClass().getMethod("getHandle").invoke(bukkitEntity);
            if (handle == null) return;

            Object entityData = handle.getClass().getMethod("getEntityData").invoke(handle);
            if (entityData == null) return;

            Object accessor = handle.getClass().getField("UNBATTLEABLE").get(null);
            if (accessor == null) return;

            for (var m : entityData.getClass().getMethods()) {
                if (!m.getName().equals("set")) continue;
                if (m.getParameterCount() != 2) continue;
                if (!m.getParameterTypes()[0].isInstance(accessor)) continue;
                m.setAccessible(true);
                m.invoke(entityData, accessor, on);
                return;
            }
        } catch (Throwable ignored) {
        }
    }
}
