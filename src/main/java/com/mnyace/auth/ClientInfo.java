package com.mnyace.auth;

import com.mnyace.shared.security.ClientIp;
import jakarta.servlet.http.HttpServletRequest;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** Where a sign-in came from, kept on the session so a user can tell two similar devices apart. The IP is truncated to its network. */
public record ClientInfo(String ip, String userAgent) {
    private static final int MAX_USER_AGENT = 255;

    public static ClientInfo from(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && userAgent.length() > MAX_USER_AGENT) {
            userAgent = userAgent.substring(0, MAX_USER_AGENT);
        }
        return new ClientInfo(truncate(ClientIp.of(request)), userAgent);
    }

    /** Keeps the network, not the host: the first three bytes of an IPv4 address (/24) or the first six of an IPv6 one (/48). */
    static String truncate(String ip) {
        if (ip == null || !ip.matches("[0-9a-fA-F:.]+")) {
            return null;
        }
        try {
            InetAddress address = InetAddress.getByName(ip);
            byte[] bytes = address.getAddress();
            for (int i = address instanceof Inet4Address ? 3 : 6; i < bytes.length; i++) {
                bytes[i] = 0;
            }
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    public static ClientInfo unknown() {
        return new ClientInfo(null, null);
    }
}
