# NDPR Proxy Plugin (Velocity + BungeeCord)

<div align="center">

![Version](https://img.shields.io/badge/version-2.2.0-blue.svg)
![Java](https://img.shields.io/badge/java-17+-green.svg)
![Velocity](https://img.shields.io/badge/Velocity-3.x-orange.svg)
![BungeeCord](https://img.shields.io/badge/BungeeCord-1.20.1+-orange.svg)
![License](https://img.shields.io/badge/license-MIT-red.svg)

**NDPR 封禁系统代理端插件（双平台单 Jar）**

[官网](https://ndpreforged.com) • [QQ群](https://qm.qq.com/cgi-bin/qm/qr?k=232760327)

</div>

---

## 📖 简介

NDPR (NotDPR) 封禁系统在 **Velocity / BungeeCord 代理端** 的完整实现。一个 Jar 同时支持 Velocity 3.x 与 BungeeCord（MC 1.20.1+），实现云端封禁数据库跨服联防 —— 代理层统一拦截，**所有下游子服自动获得封禁防护，无需逐个安装客户端**。

### 主要功能

- **多维封禁**：ID、UUID、IPv4、IPv6 四种方式封禁检查
- **云端同步**：定时下载封禁数据库（SQLite），跨服联防
- **智能检测**：玩家连入代理时立即检查封禁状态，命中直接断开连接
- **封禁统计**：拦截后自动上报服务器（`/stats/a`）
- **提交审核**：`/ndpr ban` 提交封禁审核（`/check/uploader`）
- **机器验证**：HWID 设备验证（`/hwid/upd` 系列接口），验证期间命令封锁
- **自动更新**：可配置封禁列表更新间隔与 GitHub 版本检查

---

## 🏗 设计说明

### 架构：平台无关核心 + 双平台入口

```
ndpr-proxy.jar
├── com.ndpreforged.proxy.common        # 平台无关核心（全部业务逻辑）
│   ├── NdpPlugin.java                  # 核心门面（生命周期 / 玩家事件 / 命令）
│   ├── Config.java                     # 配置读写（兼容 TOML/YAML 键值）
│   ├── Translations.java               # 内置翻译表（zh_CN / en_us）
│   ├── ApiClient.java                  # HTTP 客户端（JDK HttpClient，零依赖）
│   ├── BanDatabase.java                # SQLite 封禁库（sqlite-jdbc 打包）
│   ├── JsonStore.java                  # player_info / hwid_temp 本地存储
│   ├── HwidVerifyService.java          # HWID 验证会话 + 命令门控
│   ├── UpdateChecker.java              # GitHub 更新检查
│   ├── Platform.java                   # 平台抽象接口
│   └── NetUtil.java / RichMessage.java # 工具
├── com.ndpreforged.proxy.velocity      # Velocity 入口（@Plugin 注解 + 事件 + 命令）
└── com.ndpreforged.proxy.bungee        # Bungee 入口（Plugin 基类 + 事件 + 命令）
```

两个平台入口都实现 `Platform` 接口（玩家/命令源/调度器/消息/断开 的抽象），核心逻辑与平台 API 完全解耦。

### 关键设计

| 功能 | 实现方式 | 说明 |
|---|---|---|
| 玩家加入封禁检查 | `PostLoginEvent` 中断开连接 | 在进子服前拦截，所有子服生效 |
| 踢出玩家 | `player.disconnect(Component)` | 代理 API 直接断开 |
| IP / IPv6 获取 | 连接地址 `InetSocketAddress` | 无需解析服务器日志 |
| UUID 获取 | `player.getUniqueId()` | 正版/离线由代理模式决定 |
| 离线玩家信息（ban 命令） | 本地 `player_info.json` 缓存 | 玩家连入时自动保存 |
| 封禁库下载/校验 | `/bans/download` → SQLite 校验 → 原子替换 | 表结构动态检测，兼容旧库 |
| HWID 验证 | 云端会话（upd/check/has/cancel） | 见下方"命令封锁说明" |
| 验证期间冻结 | **降级为命令封锁** | 仅放行登录命令，其余命令在代理层拦截 |
| 权限控制 | 权限节点 `ndpr.admin` | 需 LuckPerms 等权限插件 |

### HWID 验证命令封锁说明

代理层无法执行后端指令（tp / gamemode / effect），因此验证期间的"冻结"降级为**命令封锁**：

1. 验证期间玩家**无法执行任何命令**（Velocity 通过 `CommandExecuteEvent` 拒绝、BungeeCord 通过 `ChatEvent` 取消）
2. **仅放行登录类命令**（默认 `l / reg / login / register`，可用配置 `hwid_allowed_commands` 调整，逗号分隔），适配 AuthMe 等登录插件；`/ndpr` 自身与拥有 `ndpr.admin` 权限的玩家不受限制
3. 聊天不受影响；验证完成前玩家可自由移动，但无法进行指令操作
4. 验证链接仍以可点击消息下发，超时未完成直接断开连接

### 数据目录

| 平台 | 路径 |
|---|---|
| Velocity | `plugins/ndpr/` |
| BungeeCord | `plugins/NDPReforged-Proxy/` |

```
plugins/ndpr/
├── config.toml           # 配置（首次启动自动生成）
└── data/
    ├── ban_database.db   # 封禁数据库（云端下载）
    ├── player_info.json  # 玩家信息缓存
    └── hwid_temp.json    # HWID 验证记录
```

---

## 🚀 安装

### Velocity

1. 将 `ndpr-proxy.jar` 放入 `plugins/` 目录
2. 重启 Velocity（或使用 Velocity 插件热加载）
3. 编辑 `plugins/ndpr/config.toml`，填写 `token` 与 `onlinemode`
4. `/ndpr reload` 重载

### BungeeCord

1. 将 `ndpr-proxy.jar` 放入 `plugins/` 目录
2. 重启 BungeeCord
3. 编辑 `plugins/NDPReforged-Proxy/config.toml`
4. `/ndpr reload` 重载

### 获取 Token

1. 启动插件，自动获取 UUID（或查看日志）
2. 前往 [官网](https://ndpreforged.com) 用 UUID 绑定邮箱
3. 系统发放 Token，填入配置并重载

---

## ⚙️ 配置

```toml
onlinemode = true          # 正版服；false=离线服（留空时使用代理自身模式）
api_url = "https://api.ndpreforged.com"
token = ""                 # 必填（启用封禁功能）
download_interval = 900    # 封禁库更新间隔（秒），0=禁用
check_hwid = false         # HWID 机器验证
check_interval = 3         # HWID 复检间隔（天）
fail_closed = false        # 数据库缺失时：false=放行，true=踢出
verify_timeout = 60        # HWID 验证超时（秒）
freeze_interval = 1        # 兼容字段（代理端不执行冻结指令）
hwid_allowed_commands = "l,reg,login,register"  # 验证期间允许的命令（逗号分隔）
update_repo = "NDPReforged/NDPR-Proxy"  # 更新检查仓库，留空禁用
# log_path / logger_mode / logger_format 为兼容字段，代理端不使用
```

---

## 📟 命令

| 命令 | 说明 | 权限 |
|---|---|---|
| `/ndpr` / `/ndpr help` | 帮助信息 | 所有人 |
| `/ndpr d` / `/ndpr download` | 手动下载封禁数据库 | ndpr.admin |
| `/ndpr ban <玩家> <原因>` | 提交封禁审核 | ndpr.admin |
| `/ndpr check <ID/IP/UUID>` | 检查封禁状态（模糊建议点击直接查询） | 所有人 |
| `/ndpr reload` | 重载配置并下载数据库 | ndpr.admin |
| `/ndpr cu` / `/ndpr checkupdate` | 检查插件更新 | ndpr.admin |
| `/ndpr auth <玩家>` | 强制触发设备验证 | ndpr.admin |

---

## 🔨 构建

要求：JDK 17+、网络（拉取依赖）

```bash
gradlew.bat build
# 输出：build/libs/ndpr-proxy.jar
```

依赖：

- `velocity-api 3.3.0-SNAPSHOT`（compileOnly，运行时不打包）
- `bungeecord-api 1.20-R0.1`（compileOnly）
- `sqlite-jdbc 3.45.3.0`（shade 打包，含 relocate 后的 slf4j-api）
- Gson 由两个平台运行时提供

---

## ⚠️ 注意事项

- 命令由 `/` 前缀触发（代理层没有 `!!` 前缀命令）
- `ndpr.admin` 权限需配合 LuckPerms 等权限插件分配
- 玩家在**代理层**被拦截时，不会进入任何子服

---

## 许可证

MIT License

**Made with ❤️ by NDPReforged Team**
