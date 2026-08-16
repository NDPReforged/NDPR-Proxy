package com.ndpreforged.proxy.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * 本地 JSON 存储（player_info.json / hwid_temp.json）。
 * 对应 MCDR 版 save_player_info / load_player_info / load_hwid_temp / save_hwid_temp。
 */
public final class JsonStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path path;
    private final ReentrantLock lock = new ReentrantLock();

    public JsonStore(Path path) {
        this.path = path;
    }

    public Path path() {
        return path;
    }

    /** 读取全部内容；文件缺失或损坏时返回空 map */
    public Map<String, Object> readAll() {
        lock.lock();
        try {
            if (!Files.exists(path)) {
                return new LinkedHashMap<>();
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            Object parsed = GSON.fromJson(text, Object.class);
            if (parsed instanceof Map<?, ?>) {
                Map<String, Object> map = new LinkedHashMap<>();
                ((Map<?, ?>) parsed).forEach((k, v) -> map.put(String.valueOf(k), v));
                return map;
            }
            return new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        } finally {
            lock.unlock();
        }
    }

    /** 读取单个玩家的记录（player_info 风格） */
    public Map<String, Object> readEntry(String player) {
        Object o = readAll().get(player);
        if (o instanceof Map<?, ?>) {
            Map<String, Object> result = new LinkedHashMap<>();
            ((Map<?, ?>) o).forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return new LinkedHashMap<>();
    }

    /** 覆盖写入整个内容 */
    public void writeAll(Map<String, Object> content) throws IOException {
        lock.lock();
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, GSON.toJson(content), StandardCharsets.UTF_8);
        } finally {
            lock.unlock();
        }
    }

    /** 写入单个玩家的记录（player_info 风格，保留其他玩家） */
    public void writeEntry(String player, Map<String, Object> entry) throws IOException {
        Map<String, Object> all = readAll();
        all.put(player, entry);
        writeAll(all);
    }

    public boolean exists() {
        return Files.exists(path);
    }
}
