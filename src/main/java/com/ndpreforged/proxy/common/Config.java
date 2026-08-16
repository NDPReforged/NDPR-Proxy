package com.ndpreforged.proxy.common;

import com.ndpreforged.proxy.NdpConstants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置文件读写（对应 MCDR 版 init_config / validate_config / _save_config）。
 *
 * 兼容 TOML 与 YAML 的简单键值风格：
 *   key = value     key: value
 *   # 注释、空行；字符串可带引号；true/false/数字原样保留。
 * 首次启动时若不存在配置文件，则从内置模板生成。
 */
public final class Config {

    private static final Pattern KEY_LINE = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*[=:]\\s*(.*)$");

    private final Path path;
    private final List<String> lines = new ArrayList<>();
    private final Map<String, String> values = new LinkedHashMap<>();

    private Config(Path path) {
        this.path = path;
    }

    public Path path() {
        return path;
    }

    /**
     * 加载配置；文件不存在时从资源模板生成（config.toml）。
     */
    public static Config load(Path path, ClassLoader classLoader) throws IOException {
        Config cfg = new Config(path);
        if (!Files.exists(path)) {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (var in = classLoader.getResourceAsStream("config.template")) {
                if (in == null) {
                    throw new IOException("config.template resource missing");
                }
                Files.copy(in, path);
            }
        }
        cfg.read();
        return cfg;
    }

    private void read() throws IOException {
        lines.clear();
        values.clear();
        for (String rawLine : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            lines.add(rawLine);
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            Matcher m = KEY_LINE.matcher(line);
            if (m.matches()) {
                values.put(m.group(1), unquote(m.group(2).trim()));
            }
        }
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    public String raw(String key) {
        return values.get(key);
    }

    public String getString(String key, String def) {
        String v = values.get(key);
        return v == null || v.isEmpty() ? def : v;
    }

    public boolean getBool(String key, boolean def) {
        String v = values.get(key);
        if (v == null) {
            return def;
        }
        String t = v.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(t) || "yes".equals(t) || "1".equals(t)) {
            return true;
        }
        if ("false".equals(t) || "no".equals(t) || "0".equals(t)) {
            return false;
        }
        return def;
    }

    public int getInt(String key, int def) {
        String v = values.get(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 写入（或新增）一个键值，保留文件其余内容与注释 */
    public void set(String key, Object value) throws IOException {
        String valueStr;
        if (value instanceof Boolean) {
            valueStr = ((Boolean) value) ? "true" : "false";
        } else if (value instanceof Number) {
            valueStr = String.valueOf(value);
        } else {
            valueStr = "\"" + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        Pattern linePat = Pattern.compile("^(" + Pattern.quote(key) + "\\s*[=:]\\s*).*$");
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = linePat.matcher(lines.get(i));
            if (m.matches()) {
                lines.set(i, m.group(1) + valueStr);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            lines.add(key + " = " + valueStr);
        }
        Files.write(path, lines, StandardCharsets.UTF_8);
        values.put(key, valueStr);
    }

    /**
     * 校验配置完整性（对应 MCDR 版 validate_config）。
     * 校验失败抛出 ConfigException（message 为翻译后的错误文本）。
     */
    public void validate(Translations tr, String lang) {
        List<String> errors = new ArrayList<>();

        String apiUrl = getString("api_url", "");
        if (apiUrl.isEmpty()) {
            errors.add(tr.tr(lang, "ndpr.error.config.field", "field", "api_url"));
        } else if (!apiUrl.startsWith("http://") && !apiUrl.startsWith("https://")) {
            errors.add(tr.tr(lang, "ndpr.error.config.field_hint", "field", "api_url",
                    "hint", tr.tr(lang, "ndpr.hint.api_url_scheme")));
        }

        String onlinemode = raw("onlinemode");
        if (onlinemode == null || onlinemode.trim().isEmpty()) {
            errors.add(tr.tr(lang, "ndpr.error.config.onlinemode_missing"));
        } else if (!"true".equalsIgnoreCase(onlinemode.trim())
                && !"false".equalsIgnoreCase(onlinemode.trim())) {
            errors.add(tr.tr(lang, "ndpr.error.config.field", "field", "onlinemode"));
        }

        if (!has("token") || raw("token") == null) {
            errors.add(tr.tr(lang, "ndpr.error.config.field", "field", "token"));
        }

        if (!has("uuid") || raw("uuid") == null) {
            errors.add(tr.tr(lang, "ndpr.error.config.field", "field", "uuid"));
        }

        if (!has("log_path") || getString("log_path", "").isEmpty()) {
            errors.add(tr.tr(lang, "ndpr.error.config.field", "field", "log_path"));
        }

        String loggerMode = getString("logger_mode", "");
        if (!"default".equals(loggerMode) && !"custom".equals(loggerMode)) {
            errors.add(tr.tr(lang, "ndpr.error.config.field", "field", "logger_mode"));
        }

        if (getString("logger_format", "").isEmpty()) {
            errors.add(tr.tr(lang, "ndpr.error.config.field", "field", "logger_format"));
        }

        if (getInt("download_interval", -1) < 0) {
            errors.add(tr.tr(lang, "ndpr.error.config.field_hint", "field", "download_interval",
                    "hint", tr.tr(lang, "ndpr.hint.download_interval")));
        }

        if (raw("check_hwid") == null || (!"true".equalsIgnoreCase(raw("check_hwid").trim())
                && !"false".equalsIgnoreCase(raw("check_hwid").trim()))) {
            errors.add(tr.tr(lang, "ndpr.error.config.field_hint", "field", "check_hwid",
                    "hint", tr.tr(lang, "ndpr.hint.check_hwid")));
        }

        if (getInt("check_interval", -1) < 0) {
            errors.add(tr.tr(lang, "ndpr.error.config.field", "field", "check_interval"));
        }

        int verifyTimeout = getInt("verify_timeout", -1);
        if (verifyTimeout < 30 || verifyTimeout > 600) {
            errors.add(tr.tr(lang, "ndpr.error.config.field_hint", "field", "verify_timeout",
                    "hint", tr.tr(lang, "ndpr.hint.verify_timeout")));
        }

        int freezeInterval = getInt("freeze_interval", -1);
        if (freezeInterval < 1 || freezeInterval > 60) {
            errors.add(tr.tr(lang, "ndpr.error.config.field_hint", "field", "freeze_interval",
                    "hint", tr.tr(lang, "ndpr.hint.freeze_interval")));
        }

        String language = getString("language", "");
        if (language.isEmpty()) {
            errors.add(tr.tr(lang, "ndpr.error.config.field_hint", "field", "language",
                    "hint", tr.tr(lang, "ndpr.hint.language")));
        } else if (!tr.hasLanguage(language)) {
            errors.add(tr.tr(lang, "ndpr.error.config.field_hint", "field", "language",
                    "hint", tr.tr(lang, "ndpr.hint.language")));
        }

        if (!errors.isEmpty()) {
            throw new ConfigException("Error: " + String.join("; ", errors));
        }
    }

    public boolean onlineMode() {
        return getBool("onlinemode", false);
    }

    public String effectiveLanguage(Translations tr) {
        String rawLang = getString("language", NdpConstants.DEFAULT_LANGUAGE)
                .trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (tr.hasLanguage(rawLang)) {
            return rawLang;
        }
        String fallback = NdpConstants.DEFAULT_LANGUAGE.toLowerCase(Locale.ROOT);
        return tr.hasLanguage(fallback) ? fallback : rawLang;
    }

    public static final class ConfigException extends RuntimeException {
        public ConfigException(String message) {
            super(message);
        }
    }
}
