package com.rustvnt.vntapp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 计算需要下发给 Android VPN 的路由集合。
 *
 * <p>VNT 1.x 的两类路由字段：
 * <ul>
 *   <li>{@code in_ips}：每行 "目标网段/前缀,出口节点虚拟IP"，表示要访问的远端网段；</li>
 *   <li>{@code out_ips}：每行 "网段/前缀"，表示本机允许转发的网段。</li>
 * </ul>
 * Android 侧只关心网段本身，因此两者都按 CIDR 下发。
 */
final class VpnRouteSet {
    record Route(String address, int prefix) {
        String cidr() { return address + "/" + prefix; }
    }

    private VpnRouteSet() {}

    static List<Route> build(String ip, int prefixLen, List<String> inRoutes,
                             List<String> outRoutes) {
        LinkedHashMap<String, Route> routes = new LinkedHashMap<>();
        add(routes, ip + "/" + prefixLen);
        for (String route : inRoutes) add(routes, route);
        for (String route : outRoutes) add(routes, route);
        return new ArrayList<>(routes.values());
    }

    static Set<String> cidrs(List<String> routes) {
        Set<String> result = new LinkedHashSet<>();
        for (String route : routes) result.add(parse(route).cidr());
        return result;
    }

    private static void add(LinkedHashMap<String, Route> routes, String value) {
        Route route = parse(value);
        routes.putIfAbsent(route.cidr(), route);
    }

    private static Route parse(String value) {
        String cidr = value == null ? "" : value.split(",", 2)[0].trim();
        String[] parts = cidr.split("/", -1);
        if (parts.length != 2) throw new IllegalArgumentException("无效路由：" + value);
        int prefix;
        try {
            prefix = Integer.parseInt(parts[1]);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("无效路由前缀：" + value, error);
        }
        if (prefix < 0 || prefix > 32) throw new IllegalArgumentException("无效路由前缀：" + value);
        int address = parseIpv4(parts[0].trim(), value);
        int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
        int network = address & mask;
        String normalized = ((network >>> 24) & 255) + "." + ((network >>> 16) & 255) + "." +
                ((network >>> 8) & 255) + "." + (network & 255);
        return new Route(normalized, prefix);
    }

    private static int parseIpv4(String address, String original) {
        String[] octets = address.split("\\.", -1);
        if (octets.length != 4) throw new IllegalArgumentException("无效 IPv4 路由：" + original);
        int value = 0;
        for (String octet : octets) {
            int parsed;
            try {
                parsed = Integer.parseInt(octet);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("无效 IPv4 路由：" + original, error);
            }
            if (parsed < 0 || parsed > 255) {
                throw new IllegalArgumentException("无效 IPv4 路由：" + original);
            }
            value = (value << 8) | parsed;
        }
        return value;
    }
}
