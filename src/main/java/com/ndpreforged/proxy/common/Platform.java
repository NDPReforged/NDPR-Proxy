package com.ndpreforged.proxy.common;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 平台抽象层：屏蔽 Velocity 与 BungeeCord 的差异。
 * 两个平台入口（NdpVelocity / NdpBungee）各自实现本接口，
 * common 核心逻辑完全平台无关。
 */
public interface Platform {

    /** 平台名（日志/调试用） */
    String platformName();

    /** 插件数据目录 */
    Path dataDir();

    /** 代理当前的正版/离线模式（配置 onlinemode 未填写时以此为准） */
    boolean proxyOnlineMode();

    Logger logger();

    /** 后台异步执行（网络/数据库/轮询） */
    void runAsync(Runnable r);

    /**
     * 调度任务。periodSec &lt;= 0 表示一次性任务（延迟 delaySec 执行）。
     */
    ScheduledTask schedule(Runnable r, long delaySec, long periodSec);

    /** 按名字查找在线玩家 */
    Optional<ProxyPlayer> player(String name);

    /** 当前在线玩家 */
    List<ProxyPlayer> players();

    /** 向命令源发送纯文本（含 § 颜色代码） */
    void sendMessage(NdpSource src, String legacyText);

    /** 向命令源发送富文本（含点击/悬浮事件） */
    void sendMessage(NdpSource src, RichMessage msg);

    /** 向玩家发送纯文本 */
    void sendMessage(ProxyPlayer p, String legacyText);

    /** 向玩家发送富文本 */
    void sendMessage(ProxyPlayer p, RichMessage msg);

    /** 断开玩家连接（kick） */
    void disconnect(ProxyPlayer p, String legacyReason);

    /** 发送标题（平台不支持时为空实现） */
    void sendTitle(ProxyPlayer p, String legacyTitle, String legacySubtitle);

    /** 注册 /ndpr 命令 */
    void registerCommands(CommandExecutor executor, CommandCompleter completer);

    //-------------------------------------------------------------------------
    // 抽象类型
    //-------------------------------------------------------------------------

    /** 在线玩家（平台无关） */
    interface ProxyPlayer {
        String name();

        UUID uniqueId();

        /** IPv4 或 null */
        String ipv4();

        /** IPv6 或 null */
        String ipv6();
    }

    /** 命令源（玩家或控制台） */
    interface NdpSource {
        String name();

        boolean isAdmin();

        void reply(String legacyText);

        void reply(RichMessage msg);

        Optional<ProxyPlayer> asPlayer();
    }

    /** 命令执行接口（由 common 核心实现） */
    interface CommandExecutor {
        void execute(NdpSource src, String[] args);
    }

    /** 命令补全接口（由 common 核心实现） */
    interface CommandCompleter {
        List<String> suggest(NdpSource src, String[] args);
    }

    /** 调度任务句柄 */
    interface ScheduledTask {
        void cancel();
    }
}
