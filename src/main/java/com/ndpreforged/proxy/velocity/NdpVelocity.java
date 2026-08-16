package com.ndpreforged.proxy.velocity;

import com.google.inject.Inject;
import com.ndpreforged.proxy.NdpConstants;
import com.ndpreforged.proxy.common.NdpPlugin;
import com.ndpreforged.proxy.common.NetUtil;
import com.ndpreforged.proxy.common.Platform;
import com.ndpreforged.proxy.common.RichMessage;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * NDPR Velocity 入口（对应 MCDR 版 on_load）。
 *
 * 数据目录：plugins/ndpr/
 * 命令：/ndpr（别名 NDPR）
 * 权限：ndpr.admin（对应 MCDR 权限等级 2）
 */
@Plugin(
        id = NdpConstants.PLUGIN_ID,
        name = NdpConstants.PLUGIN_NAME,
        version = NdpConstants.VERSION,
        description = "NDPReforged 代理端客户端（Velocity）",
        authors = {"EXE_autumnwind", "NDPReforged Team"},
        url = NdpConstants.WEBSITE
)
public final class NdpVelocity implements Platform {

    @Inject
    private ProxyServer proxy;

    @Inject
    @DataDirectory
    private Path dataDir;

    private final Logger log = Logger.getLogger("ndpr");
    private NdpPlugin plugin;

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        plugin = new NdpPlugin(this);
        try {
            plugin.init();
        } catch (Exception e) {
            log.log(Level.SEVERE, "NDPR init failed", e);
        }
        proxy.getEventManager().register(this, PostLoginEvent.class, e -> plugin.onPlayerJoin(adapt(e.getPlayer())));
        proxy.getEventManager().register(this, DisconnectEvent.class, e -> plugin.onPlayerLeave(adapt(e.getPlayer())));
        // HWID 验证期间的命令封锁：仅放行登录类命令
        proxy.getEventManager().register(this, CommandExecuteEvent.class, e -> {
            if (!(e.getCommandSource() instanceof Player)) {
                return;
            }
            Player p = (Player) e.getCommandSource();
            if (!plugin.gateCommand(p.getUsername(), e.getCommand(), p.hasPermission(NdpConstants.PERM_ADMIN))) {
                e.setResult(CommandExecuteEvent.CommandResult.denied());
                p.sendMessage(legacy("§c" + plugin.tr("ndpr.tell.verify_command_denied")));
            }
        });
        registerCommands((src, args) -> plugin.handleCommand(src, args),
                (src, args) -> plugin.suggest(src, args));
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (plugin != null) {
            plugin.shutdown();
        }
    }

    //-------------------------------------------------------------------------
    // Platform
    //-------------------------------------------------------------------------

    @Override
    public String platformName() {
        return "Velocity";
    }

    @Override
    public Path dataDir() {
        return dataDir;
    }

    @Override
    public boolean proxyOnlineMode() {
        return proxy.getConfiguration().isOnlineMode();
    }

    @Override
    public Logger logger() {
        return log;
    }

    @Override
    public void runAsync(Runnable r) {
        proxy.getScheduler().buildTask(this, r).schedule();
    }

    @Override
    public Platform.ScheduledTask schedule(Runnable r, long delaySec, long periodSec) {
        var builder = proxy.getScheduler().buildTask(this, r);
        if (periodSec > 0) {
            builder = builder.delay(delaySec, TimeUnit.SECONDS).repeat(periodSec, TimeUnit.SECONDS);
        } else {
            builder = builder.delay(delaySec, TimeUnit.SECONDS);
        }
        var task = builder.schedule();
        return task::cancel;
    }

    @Override
    public Optional<Platform.ProxyPlayer> player(String name) {
        return proxy.getPlayer(name).map(this::adapt);
    }

    @Override
    public List<Platform.ProxyPlayer> players() {
        List<Platform.ProxyPlayer> out = new ArrayList<>();
        for (Player p : proxy.getAllPlayers()) {
            out.add(adapt(p));
        }
        return out;
    }

    @Override
    public void sendMessage(Platform.NdpSource src, String legacyText) {
        if (src instanceof VSource) {
            ((VSource) src).source.sendMessage(legacy(legacyText));
        }
    }

    @Override
    public void sendMessage(Platform.NdpSource src, RichMessage msg) {
        if (src instanceof VSource) {
            ((VSource) src).source.sendMessage(component(msg));
        }
    }

    @Override
    public void sendMessage(Platform.ProxyPlayer p, String legacyText) {
        if (p instanceof VPlayer) {
            ((VPlayer) p).player.sendMessage(legacy(legacyText));
        }
    }

    @Override
    public void sendMessage(Platform.ProxyPlayer p, RichMessage msg) {
        if (p instanceof VPlayer) {
            ((VPlayer) p).player.sendMessage(component(msg));
        }
    }

    @Override
    public void disconnect(Platform.ProxyPlayer p, String legacyReason) {
        if (p instanceof VPlayer) {
            ((VPlayer) p).player.disconnect(legacy(legacyReason));
        }
    }

    @Override
    public void sendTitle(Platform.ProxyPlayer p, String legacyTitle, String legacySubtitle) {
        // Velocity 无公开 Title API（需 raw packet），忽略；
        // 验证提示已通过聊天消息下发，功能不受影响
    }

    @Override
    public void registerCommands(Platform.CommandExecutor executor, Platform.CommandCompleter completer) {
        proxy.getCommandManager().register(NdpConstants.MAIN_COMMAND, new SimpleCommand() {
            @Override
            public void execute(Invocation invocation) {
                executor.execute(adaptSource(invocation.source()), invocation.arguments());
            }

            @Override
            public List<String> suggest(Invocation invocation) {
                return completer.suggest(adaptSource(invocation.source()), invocation.arguments());
            }

            @Override
            public boolean hasPermission(Invocation invocation) {
                CommandSource s = invocation.source();
                return s instanceof ConsoleCommandSource || s.hasPermission(NdpConstants.PERM_ADMIN);
            }
        }, "NDPR");
    }

    //-------------------------------------------------------------------------
    // 适配
    //-------------------------------------------------------------------------

    private static Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text == null ? "" : text);
    }

    private static Component component(RichMessage msg) {
        Component c = legacy(msg.text);
        if (msg.action != null && msg.actionValue != null) {
            ClickEvent.Action action = switch (msg.action) {
                case OPEN_URL -> ClickEvent.Action.OPEN_URL;
                case RUN_COMMAND -> ClickEvent.Action.RUN_COMMAND;
                case SUGGEST_COMMAND -> ClickEvent.Action.SUGGEST_COMMAND;
                case COPY_TO_CLIPBOARD -> ClickEvent.Action.COPY_TO_CLIPBOARD;
            };
            c = c.clickEvent(ClickEvent.clickEvent(action, msg.actionValue));
        }
        if (msg.hover != null) {
            c = c.hoverEvent(HoverEvent.showText(legacy(msg.hover)));
        }
        return c;
    }

    private Platform.ProxyPlayer adapt(Player player) {
        return new VPlayer(player);
    }

    private Platform.NdpSource adaptSource(CommandSource source) {
        if (source instanceof Player) {
            Player player = (Player) source;
            return new VSource(source, adapt(player));
        }
        return new VSource(source, null);
    }

    private static final class VPlayer implements Platform.ProxyPlayer {
        private final Player player;

        VPlayer(Player player) {
            this.player = player;
        }

        @Override
        public String name() {
            return player.getUsername();
        }

        @Override
        public UUID uniqueId() {
            return player.getUniqueId();
        }

        @Override
        public String ipv4() {
            return NetUtil.splitIp(player.getRemoteAddress())[0];
        }

        @Override
        public String ipv6() {
            return NetUtil.splitIp(player.getRemoteAddress())[1];
        }
    }

    private static final class VSource implements Platform.NdpSource {
        private final CommandSource source;
        private final Platform.ProxyPlayer player;

        VSource(CommandSource source, Platform.ProxyPlayer player) {
            this.source = source;
            this.player = player;
        }

        @Override
        public String name() {
            return player != null ? player.name() : "CONSOLE";
        }

        @Override
        public boolean isAdmin() {
            return source instanceof ConsoleCommandSource || source.hasPermission(NdpConstants.PERM_ADMIN);
        }

        @Override
        public void reply(String legacyText) {
            source.sendMessage(legacy(legacyText));
        }

        @Override
        public void reply(RichMessage msg) {
            source.sendMessage(component(msg));
        }

        @Override
        public Optional<Platform.ProxyPlayer> asPlayer() {
            return Optional.ofNullable(player);
        }
    }
}
