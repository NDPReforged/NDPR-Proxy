package com.ndpreforged.proxy.bungee;

import com.ndpreforged.proxy.NdpConstants;
import com.ndpreforged.proxy.common.NdpPlugin;
import com.ndpreforged.proxy.common.NetUtil;
import com.ndpreforged.proxy.common.Platform;
import com.ndpreforged.proxy.common.RichMessage;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.Title;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.TabExecutor;
import net.md_5.bungee.event.EventHandler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * NDPR BungeeCord 入口（对应 MCDR 版 on_load）。
 *
 * 数据目录：plugins/NDPReforged-Proxy/
 * 命令：/ndpr（权限 ndpr.admin）
 */
public final class NdpBungee extends Plugin implements Platform {

    private NdpPlugin plugin;
    private final java.util.logging.Logger log = java.util.logging.Logger.getLogger("ndpr");

    @Override
    public void onEnable() {
        plugin = new NdpPlugin(this);
        try {
            plugin.init();
        } catch (Exception e) {
            log.log(Level.SEVERE, "NDPR init failed", e);
        }

        getProxy().getPluginManager().registerListener(this, new Listener() {
            @EventHandler
            public void onPostLogin(PostLoginEvent e) {
                plugin.onPlayerJoin(adapt(e.getPlayer()));
            }

            @EventHandler
            public void onDisconnect(PlayerDisconnectEvent e) {
                plugin.onPlayerLeave(adapt(e.getPlayer()));
            }

            @EventHandler
            public void onChat(ChatEvent e) {
                if (!e.isCommand() || !(e.getSender() instanceof ProxiedPlayer)) {
                    return;
                }
                ProxiedPlayer p = (ProxiedPlayer) e.getSender();
                // HWID 验证期间的命令封锁：仅放行登录类命令
                if (!plugin.gateCommand(p.getName(), e.getMessage(), p.hasPermission(NdpConstants.PERM_ADMIN))) {
                    e.setCancelled(true);
                    p.sendMessage(TextComponent.fromLegacyText("§c" + plugin.tr("ndpr.tell.verify_command_denied")));
                }
            }
        });

        registerCommands((src, args) -> plugin.handleCommand(src, args),
                (src, args) -> plugin.suggest(src, args));
    }

    @Override
    public void onDisable() {
        if (plugin != null) {
            plugin.shutdown();
        }
    }

    //-------------------------------------------------------------------------
    // Platform
    //-------------------------------------------------------------------------

    @Override
    public String platformName() {
        return "BungeeCord";
    }

    @Override
    public Path dataDir() {
        return getDataFolder().toPath();
    }

    @Override
    public boolean proxyOnlineMode() {
        return getProxy().getConfig().isOnlineMode();
    }

    @Override
    public java.util.logging.Logger logger() {
        return log;
    }

    @Override
    public void runAsync(Runnable r) {
        getProxy().getScheduler().runAsync(this, r);
    }

    @Override
    public Platform.ScheduledTask schedule(Runnable r, long delaySec, long periodSec) {
        var task = getProxy().getScheduler().schedule(this, r, delaySec, periodSec, TimeUnit.SECONDS);
        return () -> task.cancel();
    }

    @Override
    public Optional<Platform.ProxyPlayer> player(String name) {
        ProxiedPlayer p = getProxy().getPlayer(name);
        return p == null ? Optional.empty() : Optional.of(adapt(p));
    }

    @Override
    public List<Platform.ProxyPlayer> players() {
        List<Platform.ProxyPlayer> out = new ArrayList<>();
        for (ProxiedPlayer p : getProxy().getPlayers()) {
            out.add(adapt(p));
        }
        return out;
    }

    @Override
    public void sendMessage(Platform.NdpSource src, String legacyText) {
        src.reply(legacyText);
    }

    @Override
    public void sendMessage(Platform.NdpSource src, RichMessage msg) {
        src.reply(msg);
    }

    @Override
    public void sendMessage(Platform.ProxyPlayer p, String legacyText) {
        if (p instanceof BPlayer) {
            ((BPlayer) p).player.sendMessage(TextComponent.fromLegacyText(legacyText == null ? "" : legacyText));
        }
    }

    @Override
    public void sendMessage(Platform.ProxyPlayer p, RichMessage msg) {
        if (p instanceof BPlayer) {
            ((BPlayer) p).player.sendMessage(component(msg));
        }
    }

    @Override
    public void disconnect(Platform.ProxyPlayer p, String legacyReason) {
        if (p instanceof BPlayer) {
            ((BPlayer) p).player.disconnect(TextComponent.fromLegacyText(legacyReason == null ? "" : legacyReason));
        }
    }

    @Override
    public void sendTitle(Platform.ProxyPlayer p, String legacyTitle, String legacySubtitle) {
        if (p instanceof BPlayer) {
            ProxiedPlayer player = ((BPlayer) p).player;
            try {
                Title title = getProxy().createTitle();
                BaseComponent[] t = TextComponent.fromLegacyText(legacyTitle == null ? "" : legacyTitle);
                BaseComponent[] s = TextComponent.fromLegacyText(legacySubtitle == null ? "" : legacySubtitle);
                title.title(t.length > 0 ? t[0] : new TextComponent(""));
                title.subTitle(s.length > 0 ? s[0] : new TextComponent(""));
                title.fadeIn(5).stay(60).fadeOut(5);
                player.sendTitle(title);
            } catch (Exception e) {
                // 老版本 Bungee 无 Title API 时忽略
            }
        }
    }

    @Override
    public void registerCommands(Platform.CommandExecutor executor, Platform.CommandCompleter completer) {
        getProxy().getPluginManager().registerCommand(this, new NdpCommand(executor, completer));
    }

    /** 具名内部类：同时实现 Command 与 TabExecutor */
    private static final class NdpCommand extends Command implements TabExecutor {
        private final Platform.CommandExecutor executor;
        private final Platform.CommandCompleter completer;

        NdpCommand(Platform.CommandExecutor executor, Platform.CommandCompleter completer) {
            super(NdpConstants.MAIN_COMMAND, NdpConstants.PERM_ADMIN, "NDPR");
            this.executor = executor;
            this.completer = completer;
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            executor.execute(adaptSource(sender), args);
        }

        @Override
        public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
            return completer.suggest(adaptSource(sender), args);
        }
    }

    //-------------------------------------------------------------------------
    // 适配
    //-------------------------------------------------------------------------

    private static BaseComponent[] component(RichMessage msg) {
        BaseComponent[] c = TextComponent.fromLegacyText(msg.text == null ? "" : msg.text);
        if (msg.action != null && msg.actionValue != null) {
            ClickEvent.Action action = switch (msg.action) {
                case OPEN_URL -> ClickEvent.Action.OPEN_URL;
                case RUN_COMMAND -> ClickEvent.Action.RUN_COMMAND;
                case SUGGEST_COMMAND -> ClickEvent.Action.SUGGEST_COMMAND;
                case COPY_TO_CLIPBOARD -> ClickEvent.Action.COPY_TO_CLIPBOARD;
            };
            for (BaseComponent b : c) {
                b.setClickEvent(new ClickEvent(action, msg.actionValue));
            }
        }
        if (msg.hover != null) {
            BaseComponent[] hover = TextComponent.fromLegacyText(msg.hover);
            for (BaseComponent b : c) {
                b.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover));
            }
        }
        return c;
    }

    private static Platform.ProxyPlayer adapt(ProxiedPlayer player) {
        return new BPlayer(player);
    }

    private static Platform.NdpSource adaptSource(CommandSender sender) {
        if (sender instanceof ProxiedPlayer) {
            return new BSource(sender, adapt((ProxiedPlayer) sender));
        }
        return new BSource(sender, null);
    }

    private static final class BPlayer implements Platform.ProxyPlayer {
        private final ProxiedPlayer player;

        BPlayer(ProxiedPlayer player) {
            this.player = player;
        }

        @Override
        public String name() {
            return player.getName();
        }

        @Override
        public UUID uniqueId() {
            return player.getUniqueId();
        }

        @Override
        public String ipv4() {
            return NetUtil.splitIp(player.getSocketAddress())[0];
        }

        @Override
        public String ipv6() {
            return NetUtil.splitIp(player.getSocketAddress())[1];
        }
    }

    private static final class BSource implements Platform.NdpSource {
        private final CommandSender sender;
        private final Platform.ProxyPlayer player;

        BSource(CommandSender sender, Platform.ProxyPlayer player) {
            this.sender = sender;
            this.player = player;
        }

        @Override
        public String name() {
            return player != null ? player.name() : "CONSOLE";
        }

        @Override
        public boolean isAdmin() {
            return sender.hasPermission(NdpConstants.PERM_ADMIN);
        }

        @Override
        public void reply(String legacyText) {
            sender.sendMessage(TextComponent.fromLegacyText(legacyText == null ? "" : legacyText));
        }

        @Override
        public void reply(RichMessage msg) {
            sender.sendMessage(component(msg));
        }

        @Override
        public Optional<Platform.ProxyPlayer> asPlayer() {
            return Optional.ofNullable(player);
        }
    }
}
