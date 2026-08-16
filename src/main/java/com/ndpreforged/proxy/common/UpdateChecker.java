package com.ndpreforged.proxy.common;

import java.util.List;
import java.util.Map;

/**
 * 更新检查（对应 MCDR 版 check_plugin_update）。
 * 查询 GitHub Releases 最新版本并比较。
 */
public final class UpdateChecker {

    private final ApiClient http;
    private final Config config;
    private final Translations translations;
    private final String lang;

    public UpdateChecker(ApiClient http, Config config, Translations translations, String lang) {
        this.http = http;
        this.config = config;
        this.translations = translations;
        this.lang = lang;
    }

    public static final class CheckResult {
        public final String latestVersion;
        public final boolean hasUpdate;
        public final String url;
        public final String notes;

        public CheckResult(String latestVersion, boolean hasUpdate, String url, String notes) {
            this.latestVersion = latestVersion;
            this.hasUpdate = hasUpdate;
            this.url = url;
            this.notes = notes;
        }
    }

    /** 检查更新；失败返回 null */
    public CheckResult check(String currentVersion) {
        try {
            String repo = config.getString("update_repo", com.ndpreforged.proxy.NdpConstants.DEFAULT_UPDATE_REPO);
            if (repo.isEmpty()) {
                return null;
            }
            String apiUrl = "https://api.github.com/repos/" + repo + "/releases/latest";
            var resp = http.get(apiUrl, Map.of("User-Agent", "NDPR-Proxy/" + currentVersion), 30);
            if (resp.statusCode() != 200) {
                return null;
            }
            var data = http.parse(resp.body());
            String tag = data.has("tag_name") ? data.get("tag_name").getAsString() : "";
            String latest = tag.replaceFirst("^[vV]", "");
            String url = data.has("html_url") ? data.get("html_url").getAsString() : "";
            String notes = data.has("body") ? data.get("body").getAsString() : "";
            boolean hasUpdate = compareVersions(latest, currentVersion) > 0;
            return new CheckResult(latest, hasUpdate, url, notes);
        } catch (Exception e) {
            return null;
        }
    }

    /** 渲染更新检查结果消息列表 */
    public List<String> render(CheckResult r, String currentVersion) {
        if (r == null) {
            return List.of("§c" + translations.tr(lang, "ndpr.reply.connection_error"));
        }
        if (!r.hasUpdate) {
            return List.of("§a" + translations.tr(lang, "ndpr.reply.up_to_date", "version", currentVersion));
        }
        List<String> messages = new java.util.ArrayList<>();
        messages.add("§a" + translations.tr(lang, "ndpr.reply.update_found"));
        messages.add("§a" + translations.tr(lang, "ndpr.reply.current_version", "version", currentVersion));
        messages.add("§a" + translations.tr(lang, "ndpr.reply.latest_version", "version", r.latestVersion));
        if (r.notes != null && !r.notes.isEmpty()) {
            String notes = r.notes.length() > 100 ? r.notes.substring(0, 100) + "..." : r.notes;
            messages.add("§a" + translations.tr(lang, "ndpr.reply.update_notes", "notes", notes));
        }
        messages.add("§a" + translations.tr(lang, "ndpr.reply.download_url", "url", r.url));
        return messages;
    }

    private static int compareVersions(String a, String b) {
        String[] ap = a.replaceFirst("^[vV]", "").split("\\.");
        String[] bp = b.replaceFirst("^[vV]", "").split("\\.");
        int len = Math.max(ap.length, bp.length);
        for (int i = 0; i < len; i++) {
            int av = i < ap.length ? parseIntSafe(ap[i]) : 0;
            int bv = i < bp.length ? parseIntSafe(bp[i]) : 0;
            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
