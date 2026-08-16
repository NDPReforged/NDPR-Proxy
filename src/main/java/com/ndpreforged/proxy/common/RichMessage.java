package com.ndpreforged.proxy.common;

/**
 * 富文本消息（平台无关）。
 * 携带 § 颜色代码文本 + 可选的点击/悬浮事件，由各平台转换为
 * Adventure Component（Velocity）或 BaseComponent（Bungee）。
 */
public final class RichMessage {

    public enum Action {
        OPEN_URL,
        /** 点击直接执行命令（/cmd args） */
        RUN_COMMAND,
        /** 点击将命令填充到聊天栏（不执行） */
        SUGGEST_COMMAND,
        COPY_TO_CLIPBOARD
    }

    public final String text;
    public final Action action;
    public final String actionValue;
    public final String hover;

    private RichMessage(String text, Action action, String actionValue, String hover) {
        this.text = text;
        this.action = action;
        this.actionValue = actionValue;
        this.hover = hover;
    }

    public static RichMessage plain(String text) {
        return new RichMessage(text, null, null, null);
    }

    public static RichMessage clickable(String text, Action action, String value) {
        return new RichMessage(text, action, value, null);
    }

    public static RichMessage clickable(String text, Action action, String value, String hover) {
        return new RichMessage(text, action, value, hover);
    }
}
