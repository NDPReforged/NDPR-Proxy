package com.ndpreforged.proxy.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 内置翻译表（对应 MCDR 版 builtin_translations.py + lang/*.json）。
 * 从 jar 资源 /lang/zh_CN.json、/lang/en_us.json 加载。
 */
public final class Translations {

    private static final String[] LANGUAGE_FILES = {"zh_CN.json", "en_us.json"};
    private static final String DEFAULT_LANGUAGE = "zh_cn";

    private final Map<String, Map<String, String>> tables = new HashMap<>();

    private Translations() {
    }

    public static Translations load(ClassLoader classLoader, Logger logger) {
        Translations t = new Translations();
        for (String file : LANGUAGE_FILES) {
            String langKey = file.substring(0, file.length() - 5).toLowerCase(Locale.ROOT);
            Map<String, String> table = new HashMap<>();
            try (InputStream in = classLoader.getResourceAsStream("lang/" + file)) {
                if (in == null) {
                    logger.warning("Missing translation resource: lang/" + file);
                    continue;
                }
                JsonObject obj = JsonParser.parseReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        table.put(e.getKey(), e.getValue().getAsString());
                    }
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to load translation resource /lang/" + file, e);
            }
            t.tables.put(langKey, table);
        }
        return t;
    }

    public boolean hasLanguage(String lang) {
        return tables.containsKey(norm(lang));
    }

    public Set<String> languages() {
        return new HashSet<>(tables.keySet());
    }

    private static String norm(String lang) {
        return lang == null ? "" : lang.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    /**
     * 翻译，{var} 占位符以键值对形式传入：tr(lang, key, "player", name, "reason", r)
     */
    public String tr(String lang, String key, Object... kv) {
        Map<String, String> table = tables.get(norm(lang));
        String text = table == null ? null : table.get(key);
        if (text == null) {
            Map<String, String> def = tables.get(DEFAULT_LANGUAGE);
            if (def != null) {
                text = def.get(key);
            }
        }
        if (text == null) {
            text = key;
        }
        if (kv != null && kv.length > 0) {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                String k = String.valueOf(kv[i]);
                String v = String.valueOf(kv[i + 1]);
                text = text.replace("{" + k + "}", v == null ? "" : v);
            }
        }
        return text;
    }
}
