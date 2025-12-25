package com.polaris.cobbletip.listener;

import com.polaris.cobbletip.CobbleTipPlugin;
import com.polaris.cobbletip.gui.PartyViewHolder;
import com.polaris.cobbletip.util.Msg;
import com.polaris.cobbletip.util.TipConfig;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;

public final class PartyViewListener implements Listener {
    private final CobbleTipPlugin plugin;
    private final TipConfig cfg;

    public PartyViewListener(CobbleTipPlugin plugin, TipConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!cfg.partyEnabled()) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player viewer = event.getPlayer();

        if (!viewer.hasPermission("cobbletip.partyview")) {
            viewer.sendMessage(Msg.color(Msg.apply(cfg.msgNoPermission(), cfg)));
            return;
        }

        if (cfg.partyRequireSneak() && !viewer.isSneaking() && !viewer.hasPermission("cobbletip.partyview.bypass")) {
            return;
        }

        if (!(event.getRightClicked() instanceof Player target)) return;
        if (target.equals(viewer)) return;

        List<?> party;
        try {
            party = fetchPartyFromCore(target);
        } catch (Throwable t) {
            if (cfg.debug()) plugin.getLogger().warning("[CobbleTip] Failed to fetch party: " + t);
            viewer.sendMessage(Msg.color(Msg.apply(cfg.msgPartyViewFailed(), cfg)));
            return;
        }

        if (party == null) party = List.of();
        if (party.isEmpty()) {
            viewer.sendMessage(Msg.color(Msg.apply(cfg.msgPartyViewEmpty(), cfg, "player", target.getName())));
            return;
        }

        String title = Msg.color(Msg.apply(cfg.msgPartyViewTitle(), cfg, "player", target.getName()));
        int invSize = 9;
        Inventory inv = Bukkit.createInventory(new PartyViewHolder(target.getUniqueId()), invSize, title);

        ItemStack[] team = new ItemStack[6];
        party.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(PartyViewListener::dtoSlot))
                .forEach(dto -> {
                    int s = dtoSlot(dto);
                    if (s >= 0 && s < 6) team[s] = renderPartyPokemon(dto);
                });

        // 排版：-123-456-
        inv.setItem(0, new ItemStack(Material.AIR));
        inv.setItem(4, new ItemStack(Material.AIR));
        inv.setItem(8, new ItemStack(Material.AIR));
        inv.setItem(1, team[0]);
        inv.setItem(2, team[1]);
        inv.setItem(3, team[2]);
        inv.setItem(5, team[3]);
        inv.setItem(6, team[4]);
        inv.setItem(7, team[5]);

        viewer.openInventory(inv);
    }

    @SuppressWarnings("unchecked")
    private List<?> fetchPartyFromCore(Player target) throws Exception {
        Plugin core = Bukkit.getPluginManager().getPlugin("CobbleCore");
        if (core == null) return List.of();

        Object api = core.getClass().getMethod("api").invoke(core);
        if (api == null) return List.of();

        Object out = api.getClass().getMethod("getParty", Player.class).invoke(api, target);
        if (out instanceof List<?> list) return list;
        return List.of();
    }

    private ItemStack renderPartyPokemon(Object dto) {
        boolean shiny = dtoBoolean(dto, "shiny");
        String speciesName = dtoString(dto, "speciesName");
        String speciesId = dtoString(dto, "species");
        String species = (speciesName == null || speciesName.isBlank()) ? speciesId : speciesName;
        String nickname = dtoString(dto, "nickname");
        int level = dtoInt(dto, "level");

        List<String> loreLines = dtoStringList(dto, "lore");
        String pokemonNbt = dtoString(dto, "pokemonNbt");

        String display = (species == null || species.isBlank()) ? "unknown" : species;
        String nn = nickname == null ? "" : nickname;
        if (!nn.isBlank()) display = nn + " (" + display + ")";

        String displayLegacy = Msg.color("&a" + display + " &7Lv." + level + (shiny ? " &6*" : ""));
        List<String> loreLegacy = new ArrayList<>();
        for (String l : loreLines) loreLegacy.add(Msg.color(l));

        return createPokemonModelItem(pokemonNbt, shiny, displayLegacy, loreLegacy);
    }

    private static int normalizeInventorySize(int size) {
        int v = size <= 0 ? 54 : size;
        int rows = (v + 8) / 9;
        if (rows < 1) rows = 1;
        if (rows > 6) rows = 6;
        return rows * 9;
    }

    private ItemStack createPokemonModelItem(String pokemonNbtSnbt, boolean shiny, String displayLegacy, List<String> loreLegacy) {
        ItemStack base = new ItemStack(shiny ? Material.GOLD_NUGGET : Material.PAPER);
        if (pokemonNbtSnbt == null || pokemonNbtSnbt.isBlank()) {
            applyBukkitNameLore(base, displayLegacy, loreLegacy);
            return base;
        }

        String snbt = pokemonNbtSnbt.trim();

        Object nmsFromCobblemonFactory = tryCreatePokemonModelWithCobblemonFactory(snbt, cfg.debug());
        if (nmsFromCobblemonFactory != null) {
            applyNmsNameLore(nmsFromCobblemonFactory, displayLegacy, loreLegacy);
            try {
                ItemStack wrapped = wrapNmsItemStack(nmsFromCobblemonFactory);
                if (wrapped != null) {
                    applyBukkitNameLore(wrapped, displayLegacy, loreLegacy);
                    return wrapped;
                }
            } catch (Throwable t) {
                if (cfg.debug()) plugin.getLogger().log(Level.WARNING, "[CobbleTip] Failed to wrap NMS stack (Cobblemon factory): " + t, t);
            }
        }

        Object nmsFromCobblemon = tryCreatePokemonModelWithCobblemonStatics(snbt, cfg.debug());
        if (nmsFromCobblemon != null) {
            applyNmsNameLore(nmsFromCobblemon, displayLegacy, loreLegacy);
            try {
                ItemStack wrapped = wrapNmsItemStack(nmsFromCobblemon);
                if (wrapped != null) {
                    applyBukkitNameLore(wrapped, displayLegacy, loreLegacy);
                    return wrapped;
                }
            } catch (Throwable t) {
                if (cfg.debug()) plugin.getLogger().log(Level.WARNING, "[CobbleTip] Failed to wrap NMS stack (Cobblemon statics): " + t, t);
            }
        }

        Object nmsFromRegistries = tryCreatePokemonModelWithNmsRegistries(snbt, cfg.debug());
        if (nmsFromRegistries == null && cfg.debug()) {
            plugin.getLogger().info("[CobbleTip] NMS registries path returned null.");
        }
        if (nmsFromRegistries != null) {
            applyNmsNameLore(nmsFromRegistries, displayLegacy, loreLegacy);
            try {
                ItemStack wrapped = wrapNmsItemStack(nmsFromRegistries);
                if (wrapped != null) {
                    applyBukkitNameLore(wrapped, displayLegacy, loreLegacy);
                    return wrapped;
                }
            } catch (Throwable t) {
                if (cfg.debug()) plugin.getLogger().log(Level.WARNING, "[CobbleTip] Failed to wrap NMS stack (registries): " + t, t);
            }
        }

        // Most reliable on hybrid/modded servers: use NMS ItemParser (same syntax as /give).
        Object nmsParsed = tryCreatePokemonModelWithNmsItemParser(snbt, cfg.debug());
        if (nmsParsed == null && cfg.debug()) {
            plugin.getLogger().info("[CobbleTip] NMS ItemParser path returned null.");
        }
        if (nmsParsed != null) {
            applyNmsNameLore(nmsParsed, displayLegacy, loreLegacy);
            try {
                ItemStack wrapped = wrapNmsItemStack(nmsParsed);
                if (wrapped != null) {
                    applyBukkitNameLore(wrapped, displayLegacy, loreLegacy);
                    return wrapped;
                }
            } catch (Throwable t) {
                if (cfg.debug()) plugin.getLogger().log(Level.WARNING, "[CobbleTip] Failed to wrap NMS stack (ItemParser): " + t, t);
            }
        }

        if (cfg.debug()) plugin.getLogger().warning("[CobbleTip] createPokemonModelItem fell back to Bukkit item (PAPER).");
        applyBukkitNameLore(base, displayLegacy, loreLegacy);
        return base;
    }

    private Object tryCreatePokemonModelWithCobblemonStatics(String pokemonItemComponentSnbt, boolean debug) {
        if (pokemonItemComponentSnbt == null || pokemonItemComponentSnbt.isBlank()) return null;
        try {
            Object componentValue = decodePokemonItemComponent(pokemonItemComponentSnbt, debug);
            if (componentValue == null) return null;

            Object cobblemonModelItem;
            try {
                Class<?> itemsClass = loadClass("com.cobblemon.mod.common.CobblemonItems");
                cobblemonModelItem = itemsClass.getField("POKEMON_MODEL").get(null);
            } catch (Throwable t) {
                if (debug) plugin.getLogger().log(Level.WARNING, "[CobbleTip] Failed to read CobblemonItems.POKEMON_MODEL: " + t, t);
                return null;
            }
            if (cobblemonModelItem == null) return null;

            Object componentType;
            try {
                Class<?> compsClass = loadClass("com.cobblemon.mod.common.CobblemonItemComponents");
                componentType = compsClass.getField("POKEMON_ITEM").get(null);
            } catch (Throwable t) {
                if (debug) plugin.getLogger().log(Level.WARNING, "[CobbleTip] Failed to read CobblemonItemComponents.POKEMON_ITEM: " + t, t);
                return null;
            }
            if (componentType == null) return null;

            Object nmsStack = createNmsItemStackFromItem(cobblemonModelItem);
            if (nmsStack == null) return null;
            setNmsComponent(nmsStack, componentType, componentValue);
            return nmsStack;
        } catch (Throwable t) {
            if (debug) plugin.getLogger().log(Level.WARNING, "[CobbleTip] Failed to create pokemon model via Cobblemon statics: " + t, t);
            return null;
        }
    }

    private Object tryCreatePokemonModelWithCobblemonFactory(String pokemonItemComponentSnbt, boolean debug) {
        if (pokemonItemComponentSnbt == null || pokemonItemComponentSnbt.isBlank()) return null;
        try {
            String speciesId = parseSnbtStringField(pokemonItemComponentSnbt, "species");
            if (speciesId == null || speciesId.isBlank()) {
                if (debug) plugin.getLogger().warning("[CobbleTip] Cobblemon factory: missing species in SNBT");
                return null;
            }
            List<String> aspects = parseSnbtStringList(pokemonItemComponentSnbt, "aspects");
            if (aspects.isEmpty()) aspects = List.of("");

            Object species = resolvePokemonSpecies(speciesId, debug);
            if (species == null) {
                if (debug) plugin.getLogger().warning("[CobbleTip] Cobblemon factory: species not found for " + speciesId);
                return null;
            }

            Object nmsStack = createPokemonItemStackFromSpecies(species, aspects, debug);
            if (nmsStack == null && debug) {
                plugin.getLogger().warning("[CobbleTip] Cobblemon factory: failed to create ItemStack from species " + speciesId);
            }
            return nmsStack;
        } catch (Throwable t) {
            if (debug) plugin.getLogger().log(Level.WARNING, "[CobbleTip] Cobblemon factory path failed: " + t, t);
            return null;
        }
    }

    private Object resolvePokemonSpecies(String speciesId, boolean debug) {
        if (speciesId == null || speciesId.isBlank()) return null;
        try {
            String id = speciesId.trim();
            String namespace = "";
            String path = id;
            int sep = id.indexOf(':');
            if (sep >= 0) {
                namespace = id.substring(0, sep);
                path = id.substring(sep + 1);
            }

            Class<?> pokemonSpeciesClass = loadClass("com.cobblemon.mod.common.api.pokemon.PokemonSpecies");
            if ("cobblemon".equalsIgnoreCase(namespace) && !path.isBlank()) {
                try {
                    Object byName = pokemonSpeciesClass.getMethod("getByName", String.class).invoke(null, path);
                    if (byName != null) return byName;
                } catch (NoSuchMethodException ignored) {}
            }

            for (var m : pokemonSpeciesClass.getMethods()) {
                if (!m.getName().equals("getByIdentifier")) continue;
                if (m.getParameterCount() != 1) continue;
                Class<?> p0 = m.getParameterTypes()[0];
                if (p0 == String.class) {
                    Object byId = m.invoke(null, id);
                    if (byId != null) return byId;
                    continue;
                }
                Object rl = createResourceLocation(p0, id);
                if (rl == null) continue;
                Object byId = m.invoke(null, rl);
                if (byId != null) return byId;
            }
        } catch (Throwable t) {
            if (debug) plugin.getLogger().log(Level.WARNING, "[CobbleTip] Cobblemon factory: resolve species failed: " + t, t);
        }
        return null;
    }

    private static Object createPokemonItemStackFromSpecies(Object species, List<String> aspects, boolean debug) {
        if (species == null) return null;
        try {
            Class<?> pokemonItemClass = loadClass("com.cobblemon.mod.common.item.PokemonItem");
            java.util.Set<String> aspectSet = new java.util.LinkedHashSet<>(aspects == null ? List.of() : aspects);
            String[] aspectArray = aspectSet.toArray(new String[0]);

            for (var m : pokemonItemClass.getMethods()) {
                if (!m.getName().equals("from")) continue;
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 4
                        && p[0].isInstance(species)
                        && java.util.Set.class.isAssignableFrom(p[1])
                        && (p[2] == int.class || p[2] == Integer.class)) {
                    return m.invoke(null, species, aspectSet, 1, null);
                }
            }

            for (var m : pokemonItemClass.getMethods()) {
                if (!m.getName().equals("from")) continue;
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 4
                        && p[0].isInstance(species)
                        && p[1].isArray()
                        && p[1].getComponentType() == String.class
                        && (p[2] == int.class || p[2] == Integer.class)) {
                    return m.invoke(null, species, aspectArray, 1, null);
                }
            }

            for (var m : pokemonItemClass.getMethods()) {
                if (!m.getName().equals("from")) continue;
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 3
                        && p[0].isInstance(species)
                        && p[1].isArray()
                        && p[1].getComponentType() == String.class
                        && (p[2] == int.class || p[2] == Integer.class)) {
                    return m.invoke(null, species, aspectArray, 1);
                }
            }

            for (var m : pokemonItemClass.getMethods()) {
                if (!m.getName().equals("from")) continue;
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2
                        && p[0].isInstance(species)
                        && p[1].isArray()
                        && p[1].getComponentType() == String.class) {
                    return m.invoke(null, species, aspectArray);
                }
            }
        } catch (Throwable t) {
            if (debug) Bukkit.getLogger().log(Level.WARNING, "[CobbleTip] Cobblemon factory: PokemonItem.from failed: " + t, t);
        }
        return null;
    }

    private static Object createResourceLocation(Class<?> rlClass, String id) {
        if (rlClass == null || id == null || id.isBlank()) return null;
        String namespace = "";
        String path = id.trim();
        int sep = path.indexOf(':');
        if (sep >= 0) {
            namespace = path.substring(0, sep);
            path = path.substring(sep + 1);
        }
        try {
            for (var ctor : rlClass.getConstructors()) {
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length == 1 && p[0] == String.class) return ctor.newInstance(id);
                if (p.length == 2 && p[0] == String.class && p[1] == String.class) {
                    String ns = namespace.isBlank() ? "cobblemon" : namespace;
                    return ctor.newInstance(ns, path);
                }
            }
            for (var m : rlClass.getMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (!rlClass.isAssignableFrom(m.getReturnType())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 1 && p[0] == String.class) {
                    Object out = m.invoke(null, id);
                    if (out != null) return out;
                }
                if (p.length == 2 && p[0] == String.class && p[1] == String.class) {
                    String ns = namespace.isBlank() ? "cobblemon" : namespace;
                    Object out = m.invoke(null, ns, path);
                    if (out != null) return out;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String parseSnbtStringField(String snbt, String key) {
        if (snbt == null || snbt.isBlank() || key == null || key.isBlank()) return "";
        Pattern p = Pattern.compile(Pattern.quote(key) + "\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher m = p.matcher(snbt);
        if (!m.find()) return "";
        return unescapeSnbtString(m.group(1));
    }

    private static List<String> parseSnbtStringList(String snbt, String key) {
        if (snbt == null || snbt.isBlank() || key == null || key.isBlank()) return List.of();
        Pattern p = Pattern.compile(Pattern.quote(key) + "\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
        Matcher m = p.matcher(snbt);
        if (!m.find()) return List.of();
        String inner = m.group(1);
        if (inner == null || inner.isBlank()) return List.of();

        List<String> out = new ArrayList<>();
        Pattern item = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");
        Matcher im = item.matcher(inner);
        while (im.find()) {
            out.add(unescapeSnbtString(im.group(1)));
        }
        return out;
    }

    private static String unescapeSnbtString(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length());
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                out.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private Object tryCreatePokemonModelWithNmsRegistries(String pokemonItemComponentSnbt, boolean debug) {
        if (pokemonItemComponentSnbt == null || pokemonItemComponentSnbt.isBlank()) return null;
        try {
            Class<?> resourceLocationClass = loadClass("net.minecraft.resources.ResourceLocation");
            Object modelId = resourceLocationClass.getMethod("parse", String.class).invoke(null, "cobblemon:pokemon_model");
            Object componentId = resourceLocationClass.getMethod("parse", String.class).invoke(null, "cobblemon:pokemon_item");

            Class<?> builtInRegistriesClass = loadClass("net.minecraft.core.registries.BuiltInRegistries");
            Object itemRegistry = builtInRegistriesClass.getField("ITEM").get(null);
            Object componentRegistry = builtInRegistriesClass.getField("DATA_COMPONENT_TYPE").get(null);

            Object nmsItem = itemRegistry.getClass().getMethod("get", resourceLocationClass).invoke(itemRegistry, modelId);
            if (nmsItem == null) {
                if (debug) plugin.getLogger().warning("[CobbleTip] Registries: ITEM returned null for cobblemon:pokemon_model");
                return null;
            }

            Class<?> nmsItemStackClass = loadClass("net.minecraft.world.item.ItemStack");
            Object nmsStack = createNmsItemStackFromItem(nmsItem);
            if (nmsStack == null) {
                if (debug) plugin.getLogger().warning("[CobbleTip] Registries: failed to create ItemStack from cobblemon:pokemon_model");
                return null;
            }

            Object componentType = componentRegistry.getClass().getMethod("get", resourceLocationClass).invoke(componentRegistry, componentId);
            if (componentType == null) {
                if (debug) plugin.getLogger().warning("[CobbleTip] Registries: DATA_COMPONENT_TYPE returned null for cobblemon:pokemon_item");
                return null;
            }

            Object componentValue = decodePokemonItemComponent(pokemonItemComponentSnbt, debug);
            if (componentValue == null) {
                if (debug) plugin.getLogger().warning("[CobbleTip] Registries: decodePokemonItemComponent returned null");
                return null;
            }

            // ItemStack#set(DataComponentType, value)
            boolean setOk = false;
            for (var m : nmsItemStackClass.getMethods()) {
                if (!m.getName().equals("set")) continue;
                if (m.getParameterCount() != 2) continue;
                try {
                    if (!m.getParameterTypes()[0].isInstance(componentType)) continue;
                    m.setAccessible(true);
                    m.invoke(nmsStack, componentType, componentValue);
                    setOk = true;
                    break;
                } catch (Throwable ignored) {}
            }
            if (!setOk) {
                if (debug) plugin.getLogger().warning("[CobbleTip] Registries: ItemStack#set(DataComponentType, value) not found");
                return null;
            }

            return nmsStack;
        } catch (Throwable t) {
            if (debug) plugin.getLogger().log(Level.WARNING, "[CobbleTip] Registries path failed: " + t, t);
            return null;
        }
    }

    private Object tryCreatePokemonModelWithNmsItemParser(String pokemonItemComponentSnbt, boolean debug) {
        if (pokemonItemComponentSnbt == null || pokemonItemComponentSnbt.isBlank()) return null;
        try {
            Object craftServer = Bukkit.getServer();
            if (craftServer == null) return null;

            Object minecraftServer;
            try {
                minecraftServer = craftServer.getClass().getMethod("getServer").invoke(craftServer);
            } catch (NoSuchMethodException e) {
                minecraftServer = craftServer.getClass().getMethod("getHandle").invoke(craftServer);
            }
            if (minecraftServer == null) return null;

            Object registryAccess = minecraftServer.getClass().getMethod("registryAccess").invoke(minecraftServer);
            if (registryAccess == null) return null;

            Class<?> itemParserClass = loadClass("net.minecraft.commands.arguments.item.ItemParser");
            Object itemParser = tryCreateNmsItemParser(itemParserClass, registryAccess);
            if (itemParser == null) return null;

            Class<?> stringReaderClass = loadClass("com.mojang.brigadier.StringReader");
            String input = "cobblemon:pokemon_model[cobblemon:pokemon_item=" + pokemonItemComponentSnbt + "]";
            if (debug) plugin.getLogger().info("[CobbleTip] ItemParser input=" + input);
            Object reader = stringReaderClass.getConstructor(String.class).newInstance(input);

            Object parsed = itemParserClass.getMethod("parse", stringReaderClass).invoke(itemParser, reader);
            if (parsed == null) return null;

            Object nmsStack = null;
            try {
                nmsStack = parsed.getClass().getMethod("createItemStack", int.class, boolean.class).invoke(parsed, 1, false);
            } catch (NoSuchMethodException e) {
                try {
                    nmsStack = parsed.getClass().getMethod("createItemStack", int.class).invoke(parsed, 1);
                } catch (NoSuchMethodException ignored) {}
            }
            return nmsStack;
        } catch (Throwable t) {
            if (debug) plugin.getLogger().log(Level.WARNING, "[CobbleTip] ItemParser path failed: " + t, t);
            return null;
        }
    }

    private static Object createNmsItemStackFromItem(Object nmsItem) {
        if (nmsItem == null) return null;
        try {
            Class<?> nmsItemStackClass = loadClass("net.minecraft.world.item.ItemStack");
            for (var ctor : nmsItemStackClass.getConstructors()) {
                try {
                    Class<?>[] p = ctor.getParameterTypes();
                    if (p.length == 1 && p[0].isInstance(nmsItem)) {
                        return ctor.newInstance(nmsItem);
                    }
                    if (p.length == 2 && p[0].isInstance(nmsItem) && (p[1] == int.class || p[1] == Integer.class)) {
                        return ctor.newInstance(nmsItem, 1);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Object decodePokemonItemComponent(String pokemonItemComponentSnbt, boolean debug) {
        if (pokemonItemComponentSnbt == null || pokemonItemComponentSnbt.isBlank()) return null;
        try {
            // Parse SNBT -> NBT tag
            Object tag = null;
            try {
                Class<?> tagParserClass = loadClass("net.minecraft.nbt.TagParser");
                try {
                    tag = tagParserClass.getMethod("parseTag", String.class).invoke(null, pokemonItemComponentSnbt);
                } catch (NoSuchMethodException e) {
                    Class<?> stringReaderClass = loadClass("com.mojang.brigadier.StringReader");
                    Object reader = stringReaderClass.getConstructor(String.class).newInstance(pokemonItemComponentSnbt);
                    tag = tagParserClass.getMethod("parseTag", stringReaderClass).invoke(null, reader);
                }
            } catch (Throwable ignored) {}
            if (tag == null) return null;

            // Decode NBT tag -> PokemonItemComponent via Cobblemon codec.
            Object codec = null;
            try {
                Class<?> picClass = loadClass("com.cobblemon.mod.common.item.components.PokemonItemComponent");
                codec = picClass.getField("CODEC").get(null);
            } catch (Throwable ignored) {}
            if (codec == null) {
                if (debug) plugin.getLogger().warning("[CobbleTip] decodePokemonItemComponent: CODEC not found");
                return null;
            }

            Object nbtOps;
            try {
                Class<?> nbtOpsClass = loadClass("net.minecraft.nbt.NbtOps");
                nbtOps = nbtOpsClass.getField("INSTANCE").get(null);
            } catch (Throwable t) {
                return null;
            }

            Object dataResult;
            try {
                // Codec#parse(DynamicOps, Object)
                dataResult = codec.getClass().getMethod("parse", loadClass("com.mojang.serialization.DynamicOps"), Object.class)
                        .invoke(codec, nbtOps, tag);
            } catch (NoSuchMethodException e) {
                // Some mappings erase generics differently; fall back to any 2-arg parse.
                dataResult = null;
                for (var m : codec.getClass().getMethods()) {
                    if (!m.getName().equals("parse")) continue;
                    if (m.getParameterCount() != 2) continue;
                    try {
                        dataResult = m.invoke(codec, nbtOps, tag);
                        break;
                    } catch (Throwable ignored) {}
                }
            }
            if (dataResult == null) {
                if (debug) plugin.getLogger().warning("[CobbleTip] decodePokemonItemComponent: DataResult is null");
                return null;
            }

            Object componentValue = null;
            try {
                Object opt = dataResult.getClass().getMethod("result").invoke(dataResult);
                if (opt instanceof java.util.Optional<?> o) componentValue = o.orElse(null);
            } catch (Throwable ignored) {}
            if (componentValue == null && debug) {
                try {
                    Object err = dataResult.getClass().getMethod("error").invoke(dataResult);
                    if (err instanceof java.util.Optional<?> o) {
                        Object msg = tryInvokeOptionalMessage(o.orElse(null));
                        if (msg != null) {
                            plugin.getLogger().warning("[CobbleTip] decodePokemonItemComponent error: " + msg);
                        }
                    }
                } catch (Throwable ignored) {}
            }
            return componentValue;
        } catch (Throwable t) {
            if (debug) plugin.getLogger().log(Level.WARNING, "[CobbleTip] decodePokemonItemComponent failed: " + t, t);
            return null;
        }
    }

    private static Object tryInvokeOptionalMessage(Object err) {
        if (err == null) return null;
        try {
            Object msg = err.getClass().getMethod("message").invoke(err);
            if (msg != null) return msg;
        } catch (Throwable ignored) {}
        try {
            Object msg = err.getClass().getMethod("toString").invoke(err);
            if (msg != null) return msg;
        } catch (Throwable ignored) {}
        return String.valueOf(err);
    }

    private static Object tryCreateNmsItemParser(Class<?> itemParserClass, Object registryAccess) {
        if (itemParserClass == null || registryAccess == null) return null;

        // 1) ctor(registryAccess) or ctor(CommandBuildContext) depending on server mappings.
        for (var ctor : itemParserClass.getConstructors()) {
            try {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length != 1) continue;
                Class<?> p0 = params[0];
                if (p0.isInstance(registryAccess)) return ctor.newInstance(registryAccess);

                Object ctx = tryBuildCommandBuildContext(registryAccess, p0);
                if (ctx != null && p0.isInstance(ctx)) return ctor.newInstance(ctx);
            } catch (Throwable ignored) {}
        }

        // 2) static factory methods returning ItemParser.
        for (var m : itemParserClass.getMethods()) {
            try {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (!itemParserClass.isAssignableFrom(m.getReturnType())) continue;
                if (m.getParameterCount() != 1) continue;
                Class<?> p0 = m.getParameterTypes()[0];
                if (p0.isInstance(registryAccess)) return m.invoke(null, registryAccess);
                Object ctx = tryBuildCommandBuildContext(registryAccess, p0);
                if (ctx != null && p0.isInstance(ctx)) return m.invoke(null, ctx);
            } catch (Throwable ignored) {}
        }

        return null;
    }

    private static Object tryBuildCommandBuildContext(Object registryAccess, Class<?> expectedType) {
        if (registryAccess == null || expectedType == null) return null;
        try {
            Class<?> cbc = loadClass("net.minecraft.commands.CommandBuildContext");
            if (!expectedType.isAssignableFrom(cbc) && !cbc.isAssignableFrom(expectedType)) {
                if (!expectedType.getName().toLowerCase().contains("commandbuildcontext")) return null;
            }

            // Try static factories on CommandBuildContext.
            for (var m : cbc.getMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (!cbc.isAssignableFrom(m.getReturnType())) continue;
                Class<?>[] p = m.getParameterTypes();
                try {
                    if (p.length == 1 && p[0].isInstance(registryAccess)) return m.invoke(null, registryAccess);
                    if (p.length == 2 && p[0].isInstance(registryAccess)) {
                        Object flags = tryGetDefaultFeatureFlags(p[1]);
                        if (flags != null && p[1].isInstance(flags)) return m.invoke(null, registryAccess, flags);
                    }
                } catch (Throwable ignored) {}
            }

            // Try constructors.
            for (var ctor : cbc.getConstructors()) {
                Class<?>[] p = ctor.getParameterTypes();
                try {
                    if (p.length == 1 && p[0].isInstance(registryAccess)) return ctor.newInstance(registryAccess);
                    if (p.length == 2 && p[0].isInstance(registryAccess)) {
                        Object flags = tryGetDefaultFeatureFlags(p[1]);
                        if (flags != null && p[1].isInstance(flags)) return ctor.newInstance(registryAccess, flags);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object tryGetDefaultFeatureFlags(Class<?> flagsType) {
        if (flagsType == null) return null;
        try {
            Class<?> ff = loadClass("net.minecraft.world.flag.FeatureFlags");
            for (var f : ff.getFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!flagsType.isAssignableFrom(f.getType())) continue;
                String n = f.getName();
                if (n.equalsIgnoreCase("VANILLA_SET") || n.equalsIgnoreCase("DEFAULT_FLAGS") || n.equalsIgnoreCase("DEFAULT")) {
                    Object v = f.get(null);
                    if (v != null) return v;
                }
            }
            for (var f : ff.getFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!flagsType.isAssignableFrom(f.getType())) continue;
                Object v = f.get(null);
                if (v != null) return v;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Class<?> loadClass(String name) throws ClassNotFoundException {
        if (name == null || name.isBlank()) throw new ClassNotFoundException("empty");

        ClassLoader serverLoader = null;
        try {
            Object server = Bukkit.getServer();
            if (server != null) serverLoader = server.getClass().getClassLoader();
        } catch (Throwable ignored) {}

        ClassLoader[] loaders = new ClassLoader[] {
                PartyViewListener.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                serverLoader,
                ClassLoader.getSystemClassLoader()
        };

        for (ClassLoader cl : loaders) {
            if (cl == null) continue;
            try {
                return Class.forName(name, false, cl);
            } catch (ClassNotFoundException ignored) {}
        }

        return Class.forName(name);
    }

    private static ItemStack wrapNmsItemStack(Object nmsStack) throws Exception {
        if (nmsStack == null) return null;
        Class<?> nmsItemStackClass = nmsStack.getClass();
        String craftPackage = Bukkit.getServer().getClass().getPackage().getName();
        Class<?> craftItemStackClass = loadClass(craftPackage + ".inventory.CraftItemStack");

        // Prefer mirror (keeps handle), fall back to a copy.
        for (String name : List.of("asCraftMirror", "asBukkitCopy")) {
            for (var m : craftItemStackClass.getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() != 1) continue;
                if (!m.getParameterTypes()[0].isInstance(nmsStack)) continue;
                try {
                    return (ItemStack) m.invoke(null, nmsStack);
                } catch (Throwable ignored) {}
            }
        }

        // Last-ditch: find any static 1-arg method taking ItemStack.
        for (var m : craftItemStackClass.getMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() != 1) continue;
            if (!m.getParameterTypes()[0].isInstance(nmsStack)) continue;
            if (!ItemStack.class.isAssignableFrom(m.getReturnType())) continue;
            try {
                return (ItemStack) m.invoke(null, nmsStack);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Material resolveMaterial(String key) {
        if (key == null || key.isBlank()) return null;
        try {
            Material m = Material.matchMaterial(key);
            if (m != null) return m;
        } catch (Throwable ignored) {}
        try {
            Object unsafe = Bukkit.getUnsafe();
            var m = unsafe.getClass().getMethod("getMaterial", String.class);
            Object out = m.invoke(unsafe, key);
            if (out instanceof Material mat) return mat;
        } catch (Throwable ignored) {}
        return null;
    }

    private static void applyBukkitNameLore(ItemStack item, String displayLegacy, List<String> loreLegacy) {
        if (item == null) return;
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return;
            if (displayLegacy != null && !displayLegacy.isBlank()) meta.setDisplayName(displayLegacy);
            if (loreLegacy != null && !loreLegacy.isEmpty()) meta.setLore(new ArrayList<>(loreLegacy));
            item.setItemMeta(meta);
        } catch (Throwable ignored) {}
    }

    private static void applyNmsNameLore(Object nmsStack, String displayLegacy, List<String> loreLegacy) {
        if (nmsStack == null) return;
        try {
            Class<?> dataComponentsClass = loadClass("net.minecraft.core.component.DataComponents");
            Object customNameType = null;
            try { customNameType = dataComponentsClass.getField("CUSTOM_NAME").get(null); } catch (Throwable ignored) {}
            if (customNameType == null) {
                try { customNameType = dataComponentsClass.getField("ITEM_NAME").get(null); } catch (Throwable ignored) {}
            }

            Object loreType = null;
            try { loreType = dataComponentsClass.getField("LORE").get(null); } catch (Throwable ignored) {}

            Object nameComponent = displayLegacy == null ? null : legacyToNmsComponent(displayLegacy);
            if (customNameType != null && nameComponent != null) setNmsComponent(nmsStack, customNameType, nameComponent);

            if (loreType != null && loreLegacy != null && !loreLegacy.isEmpty()) {
                List<Object> lines = new ArrayList<>();
                for (String s : loreLegacy) {
                    Object c = legacyToNmsComponent(s == null ? "" : s);
                    if (c != null) lines.add(c);
                }
                Object loreValue = createNmsItemLore(lines);
                if (loreValue != null) setNmsComponent(nmsStack, loreType, loreValue);
            }
        } catch (Throwable ignored) {}
    }

    private static Object legacyToNmsComponent(String legacy) {
        if (legacy == null) return null;
        try {
            String craftPackage = Bukkit.getServer().getClass().getPackage().getName();
            Class<?> craftChatMessageClass = loadClass(craftPackage + ".util.CraftChatMessage");

            // fromStringOrNull(String) -> Component
            try {
                var m = craftChatMessageClass.getMethod("fromStringOrNull", String.class);
                Object out = m.invoke(null, legacy);
                if (out != null) return out;
            } catch (NoSuchMethodException ignored) {}

            // fromString(String, boolean) -> Component[]
            try {
                var m = craftChatMessageClass.getMethod("fromString", String.class, boolean.class);
                Object out = m.invoke(null, legacy, true);
                if (out instanceof Object[] arr && arr.length > 0) return arr[0];
            } catch (NoSuchMethodException ignored) {}

            // fromString(String) -> Component[]
            try {
                var m = craftChatMessageClass.getMethod("fromString", String.class);
                Object out = m.invoke(null, legacy);
                if (out instanceof Object[] arr && arr.length > 0) return arr[0];
            } catch (NoSuchMethodException ignored) {}
        } catch (Throwable ignored) {}

        try {
            Class<?> componentClass = loadClass("net.minecraft.network.chat.Component");
            var lit = componentClass.getMethod("literal", String.class);
            return lit.invoke(null, stripLegacyColors(legacy));
        } catch (Throwable ignored) {}
        return null;
    }

    private static String stripLegacyColors(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00A7' && i + 1 < s.length()) { i++; continue; }
            out.append(c);
        }
        return out.toString();
    }

    private static Object createNmsItemLore(List<Object> nmsComponents) {
        if (nmsComponents == null) return null;
        try {
            Class<?> itemLoreClass = loadClass("net.minecraft.world.item.component.ItemLore");
            for (var ctor : itemLoreClass.getConstructors()) {
                try {
                    Class<?>[] p = ctor.getParameterTypes();
                    if (p.length == 1 && java.util.List.class.isAssignableFrom(p[0])) {
                        return ctor.newInstance(nmsComponents);
                    }
                    if (p.length == 2 && java.util.List.class.isAssignableFrom(p[0]) && p[1] == boolean.class) {
                        return ctor.newInstance(nmsComponents, true);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void setNmsComponent(Object nmsStack, Object dataComponentType, Object value) {
        if (nmsStack == null || dataComponentType == null || value == null) return;
        try {
            for (var m : nmsStack.getClass().getMethods()) {
                if (!m.getName().equals("set")) continue;
                if (m.getParameterCount() != 2) continue;
                try {
                    if (!m.getParameterTypes()[0].isInstance(dataComponentType)) continue;
                    m.setAccessible(true);
                    m.invoke(nmsStack, dataComponentType, value);
                    return;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static int dtoSlot(Object dto) {
        return dtoInt(dto, "slot");
    }

    private static int dtoInt(Object dto, String method) {
        try {
            Object v = dto.getClass().getMethod(method).invoke(dto);
            return v instanceof Number n ? n.intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static boolean dtoBoolean(Object dto, String method) {
        try {
            Object v = dto.getClass().getMethod(method).invoke(dto);
            return v instanceof Boolean b && b;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String dtoString(Object dto, String method) {
        try {
            Object v = dto.getClass().getMethod(method).invoke(dto);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable ignored) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> dtoStringList(Object dto, String method) {
        try {
            Object v = dto.getClass().getMethod(method).invoke(dto);
            if (v instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object o : list) out.add(o == null ? "" : String.valueOf(o));
                return List.copyOf(out);
            }
            return List.of();
        } catch (Throwable ignored) {
            return List.of();
        }
    }
}
