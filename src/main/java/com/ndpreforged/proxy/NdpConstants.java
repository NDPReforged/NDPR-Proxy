package com.ndpreforged.proxy;

/**
 * NDPReforged 代理端客户端全局常量
 * （对应 MCDR 版 mcdreforged.plugin.json / 模块常量）
 */
public final class NdpConstants {

    public static final String PLUGIN_ID = "ndpr";
    public static final String PLUGIN_NAME = "NDPReforged-Proxy";
    public static final String VERSION = "2.1.0";
    public static final String WEBSITE = "https://ndpreforged.com";
    public static final String DEFAULT_UPDATE_REPO = "NDPReforged/NDPR-MCDR";

    public static final String DEFAULT_LANGUAGE = "zh_CN";
    public static final String DEFAULT_API_URL = "https://api.ndpreforged.com";

    // 数据目录内文件名（与 MCDR 版保持一致）
    public static final String CONFIG_FILE = "config.toml";
    public static final String DATA_DIR = "data";
    public static final String BAN_DB_FILE = "ban_database.db";
    public static final String PLAYER_INFO_FILE = "player_info.json";
    public static final String HWID_TEMP_FILE = "hwid_temp.json";

    // 权限节点（对应 MCDR 权限等级 2）
    public static final String PERM_ADMIN = "ndpr.admin";

    // 命令主名
    public static final String MAIN_COMMAND = "ndpr";

    private NdpConstants() {
    }
}
