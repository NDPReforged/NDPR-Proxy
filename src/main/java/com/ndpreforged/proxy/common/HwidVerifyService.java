package com.ndpreforged.proxy.common;

import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HWID 设备验证（对应 MCDR 版 start_hwid_verify / _run_verify）。
 *
 * 代理层无法执行 tp / gamemode / effect 等后端指令，因此"冻结"降级为
 * **命令封锁**：验证期间玩家无法执行任何命令（仅放行登录类命令，
 * 如 /l /reg /login /register，可配置），超时未验证则断开连接。
 * 验证完成后放行并记录时间戳，与 MCDR 版共用同一套云端会话接口
 * （/hwid/upd、/hwid/upd/check、/hwid/has、/hwid/upd/cancel）。
 */
public final class HwidVerifyService {

    private final Platform platform;
    private final Config config;
    private final ApiClient http;
    private final Translations translations;
    private final JsonStore hwidTemp;
    private final String lang;
    private final Logger log;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private volatile Set<String> allowedCommands;

    public HwidVerifyService(Platform platform, Config config, ApiClient http,
                             Translations translations, JsonStore hwidTemp, String lang) {
        this.platform = platform;
        this.config = config;
        this.http = http;
        this.translations = translations;
        this.hwidTemp = hwidTemp;
        this.lang = lang;
        this.log = platform.logger();
    }

    /** 验证会话 */
    public static final class Session {
        public final String playerName;
        public final String ip;
        volatile boolean cancelled;
        volatile String sessionId;
        volatile long expiresAt;

        Session(String playerName, String ip) {
            this.playerName = playerName;
            this.ip = ip;
        }
    }

    private String tr(String key, Object... kv) {
        return translations.tr(lang, key, kv);
    }

    //-------------------------------------------------------------------------
    // 命令门控（验证期间冻结 = 禁止执行非登录命令）
    //-------------------------------------------------------------------------

    /** 玩家是否正在验证中 */
    public boolean isVerifying(String playerName) {
        return sessions.containsKey(playerName);
    }

    /**
     * 验证期间命令门控：返回 false 表示该命令应被拦截。
     *
     * @param command 原始命令字符串（可带前导 /）
     * @param isAdmin 是否为管理员（拥有 ndpr.admin，不受封锁）
     */
    public boolean gateCommand(String playerName, String command, boolean isAdmin) {
        if (isAdmin || !isVerifying(playerName) || command == null) {
            return true;
        }
        String c = command.startsWith("/") ? command.substring(1) : command;
        String first = c.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (first.isEmpty()) {
            return true;
        }
        // 放行本插件命令（如管理员不在线时 console 使用不受影响；玩家侧 /ndpr 亦允许）
        if (first.equals(com.ndpreforged.proxy.NdpConstants.MAIN_COMMAND)) {
            return true;
        }
        return allowedCommands().contains(first);
    }

    /** 验证期间允许执行的命令首词集合（配置 hwid_allowed_commands，逗号分隔） */
    public Set<String> allowedCommands() {
        Set<String> cached = allowedCommands;
        if (cached != null) {
            return cached;
        }
        Set<String> set = new HashSet<>();
        String raw = config.getString("hwid_allowed_commands", "l,reg,login,register");
        for (String part : raw.split(",")) {
            String t = part.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) {
                set.add(t);
            }
        }
        set.add(com.ndpreforged.proxy.NdpConstants.MAIN_COMMAND);
        allowedCommands = set;
        return set;
    }

    /** 配置重载后调用（清除命令白名单缓存） */
    public void invalidateCache() {
        allowedCommands = null;
    }

    /**
     * 启动（或强制启动）设备验证。
     *
     * @param force true 时忽略 check_hwid 开关与最近验证记录（对应 !!ndpr auth）
     */
    public void start(Platform.ProxyPlayer player, boolean force) {
        if (!force && !config.getBool("check_hwid", false)) {
            return;
        }
        String token = config.getString("token", "");
        if (token.isEmpty()) {
            log.warning(tr("ndpr.warn.token_missing_hwid"));
            return;
        }

        if (!force) {
            Map<String, Object> records = hwidTemp.readAll();
            Object rec = records.get(player.name());
            if (rec instanceof Map<?, ?>) {
                Object t = ((Map<?, ?>) rec).get("time");
                double last = toEpochSeconds(t);
                int intervalDays = config.getInt("check_interval", 3);
                if (System.currentTimeMillis() / 1000.0 - last < intervalDays * 86400L) {
                    return;
                }
            }
        }

        Session session = new Session(player.name(), player.ipv4());
        Session old = sessions.put(player.name(), session);
        if (old != null) {
            old.cancelled = true;
        }
        platform.runAsync(() -> runVerify(player, session, token));
    }

    /** 玩家离开：取消进行中的验证 */
    public void onLeave(Platform.ProxyPlayer player) {
        Session session = sessions.get(player.name());
        if (session != null) {
            session.cancelled = true;
            if (session.sessionId != null && !session.sessionId.isEmpty()) {
                platform.runAsync(() -> cancelApiSession(session.sessionId));
            }
        }
    }

    /** 插件卸载：取消全部会话 */
    public void shutdown() {
        sessions.values().forEach(s -> s.cancelled = true);
        sessions.clear();
    }

    private void runVerify(Platform.ProxyPlayer player, Session session, String token) {
        try {
            JsonObject created = createSession(player, token);
            if (created == null) {
                kick(player, tr("ndpr.kick.verify_unavailable"), false);
                return;
            }
            session.sessionId = str(created, "session_id");
            long now = System.currentTimeMillis();
            long rawExpires = created.has("expires_at") ? created.get("expires_at").getAsLong() * 1000L : 0L;
            long verifyTimeout = config.getInt("verify_timeout", 60) * 1000L;
            long expiresAt = Math.min(Math.max(rawExpires, now + 30_000L), now + verifyTimeout);
            session.expiresAt = expiresAt;
            String verifyUrl = str(created, "verify_url");

            platform.sendMessage(player, "§e" + tr("ndpr.tell.click_verify"));
            platform.sendMessage(player, RichMessage.clickable(verifyUrl,
                    RichMessage.Action.OPEN_URL, verifyUrl,
                    "§a" + tr("ndpr.hover.open_verify_page")));
            platform.sendMessage(player, "§7" + tr("ndpr.tell.verify_freeze_notice"));
            platform.sendTitle(player, tr("ndpr.title.verify"), tr("ndpr.subtitle.verify"));

            while (!session.cancelled && System.currentTimeMillis() < expiresAt) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                JsonObject status = checkStatus(session.sessionId, token);
                if (status == null) {
                    continue;
                }
                if (bool(status, "completed")) {
                    boolean banned;
                    String reason;
                    if (status.has("banned")) {
                        banned = bool(status, "banned");
                        reason = str(status, "reason");
                    } else {
                        JsonObject has = queryHas(player, token);
                        if (has == null) {
                            kick(player, tr("ndpr.kick.hwid_status_unknown"), false);
                            return;
                        }
                        banned = bool(has, "banned");
                        reason = str(has, "reason");
                    }
                    if (banned) {
                        String r = (reason == null || reason.isEmpty()) ? tr("ndpr.word.hwid_banned") : reason;
                        kick(player, tr("ndpr.kick.banned_with_reason", "reason", r), true);
                    } else {
                        saveHwidPass(player, session);
                        platform.sendMessage(player, "§a" + tr("ndpr.tell.verify_done"));
                    }
                    return;
                }
                String st = str(status, "status");
                if ("cancelled".equals(st)) {
                    return;
                }
                if ("expired".equals(st)) {
                    break;
                }
            }
            if (!session.cancelled) {
                kick(player, tr("ndpr.kick.verify_timeout"), false);
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "HWID verify loop error for " + player.name(), e);
        } finally {
            sessions.remove(player.name(), session);
        }
    }

    private JsonObject createSession(Platform.ProxyPlayer player, String token) {
        JsonObject payload = new JsonObject();
        payload.addProperty("player_id", player.name());
        String ip = player.ipv4();
        if (ip != null && !ip.isEmpty()) {
            payload.addProperty("ip", ip);
        }
        try {
            var resp = http.postJson(config.getString("api_url", "") + "/hwid/upd",
                    auth(token), payload, 10);
            return resp.statusCode() == 200 ? http.parse(resp.body()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private JsonObject checkStatus(String sessionId, String token) {
        JsonObject payload = new JsonObject();
        payload.addProperty("session_id", sessionId);
        try {
            var resp = http.postJson(config.getString("api_url", "") + "/hwid/upd/check",
                    auth(token), payload, 3);
            return resp.statusCode() == 200 ? http.parse(resp.body()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private JsonObject queryHas(Platform.ProxyPlayer player, String token) {
        JsonObject payload = new JsonObject();
        payload.addProperty("player_id", player.name());
        try {
            var resp = http.postJson(config.getString("api_url", "") + "/hwid/has",
                    auth(token), payload, 5);
            return resp.statusCode() == 200 ? http.parse(resp.body()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void cancelApiSession(String sessionId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("session_id", sessionId);
        try {
            http.postJson(config.getString("api_url", "") + "/hwid/upd/cancel",
                    auth(config.getString("token", "")), payload, 5);
        } catch (Exception ignored) {
        }
    }

    private void saveHwidPass(Platform.ProxyPlayer player, Session session) {
        try {
            Map<String, Object> records = hwidTemp.readAll();
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("ip", session.ip == null ? "" : session.ip);
            entry.put("time", System.currentTimeMillis() / 1000.0);
            records.put(player.name(), entry);
            hwidTemp.writeAll(records);
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to save hwid record for " + player.name(), e);
        }
    }

    private void kick(Platform.ProxyPlayer player, String reason, boolean report) {
        platform.disconnect(player, "§c" + reason);
        log.info(tr("ndpr.log.kick_hwid", "player", player.name(), "reason", reason));
        if (report) {
            reportKick();
        }
    }

    /** 上报拦截统计（对应 MCDR 版 report_kick） */
    private void reportKick() {
        try {
            String url = config.getString("api_url", "") + "/stats/a";
            http.postJson(url, auth(config.getString("token", "")), new JsonObject(), 5);
        } catch (Exception ignored) {
        }
    }

    private java.util.Map<String, String> auth(String token) {
        return java.util.Map.of("Authorization", "Bearer " + token);
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static boolean bool(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsBoolean();
    }

    private static double toEpochSeconds(Object v) {
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof com.google.gson.JsonPrimitive) {
            try {
                return ((com.google.gson.JsonPrimitive) v).getAsDouble();
            } catch (RuntimeException e) {
                return 0;
            }
        }
        return 0;
    }
}
