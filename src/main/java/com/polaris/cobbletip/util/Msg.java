package com.polaris.cobbletip.util;

import org.bukkit.ChatColor;

import java.util.Map;

public final class Msg {
    private Msg() {}

    public static String color(String s) {
        if (s == null) return "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public static String apply(String s, TipConfig cfg, String... kv) {
        if (s == null) return "";
        String out = s.replace("{prefix}", cfg.msgPrefix());
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out = out.replace("{" + kv[i] + "}", kv[i + 1]);
        }
        return out;
    }
}
