package com.ndpreforged.proxy.common;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * 网络工具：从连接地址中分离 IPv4 / IPv6。
 * 代理层可直接获得玩家真实连接地址（MCDR 版需解析服务器日志，代理版无需）。
 */
public final class NetUtil {

    private NetUtil() {
    }

    /** BungeeCord ProxiedPlayer.getSocketAddress() 返回 SocketAddress，统一入口 */
    public static String[] splitIp(SocketAddress addr) {
        if (addr instanceof InetSocketAddress) {
            return splitIp((InetSocketAddress) addr);
        }
        return new String[]{null, null};
    }

    /**
     * @param addr 玩家连接地址
     * @return [ipv4, ipv6]，不存在的位置为 null；
     *         IPv4-mapped IPv6 (::ffff:x.x.x.x) 自动还原为 IPv4
     */
    public static String[] splitIp(InetSocketAddress addr) {
        String ipv4 = null;
        String ipv6 = null;
        if (addr != null) {
            InetAddress inet = addr.getAddress();
            if (inet != null) {
                String host = inet.getHostAddress();
                if (host != null) {
                    int zone = host.indexOf('%');
                    if (zone >= 0) {
                        host = host.substring(0, zone);
                    }
                    if (host.startsWith("::ffff:")) {
                        ipv4 = host.substring(7);
                    } else if (host.contains(":")) {
                        ipv6 = host;
                    } else {
                        ipv4 = host;
                    }
                }
            }
        }
        return new String[]{ipv4, ipv6};
    }

    /** 判断是否为 IPv4 地址字符串 */
    public static boolean isIpv4(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            int v;
            try {
                v = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return false;
            }
            if (v < 0 || v > 255) {
                return false;
            }
        }
        return true;
    }

    /** 判断是否为 IPv6 地址字符串（宽松：包含冒号且非 IPv4-mapped） */
    public static boolean isIpv6(String s) {
        return s != null && s.contains(":") && !s.startsWith("::ffff:");
    }

    /** 判断是否为 UUID 字符串 */
    public static boolean isUuid(String s) {
        if (s == null || s.length() != 36) {
            return false;
        }
        int dash = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-') {
                dash++;
            } else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return dash == 4;
    }
}
