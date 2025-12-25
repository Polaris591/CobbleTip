package com.polaris.cobbletip.util;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class CobblemonZhCn {
    private CobblemonZhCn() {}

    private static final String RESOURCE = "assets/cobblemon/lang/zh_cn.json";
    private static volatile Map<String, String> ZH = null;
    private static final Object LOCK = new Object();

    public static String tr(String key, String fallback) {
        if (key == null || key.isBlank()) return fallback == null ? "" : fallback;
        String v = map().get(key);
        return v == null ? (fallback == null ? "" : fallback) : v;
    }

    public static String speciesNameFromSpeciesId(String speciesId, String fallback) {
        String path = idPath(speciesId);
        if (path.isBlank()) return fallback == null ? "" : fallback;
        return tr("cobblemon.species." + path + ".name", fallback);
    }

    private static String idPath(String idLike) {
        if (idLike == null) return "";
        String s = idLike.trim();
        if (s.isBlank()) return "";
        int dot = s.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < s.length()) s = s.substring(dot + 1);
        int colon = s.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < s.length()) s = s.substring(colon + 1);
        int slash = s.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < s.length()) s = s.substring(slash + 1);
        return s.trim().toLowerCase();
    }

    private static Map<String, String> map() {
        Map<String, String> m = ZH;
        if (m != null) return m;
        synchronized (LOCK) {
            if (ZH != null) return ZH;
            ZH = Collections.unmodifiableMap(loadZh());
            return ZH;
        }
    }

    private static Map<String, String> loadZh() {
        try (InputStream in = openResource()) {
            if (in == null) return Map.of();
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parseFlatJsonObject(json);
        } catch (Throwable ignored) {
            return Map.of();
        }
    }

    private static InputStream openResource() {
        try {
            InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(RESOURCE);
            if (in != null) return in;
        } catch (Throwable ignored) {}
        try {
            InputStream in = org.bukkit.Bukkit.getServer().getClass().getClassLoader().getResourceAsStream(RESOURCE);
            if (in != null) return in;
        } catch (Throwable ignored) {}
        try {
            return CobblemonZhCn.class.getClassLoader().getResourceAsStream(RESOURCE);
        } catch (Throwable ignored) {}
        return null;
    }

    private static Map<String, String> parseFlatJsonObject(String json) {
        if (json == null) return Map.of();
        int i = 0;
        int n = json.length();
        while (i < n && isWs(json.charAt(i))) i++;
        if (i >= n || json.charAt(i) != '{') return Map.of();
        i++;

        Map<String, String> out = new HashMap<>(4096);
        while (i < n) {
            while (i < n && isWs(json.charAt(i))) i++;
            if (i < n && json.charAt(i) == '}') break;

            String key = readJsonString(json, n, i);
            i = POS;
            if (key == null) break;

            while (i < n && isWs(json.charAt(i))) i++;
            if (i >= n || json.charAt(i) != ':') break;
            i++;

            while (i < n && isWs(json.charAt(i))) i++;
            String value = readJsonString(json, n, i);
            i = POS;
            if (value == null) break;
            out.put(key, value);

            while (i < n && isWs(json.charAt(i))) i++;
            if (i < n && json.charAt(i) == ',') {
                i++;
                continue;
            }
            if (i < n && json.charAt(i) == '}') break;
        }
        return out;
    }

    private static boolean isWs(char c) {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t';
    }

    private static int POS = 0;

    private static String readJsonString(String s, int n, int start) {
        int i = start;
        if (i >= n || s.charAt(i) != '"') return null;
        i++;
        StringBuilder sb = new StringBuilder(64);
        while (i < n) {
            char c = s.charAt(i++);
            if (c == '"') {
                POS = i;
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i >= n) break;
            char e = s.charAt(i++);
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (i + 3 >= n) break;
                    int code = hex4(s, i);
                    if (code >= 0) sb.append((char) code);
                    i += 4;
                }
                default -> sb.append(e);
            }
        }
        return null;
    }

    private static int hex4(String s, int i) {
        int c1 = hex(s.charAt(i));
        int c2 = hex(s.charAt(i + 1));
        int c3 = hex(s.charAt(i + 2));
        int c4 = hex(s.charAt(i + 3));
        if (c1 < 0 || c2 < 0 || c3 < 0 || c4 < 0) return -1;
        return (c1 << 12) | (c2 << 8) | (c3 << 4) | c4;
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return 10 + (c - 'a');
        if (c >= 'A' && c <= 'F') return 10 + (c - 'A');
        return -1;
    }
}
