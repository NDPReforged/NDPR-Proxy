package com.ndpreforged.proxy.common;

import com.google.gson.JsonObject;
import com.ndpreforged.proxy.NdpConstants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * NDPR 代理端核心（对应 MCDR 版 ndpr/__init__.py 的全部逻辑）。
 *
 * 生命周期：init() → 配置/翻译/UUID/封禁库/更新检查/定时任务
 * 事件：onPlayerJoin / onPlayerLeave
 * 命令：/ndpr help|d|ban|check|reload|cu|auth
 *
 * 与 MCDR 版的差异（代理架构天然优势）：
 *  - 玩家 IP / IPv6 / UUID 直接来自代理连接，无需解析服务器日志；
 *  - 踢出通过代理 API 直接断开连接，无需执行后端 kick 命令；
 *  - HWID 验证的 freeze（tp/gamemode/effect）代理层无法执行，降级为提示+超时踢出。
 */
public final class NdpPlugin {

    private final Platform platform;
    private final Logger log;
    private final Translations translations;
    private final Path dataDir;
    private final ApiClient http = new ApiClient();
    private final AtomicBoolean downloadInflight = new AtomicBoolean(false);
    private final java.util.concurrent.locks.ReentrantLock downloadLock = new java.util.concurrent.locks.ReentrantLock();

    private Config config;
    private BanDatabase banDb;
    private JsonStore playerInfo;
    private JsonStore hwidTemp;
    private HwidVerifyService hwidService;
    private Platform.ScheduledTask downloadTask;
    private String lang = "zh_cn";
    private boolean configBroken = true;

    public NdpPlugin(Platform platform) {
        this.platform = platform;
        this.log = platform.logger();
        this.dataDir = platform.dataDir();
        this.translations = Translations.load(getClass().getClassLoader(), log);
    }

    //-------------------------------------------------------------------------
    // 生命周期
    //-------------------------------------------------------------------------

    /** 初始化（配置校验失败时记录错误，可通过 /ndpr reload 修复后重载） */
    public synchronized void init() {
        reloadConfig();
        platform.runAsync(this::asyncInit);
    }

    /**
     * 重新加载配置并重建服务（同步，不触发异步初始化）。
     * 供 init 与 /ndpr reload 共用；reload 不得调用 init()，
     * 否则 init 的异步初始化会与 reload 的下载并发。
     */
    public synchronized void reloadConfig() {
        try {
            Config newConfig = Config.load(dataDir.resolve(NdpConstants.CONFIG_FILE),
                    getClass().getClassLoader());
            // 先赋值：即使校验失败（如 onlinemode 未填），api_url 等默认值依然可用，
            // 保证首次启动也能自动获取 UUID
            this.config = newConfig;
            this.lang = newConfig.effectiveLanguage(translations);
            newConfig.validate(translations, this.lang);
            this.configBroken = false;
        } catch (Exception e) {
            this.configBroken = true;
            log.log(Level.SEVERE, "NDPR config load failed: " + e.getMessage(), e);
        }

        // 数据目录与服务初始化（即使配置校验失败也执行，UUID 获取不依赖 onlinemode）
        if (config != null) {
            Path data = dataDir.resolve(NdpConstants.DATA_DIR);
            try {
                Files.createDirectories(data);
            } catch (IOException e) {
                log.log(Level.SEVERE, "Failed to create data dir", e);
            }
            this.banDb = new BanDatabase(data.resolve(NdpConstants.BAN_DB_FILE));
            this.playerInfo = new JsonStore(data.resolve(NdpConstants.PLAYER_INFO_FILE));
            this.hwidTemp = new JsonStore(data.resolve(NdpConstants.HWID_TEMP_FILE));
            this.hwidService = new HwidVerifyService(platform, config, http, translations, hwidTemp, lang);

            if (!configBroken) {
                String serverType = config.onlineMode() ? tr("ndpr.word.online") : tr("ndpr.word.offline");
                log.info(tr("ndpr.log.server_type", "type", serverType));
            }
            log.info(tr("ndpr.log.uuid", "uuid",
                    config.getString("uuid", "").isEmpty() ? tr("ndpr.word.unset") : config.getString("uuid", "")));
        }
    }

    private void asyncInit() {
        if (config == null) {
            return;
        }
        // 1. UUID 获取（独立于配置校验：首次启动 onlinemode 未填写时同样自动获取）
        if (config.getString("uuid", "").isEmpty()) {
            try {
                obtainUuid();
            } catch (Exception e) {
                log.warning(tr("ndpr.error.init_stage_failed", "stage", tr("ndpr.word.stage_uuid"), "error", e.getMessage()));
            }
        }
        if (configBroken) {
            log.warning("NDPR 配置不完整（如 onlinemode 未填写），UUID 已自动获取；请填写 config.toml 后执行 /ndpr reload");
            return;
        }
        // 2. 封禁库下载
        try {
            downloadBanDatabase(null);
        } catch (Exception e) {
            log.warning(tr("ndpr.error.init_stage_failed", "stage", tr("ndpr.word.stage_db"), "error", e.getMessage()));
        }
        // 3. 更新检查
        try {
            checkUpdateSilently();
        } catch (Exception ignored) {
        }
        // 4. 定时下载
        restartDownloadTask();
        log.info(tr("ndpr.log.init_done"));
    }

    /** 卸载 */
    public void shutdown() {
        if (downloadTask != null) {
            downloadTask.cancel();
            downloadTask = null;
        }
        if (hwidService != null) {
            hwidService.shutdown();
        }
        log.info(tr("ndpr.log.unloaded"));
    }

    /** 翻译（平台层构造拦截提示消息时亦使用） */
    public String tr(String key, Object... kv) {
        return translations.tr(lang, key, kv);
    }

    /** 平台命令拦截器调用：验证期间禁止非白名单命令 */
    public boolean gateCommand(String playerName, String command, boolean isAdmin) {
        return hwidService == null || hwidService.gateCommand(playerName, command, isAdmin);
    }

    //-------------------------------------------------------------------------
    // 事件
    //-------------------------------------------------------------------------

    /** 玩家加入（对应 MCDR 版 on_player_joined） */
    public void onPlayerJoin(Platform.ProxyPlayer p) {
        if (configBroken || config == null || banDb == null) {
            return;
        }
        String ipv4 = p.ipv4();
        String ipv6 = p.ipv6();
        String uuid = p.uniqueId() == null ? null : p.uniqueId().toString();
        savePlayerInfo(p.name(), ipv4, uuid, ipv6);
        log.info(tr("ndpr.log.player_info", "player", p.name(), "ip", String.valueOf(ipv4),
                "uuid", String.valueOf(uuid), "ipv6", String.valueOf(ipv6)));

        String initKick = "§c" + tr("ndpr.kick.system_initializing");

        if (!banDb.exists()) {
            log.warning(tr("ndpr.warn.db_missing"));
            downloadBanDatabaseAsync(null);
            if (config.getBool("fail_closed", false)) {
                log.warning(tr("ndpr.warn.fail_closed_rejected", "player", p.name()));
                platform.disconnect(p, initKick);
                return;
            }
            log.warning(tr("ndpr.warn.fail_open_allowed", "player", p.name()));
        } else {
            try {
                BanDatabase.BanRecord rec = banDb.findBan(p.name(), uuid, ipv4, ipv6);
                if (rec != null) {
                    log.info(tr("ndpr.log.banned_detected", "player", p.name(), "table", rec.table));
                    platform.disconnect(p, "§c" + tr("ndpr.kick.banned"));
                    reportKick();
                    return;
                }
            } catch (Exception e) {
                if (config.getBool("fail_closed", false)) {
                    log.warning(tr("ndpr.warn.fail_closed_query_error", "player", p.name(), "error", e.getMessage()));
                    platform.disconnect(p, initKick);
                    return;
                }
                log.warning(tr("ndpr.warn.fail_open_query_error", "player", p.name(), "error", e.getMessage()));
            }
        }

        if (config.getBool("check_hwid", false)) {
            hwidService.start(p, false);
        }
    }

    /** 玩家离开（对应 MCDR 版 on_player_left） */
    public void onPlayerLeave(Platform.ProxyPlayer p) {
        if (hwidService != null) {
            hwidService.onLeave(p);
        }
    }

    private void savePlayerInfo(String player, String ip, String uuid, String ipv6) {
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ip", ip);
            entry.put("uuid", uuid);
            entry.put("ipv6", ipv6);
            entry.put("timestamp", System.currentTimeMillis() / 1000.0);
            playerInfo.writeEntry(player, entry);
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to save player info for " + player, e);
        }
    }

    //-------------------------------------------------------------------------
    // 命令
    //-------------------------------------------------------------------------

    public void handleCommand(Platform.NdpSource src, String[] args) {
        if (args == null || args.length == 0 || args[0].isEmpty()) {
            help(src);
            return;
        }
        switch (args[0].toLowerCase()) {
            case "help":
                help(src);
                break;
            case "d":
            case "download":
                requireAdmin(src, () -> cmdDownload(src));
                break;
            case "ban":
                requireAdmin(src, () -> cmdBan(src, args));
                break;
            case "check":
                cmdCheck(src, args);
                break;
            case "reload":
                requireAdmin(src, () -> cmdReload(src));
                break;
            case "cu":
            case "checkupdate":
                requireAdmin(src, () -> cmdCheckUpdate(src));
                break;
            case "auth":
                requireAdmin(src, () -> cmdAuth(src, args));
                break;
            default:
                help(src);
        }
    }

    public List<String> suggest(Platform.NdpSource src, String[] args) {
        List<String> subs = new ArrayList<>(List.of("help", "d", "download", "check", "ban", "reload", "cu", "checkupdate", "auth"));
        if (args.length <= 1) {
            List<String> out = new ArrayList<>();
            for (String s : subs) {
                if (!src.isAdmin() && !"check".equals(s) && !"help".equals(s)) {
                    continue;
                }
                if (args.length == 1 && !s.startsWith(args[0].toLowerCase())) {
                    continue;
                }
                out.add(s);
            }
            return out;
        }
        String sub = args[0].toLowerCase();
        if (("ban".equals(sub) || "auth".equals(sub) || "check".equals(sub)) && src.isAdmin()
                || ("check".equals(sub))) {
            String prefix = args.length >= 2 ? args[1].toLowerCase() : "";
            List<String> out = new ArrayList<>();
            for (Platform.ProxyPlayer p : platform.players()) {
                if (p.name().toLowerCase().startsWith(prefix)) {
                    out.add(p.name());
                }
            }
            return out;
        }
        return List.of();
    }

    private void requireAdmin(Platform.NdpSource src, Runnable action) {
        if (!src.isAdmin()) {
            src.reply("§c" + tr("ndpr.reply.permission_denied"));
            return;
        }
        action.run();
    }

    private void help(Platform.NdpSource src) {
        src.reply("§6========== §b" + tr("ndpr.help.title") + " §6==========");
        src.reply("§e" + tr("ndpr.help.version", "version", NdpConstants.VERSION));
        src.reply("§e" + tr("ndpr.help.author"));
        src.reply(tr("ndpr.help.qq_group"));
        src.reply("");
        src.reply("§b" + tr("ndpr.help.commands"));
        src.reply("§f/" + NdpConstants.MAIN_COMMAND + " help §7- " + tr("ndpr.help.desc.help"));
        src.reply("§f/" + NdpConstants.MAIN_COMMAND + " d / download §7- " + tr("ndpr.help.desc.download"));
        src.reply("§f/" + NdpConstants.MAIN_COMMAND + " ban <ID> <reason> §7- " + tr("ndpr.help.desc.ban"));
        src.reply("§f/" + NdpConstants.MAIN_COMMAND + " check <ID/IP/UUID> §7- " + tr("ndpr.help.desc.check"));
        src.reply("§f/" + NdpConstants.MAIN_COMMAND + " reload §7- " + tr("ndpr.help.desc.reload"));
        src.reply("§f/" + NdpConstants.MAIN_COMMAND + " cu / checkupdate §7- " + tr("ndpr.help.desc.checkupdate"));
        src.reply("§f/" + NdpConstants.MAIN_COMMAND + " auth <ID> §7- " + tr("ndpr.help.desc.auth"));
        src.reply("");
        src.reply(tr("ndpr.help.footer"));
    }

    private void cmdDownload(Platform.NdpSource src) {
        if (configBroken || config == null) {
            src.reply("§c" + tr("ndpr.reply.config_not_loaded"));
            return;
        }
        src.reply("§e" + tr("ndpr.reply.downloading"));
        downloadBanDatabaseAsync(src);
    }

    private void cmdReload(Platform.NdpSource src) {
        src.reply("§e" + tr("ndpr.reply.reloading"));
        platform.runAsync(() -> {
            try {
                // 只重载配置（不调用 init()，避免其异步初始化与本次下载并发写同一临时文件）
                reloadConfig();
                if (configBroken) {
                    src.reply("§c" + tr("ndpr.reply.reload_failed", "error", tr("ndpr.error.config.onlinemode_missing")));
                    return;
                }
                downloadBanDatabase(src);
                restartDownloadTask();
                src.reply("§a" + tr("ndpr.reply.reloaded"));
            } catch (Exception e) {
                src.reply("§c" + tr("ndpr.reply.reload_failed", "error", e.getMessage()));
            }
        });
    }

    private void cmdCheckUpdate(Platform.NdpSource src) {
        src.reply("§a" + tr("ndpr.reply.checking_update"));
        platform.runAsync(() -> {
            UpdateChecker checker = new UpdateChecker(http, config, translations, lang);
            UpdateChecker.CheckResult result = checker.check(NdpConstants.VERSION);
            for (String line : checker.render(result, NdpConstants.VERSION)) {
                src.reply(line);
            }
        });
    }

    /** 启动时的静默更新检查（仅写日志） */
    private void checkUpdateSilently() {
        UpdateChecker checker = new UpdateChecker(http, config, translations, lang);
        UpdateChecker.CheckResult result = checker.check(NdpConstants.VERSION);
        if (result != null && result.hasUpdate) {
            log.info(tr("ndpr.log.update_found", "latest", result.latestVersion,
                    "current", NdpConstants.VERSION, "url", result.url));
        }
    }

    private void cmdCheck(Platform.NdpSource src, String[] args) {
        if (configBroken || config == null || banDb == null) {
            src.reply("§c" + tr("ndpr.reply.config_not_loaded"));
            return;
        }
        if (args.length < 2) {
            src.reply("§7" + tr("ndpr.reply.ban_usage"));
            return;
        }
        String target = args[1];
        String type;
        if (NetUtil.isIpv4(target)) {
            type = "ip";
        } else if (NetUtil.isIpv6(target)) {
            type = "ipv6";
        } else if (NetUtil.isUuid(target)) {
            type = "uuid";
        } else {
            type = "id";
        }
        platform.runAsync(() -> {
            if (!banDb.exists()) {
                src.reply("§c" + tr("ndpr.reply.no_data"));
                return;
            }
            try {
                BanDatabase.BanRecord rec;
                if ("id".equals(type)) {
                    rec = banDb.findBan(target, null, null, null);
                } else {
                    rec = banDb.findByIdentifier(type, target);
                }
                if (rec != null) {
                    src.reply("§7" + tr("ndpr.label.player", "player", rec.player));
                    src.reply("§7" + tr("ndpr.label.reason", "reason", String.valueOf(rec.reason)));
                    src.reply("§7" + tr("ndpr.label.ban_time", "time", String.valueOf(rec.time)));
                } else {
                    if ("id".equals(type)) {
                        src.reply("§a" + tr("ndpr.reply.not_banned", "player", target));
                        fuzzySuggest(src, target);
                    } else {
                        src.reply(tr("ndpr.reply.record_not_found", "type", type, "value", target));
                    }
                }
            } catch (Exception e) {
                src.reply("§c" + tr("ndpr.reply.query_failed", "error", e.getMessage()));
            }
        });
    }

    private void fuzzySuggest(Platform.NdpSource src, String query) {
        try {
            List<String> matches = banDb.fuzzyNames(query, 5);
            if (matches.isEmpty()) {
                return;
            }
            src.reply("§7" + tr("ndpr.reply.fuzzy_suggestion"));
            for (String name : matches) {
                String safe = name.contains(" ") ? "\"" + name + "\"" : name;
                // 点击直接执行查询命令（RUN_COMMAND，而非填充到聊天栏）
                src.reply(RichMessage.clickable(
                        "§8" + name + " §7[" + tr("ndpr.reply.fuzzy_expand") + "]",
                        RichMessage.Action.RUN_COMMAND, "/" + NdpConstants.MAIN_COMMAND + " check " + safe,
                        "§a" + tr("ndpr.reply.fuzzy_hover", "player", name)));
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "fuzzy suggest failed", e);
        }
    }

    private void cmdBan(Platform.NdpSource src, String[] args) {
        if (configBroken || config == null) {
            src.reply("§c" + tr("ndpr.reply.config_not_loaded"));
            return;
        }
        if (args.length < 2) {
            src.reply("§7" + tr("ndpr.reply.ban_usage"));
            return;
        }
        String player = args[1];
        String reason = args.length >= 3 ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)) : "";
        if (reason.isEmpty()) {
            src.reply("§c" + tr("ndpr.reply.ban_reason_required"));
            src.reply("§7" + tr("ndpr.reply.ban_usage"));
            return;
        }
        String token = config.getString("token", "");
        if (token.isEmpty()) {
            src.reply("§c" + tr("ndpr.reply.token_not_configured"));
            return;
        }
        if (config.getString("api_url", "").isEmpty()) {
            src.reply("§c" + tr("ndpr.reply.api_not_configured"));
            return;
        }

        src.reply("§e" + tr("ndpr.reply.getting_player_info", "player", player));
        platform.runAsync(() -> {
            try {
                String ip = null;
                String ipv6 = null;
                String uuid = null;
                Optional<Platform.ProxyPlayer> online = platform.player(player);
                if (online.isPresent()) {
                    ip = online.get().ipv4();
                    ipv6 = online.get().ipv6();
                    uuid = online.get().uniqueId() == null ? null : online.get().uniqueId().toString();
                } else if (playerInfo != null && playerInfo.exists()) {
                    Map<String, Object> info = playerInfo.readEntry(player);
                    ip = strVal(info.get("ip"));
                    ipv6 = strVal(info.get("ipv6"));
                    uuid = strVal(info.get("uuid"));
                }
                if (ip == null && ipv6 == null && uuid == null) {
                    src.reply("§c" + tr("ndpr.reply.player_info_not_found"));
                    src.reply("§7" + tr("ndpr.reply.player_info_hint"));
                    return;
                }
                List<String> infoParts = new ArrayList<>();
                if (ip != null) {
                    infoParts.add("IP: " + ip);
                }
                if (ipv6 != null) {
                    infoParts.add("IPv6: " + ipv6);
                }
                if (uuid != null) {
                    infoParts.add("UUID: " + uuid);
                }
                src.reply("§e" + tr("ndpr.reply.info_obtained", "info", String.join(", ", infoParts)));
                src.reply("§e" + tr("ndpr.reply.ban_reason_echo", "reason", reason));

                JsonObject data = new JsonObject();
                data.addProperty("player_id", player);
                if (ip != null) {
                    data.addProperty("ip", ip);
                }
                if (ipv6 != null) {
                    data.addProperty("ipv6", ipv6);
                }
                if (uuid != null) {
                    data.addProperty("uuid", uuid);
                }
                data.addProperty("onlinemode", config.onlineMode());
                data.addProperty("reason", reason);

                src.reply("§e" + tr("ndpr.reply.submitting"));
                var resp = http.postJson(config.getString("api_url", "") + "/check/uploader",
                        Map.of("Authorization", "Bearer " + token), data, 10);

                if (resp.statusCode() == 200) {
                    JsonObject result = http.parse(resp.body());
                    if ("success".equals(strVal(result.get("result")))) {
                        String checkId = strVal(result.get("check_id"));
                        src.reply("§a" + tr("ndpr.reply.submit_success"));
                        src.reply("§7" + tr("ndpr.reply.check_id", "check_id", String.valueOf(checkId)));
                        src.reply("§7" + tr("ndpr.reply.wait_review"));
                    } else {
                        String message = strVal(result.get("message"));
                        src.reply("§c" + tr("ndpr.reply.submit_failed",
                                "message", message == null ? tr("ndpr.reply.unknown_error") : message));
                    }
                } else if (resp.statusCode() == 403) {
                    src.reply("§c" + tr("ndpr.reply.no_upload_permission"));
                } else {
                    src.reply("§c" + tr("ndpr.reply.submit_failed_http", "code", resp.statusCode()));
                    JsonObject err = http.parse(resp.body());
                    String errorText = strVal(err.get("error"));
                    if (errorText != null) {
                        src.reply("§7" + tr("ndpr.reply.error_info", "error", errorText));
                    } else {
                        String body = resp.body();
                        src.reply("§7" + tr("ndpr.reply.response_body",
                                "body", body == null ? "" : body.substring(0, Math.min(200, body.length()))));
                    }
                }
            } catch (java.net.http.HttpTimeoutException e) {
                src.reply("§c" + tr("ndpr.reply.timeout"));
            } catch (java.net.ConnectException e) {
                src.reply("§c" + tr("ndpr.reply.connection_error"));
            } catch (Exception e) {
                src.reply("§c" + tr("ndpr.reply.submit_failed_http", "code", "ERR") + " " + e.getMessage());
            }
        });
    }

    private void cmdAuth(Platform.NdpSource src, String[] args) {
        if (configBroken || config == null || hwidService == null) {
            src.reply("§c" + tr("ndpr.reply.config_not_loaded"));
            return;
        }
        if (args.length < 2) {
            src.reply("§7" + tr("ndpr.reply.auth_usage"));
            return;
        }
        String player = args[1];
        Optional<Platform.ProxyPlayer> online = platform.player(player);
        if (online.isEmpty()) {
            src.reply("§7" + tr("ndpr.reply.auth_player_not_online", "player", player));
            return;
        }
        src.reply("§e" + tr("ndpr.reply.auth_starting", "player", player));
        hwidService.start(online.get(), true);
    }

    //-------------------------------------------------------------------------
    // 封禁库下载
    //-------------------------------------------------------------------------

    private void downloadBanDatabaseAsync(Platform.NdpSource src) {
        if (!downloadInflight.compareAndSet(false, true)) {
            if (src != null) {
                src.reply("§e" + tr("ndpr.reply.download_inflight"));
            }
            return;
        }
        platform.runAsync(() -> {
            try {
                downloadBanDatabase(src);
            } catch (Exception e) {
                log.log(Level.WARNING, "Ban database download failed: " + e.getMessage(), e);
            } finally {
                downloadInflight.set(false);
            }
        });
    }

    /**
     * 下载封禁数据库（对应 MCDR 版 download_ban_database）。
     * 流程：GET /bans/download 取文件 URL → 下载 SQLite 文件 → 校验结构 → 原子替换 → 上报完成。
     */
    private void downloadBanDatabase(Platform.NdpSource src) throws Exception {
        // 串行化下载：init 异步初始化 / reload / 定时任务 / 玩家加入 可能并发触发，
        // 并发写同一 .tmp 文件会导致 Files.move 失败（NoSuchFile）
        downloadLock.lock();
        try {
            downloadBanDatabaseLocked(src);
        } finally {
            downloadLock.unlock();
        }
    }

    private void downloadBanDatabaseLocked(Platform.NdpSource src) throws Exception {
        if (configBroken || config == null) {
            return;
        }
        String token = config.getString("token", "");
        if (token.isEmpty()) {
            String msg = tr("ndpr.warn.token_missing");
            log.warning(msg);
            if (src != null) {
                src.reply("§c" + msg);
            }
            return;
        }
        String apiUrl = config.getString("api_url", "");
        if (apiUrl.isEmpty()) {
            String msg = tr("ndpr.error.db_api_unconfigured");
            log.severe(msg);
            if (src != null) {
                src.reply("§c" + msg);
            }
            return;
        }

        var resp = http.get(apiUrl + "/bans/download", Map.of("Authorization", "Bearer " + token), 30);
        if (resp.statusCode() != 200) {
            String msg = tr("ndpr.error.db_download_http", "code", resp.statusCode(), "body", resp.body());
            log.severe(msg);
            if (src != null) {
                src.reply("§c" + msg);
            }
            return;
        }
        JsonObject data = http.parse(resp.body());
        String downloadUrl = strVal(data.get("url"));
        if (downloadUrl == null) {
            String msg = tr("ndpr.error.db_download_no_url");
            log.severe(msg);
            if (src != null) {
                src.reply("§c" + msg);
            }
            return;
        }

        var fileResp = http.getBytes(downloadUrl, null, 60);
        if (fileResp.statusCode() != 200) {
            String msg = tr("ndpr.error.db_file_download_http", "code", fileResp.statusCode());
            log.severe(msg);
            if (src != null) {
                src.reply("§c" + msg);
            }
            return;
        }

        Path dbPath = banDb.path();
        Path tmpPath = dbPath.resolveSibling(dbPath.getFileName() + ".tmp");
        Files.write(tmpPath, fileResp.body());

        int count;
        try {
            count = new BanDatabase(tmpPath).countAll();
        } catch (Exception e) {
            Files.deleteIfExists(tmpPath);
            throw new IllegalStateException(tr("ndpr.error.db_file_invalid", "error", e.getMessage()));
        }

        try {
            Files.move(tmpPath, dbPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.deleteIfExists(tmpPath);
            throw e;
        }
        banDb = new BanDatabase(dbPath);

        String detail = tr("ndpr.log.db_updated", "count", count);
        log.info(detail);
        if (src != null) {
            src.reply("§a" + tr("ndpr.reply.db_download_success"));
            src.reply("§7" + detail);
        }

        try {
            http.postJson(apiUrl + "/bans/download/done",
                    Map.of("Authorization", "Bearer " + token), new JsonObject(), 10);
        } catch (Exception ignored) {
        }
    }

    //-------------------------------------------------------------------------
    // UUID / 统计 / 定时任务
    //-------------------------------------------------------------------------

    private void obtainUuid() throws Exception {
        String apiUrl = config.getString("api_url", "");
        if (apiUrl.isEmpty()) {
            throw new IllegalStateException(tr("ndpr.error.api_url_missing"));
        }
        log.info(tr("ndpr.log.getting_uuid"));
        var resp = http.postJson(apiUrl + "/uuid/getuuid", null, new JsonObject(), 10);
        if (resp.statusCode() != 200) {
            throw new IllegalStateException(tr("ndpr.error.get_uuid_http", "code", resp.statusCode(), "body", resp.body()));
        }
        JsonObject data = http.parse(resp.body());
        String uuid = strVal(data.get("uuid"));
        if (uuid == null || uuid.isEmpty()) {
            throw new IllegalStateException(tr("ndpr.error.get_uuid_invalid", "data", resp.body()));
        }
        config.set("uuid", uuid);
        log.info(tr("ndpr.log.uuid_obtained", "uuid", uuid));
    }

    /** 上报拦截统计（对应 MCDR 版 report_kick） */
    private void reportKick() {
        try {
            http.postJson(config.getString("api_url", "") + "/stats/a",
                    Map.of("Authorization", "Bearer " + config.getString("token", "")),
                    new JsonObject(), 5);
        } catch (Exception ignored) {
        }
    }

    private void restartDownloadTask() {
        if (downloadTask != null) {
            downloadTask.cancel();
            downloadTask = null;
        }
        int interval = config.getInt("download_interval", 900);
        if (interval <= 0) {
            log.info(tr("ndpr.log.auto_update_disabled"));
            return;
        }
        downloadTask = platform.schedule(() -> {
            try {
                downloadBanDatabase(null);
            } catch (Exception ignored) {
            }
        }, interval, interval);
        log.info(tr("ndpr.log.auto_update_started", "interval", interval));
    }

    private static String strVal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof com.google.gson.JsonPrimitive) {
            return ((com.google.gson.JsonPrimitive) v).getAsString();
        }
        return String.valueOf(v);
    }
}
