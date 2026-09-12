package com.agent.sense.security;

import com.agent.sense.common.BizException;
import com.agent.sense.common.ErrorCode;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 出站访问守卫（R2-02 SSRF 防护，Phase 1 已有能力在渠道层复用）
 * 拦截 loopback / 私网 / link-local / multicast / 非 http(s) 目标。
 */
public final class OutboundGuard {

    private OutboundGuard() {}

    public static Set<String> parseAllowedHosts(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(","))
                .map(String::trim).filter(s -> !s.isBlank())
                .map(String::toLowerCase)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 内部可信端点（来自配置而非用户输入）访问守卫：仅要求 http(s) 且 host 可解析。
     * 仅用于调用 nlp-service 等内网服务，禁止用于用户提交的 data_source。
     */
    public static void assertInternalHttpUrl(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new BizException(ErrorCode.AGENT_BAD_REQUEST, "internal endpoint must be http/https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new BizException(ErrorCode.AGENT_BAD_REQUEST, "internal endpoint host is required");
        }
        try {
            InetAddress.getByName(uri.getHost());
        } catch (Exception e) {
            throw new BizException(ErrorCode.AGENT_BAD_REQUEST, "internal endpoint host cannot be resolved");
        }
    }

    private static boolean isExtendedBlockedAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc) {
            return true; // IPv6 unique local fc00::/7
        }
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 100 && second >= 64 && second <= 127; // IPv4 CGNAT 100.64/10
        }
        return false;
    }
    public static void assertPublicHttpUrl(URI uri, Set<String> allowedHosts) {        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new BizException(ErrorCode.AGENT_BAD_REQUEST, "only http/https URLs are allowed");
        }
        if (host == null || host.isBlank()) {
            throw new BizException(ErrorCode.AGENT_BAD_REQUEST, "URL host is required");
        }
        if (allowedHosts != null && !allowedHosts.isEmpty() && !allowedHosts.contains(host.toLowerCase())) {
            throw new BizException(ErrorCode.AGENT_BAD_REQUEST, "host is not in SENSE_ALLOWED_HOSTS");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isMulticastAddress() || isExtendedBlockedAddress(address)) {
                    throw new BizException(ErrorCode.AGENT_BAD_REQUEST, "private or local network targets are forbidden");
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.AGENT_BAD_REQUEST, "URL host cannot be resolved: " + host);
        }
    }
}
