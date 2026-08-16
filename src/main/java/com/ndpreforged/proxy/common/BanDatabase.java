package com.ndpreforged.proxy.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 封禁数据库（SQLite，sqlite-jdbc）。
 * 对应 MCDR 版 _db_conn / check_ban_status / check_ban_by_identifier / _fuzzy_suggest。
 *
 * 库结构由云端 sql_pack 生成：
 *   online : player, mcuuid, ip, ipv6, ban_reason, last_seen
 *   offline: player, ip, ipv6, ban_time, ban_reason
 * 兼容无 mcuuid 列的旧库（动态检测表结构）。
 */
public final class BanDatabase {

    private static final String[] TABLES = {"online", "offline"};

    private final Path path;
    private final Map<String, Set<String>> schemaCache = new HashMap<>();

    static {
        // Velocity/Bungee 的插件任务线程 TCCL 通常指向平台类加载器，
        // DriverManager 的 ServiceLoader 自动注册找不到插件 jar 内的驱动
        // （报 "No suitable driver found"）。显式加载驱动类触发其 static
        // 块自注册，确保任何类加载器环境下 SQLite 驱动都可用。
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (Throwable t) {
            throw new ExceptionInInitializerError("Failed to init sqlite-jdbc driver: " + t);
        }
    }

    public BanDatabase(Path path) {
        this.path = path;
    }

    public Path path() {
        return path;
    }

    public boolean exists() {
        return Files.exists(path);
    }

    private Connection open() throws Exception {
        String url = "jdbc:sqlite:" + path.toAbsolutePath().toString().replace('\\', '/');
        return DriverManager.getConnection(url);
    }

    private Set<String> columns(Connection conn, String table) {
        Set<String> cached = schemaCache.get(table);
        if (cached != null) {
            return cached;
        }
        Set<String> cols = new HashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                cols.add(rs.getString("name").toLowerCase());
            }
        } catch (Exception e) {
            // 表不存在等情况
        }
        schemaCache.put(table, cols);
        return cols;
    }

    private boolean hasColumn(Connection conn, String table, String col) {
        return columns(conn, table).contains(col);
    }

    private String timeColumn(Connection conn, String table) {
        if (hasColumn(conn, table, "ban_time")) {
            return "ban_time";
        }
        return "last_seen";
    }

    /** 校验数据库可用并统计两表记录总数（下载后用） */
    public int countAll() throws Exception {
        int total = 0;
        try (Connection conn = open()) {
            for (String table : TABLES) {
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    if (rs.next()) {
                        total += rs.getInt(1);
                    }
                }
            }
        }
        return total;
    }

    /** 封禁记录 */
    public static final class BanRecord {
        public final String table;
        public final String player;
        public final String reason;
        public final String time;

        public BanRecord(String table, String player, String reason, String time) {
            this.table = table;
            this.player = player;
            this.reason = reason;
            this.time = time;
        }
    }

    /**
     * 按玩家名/UUID/IP/IPv6 查封禁（对应 MCDR 版 on_player_joined 的查询逻辑）。
     */
    public BanRecord findBan(String name, String uuid, String ip, String ipv6) throws Exception {
        try (Connection conn = open()) {
            for (String table : TABLES) {
                boolean hasMcuuid = hasColumn(conn, table, "mcuuid");
                String sql;
                if (hasMcuuid) {
                    sql = "SELECT player, ban_reason, " + timeColumn(conn, table)
                            + " FROM " + table + " WHERE mcuuid = ? OR player = ? OR ip = ? OR ipv6 = ? LIMIT 1";
                } else {
                    sql = "SELECT player, ban_reason, " + timeColumn(conn, table)
                            + " FROM " + table + " WHERE player = ? OR ip = ? OR ipv6 = ? LIMIT 1";
                }
                try (var ps = conn.prepareStatement(sql)) {
                    int idx = 1;
                    if (hasMcuuid) {
                        ps.setString(idx++, uuid);
                    }
                    ps.setString(idx++, name);
                    ps.setString(idx++, ip);
                    ps.setString(idx++, ipv6);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return new BanRecord(table, rs.getString(1), rs.getString(2), rs.getString(3));
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 按标识符类型查询（对应 MCDR 版 check_ban_by_identifier）。
     *
     * @param type ip / ipv6 / uuid / id
     */
    public BanRecord findByIdentifier(String type, String value) throws Exception {
        try (Connection conn = open()) {
            for (String table : TABLES) {
                String col;
                if ("ip".equals(type)) {
                    col = "ip";
                } else if ("ipv6".equals(type)) {
                    col = "ipv6";
                } else if ("uuid".equals(type)) {
                    if (!hasColumn(conn, table, "mcuuid")) {
                        continue;
                    }
                    col = "mcuuid";
                } else {
                    col = "player";
                }
                if (!hasColumn(conn, table, col)) {
                    continue;
                }
                String sql = "SELECT player, ban_reason, " + timeColumn(conn, table)
                        + " FROM " + table + " WHERE " + col + " = ? LIMIT 1";
                try (var ps = conn.prepareStatement(sql)) {
                    ps.setString(1, value);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return new BanRecord(table, rs.getString(1), rs.getString(2), rs.getString(3));
                        }
                    }
                }
            }
        }
        return null;
    }

    /** 模糊匹配玩家名（对应 MCDR 版 _fuzzy_suggest） */
    public List<String> fuzzyNames(String query, int limit) throws Exception {
        List<String> matches = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String pattern = "%" + query.toLowerCase() + "%";
        try (Connection conn = open()) {
            for (String table : TABLES) {
                String sql = "SELECT player FROM " + table + " WHERE LOWER(player) LIKE ? LIMIT ?";
                try (var ps = conn.prepareStatement(sql)) {
                    ps.setString(1, pattern);
                    ps.setInt(2, limit * 2);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String name = rs.getString(1);
                            if (name != null && seen.add(name)) {
                                matches.add(name);
                            }
                            if (matches.size() >= limit) {
                                return matches;
                            }
                        }
                    }
                }
            }
        }
        return matches;
    }
}
