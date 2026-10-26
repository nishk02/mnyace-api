package com.mnyace.shared.security;

import jakarta.servlet.http.HttpServletRequest;

/** The caller's address: the first X-Forwarded-For entry (set by Caddy, the only way in), else the socket peer. */
public final class ClientIp {
    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
