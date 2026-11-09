package com.mnyace.auth;

import com.mnyace.shared.security.ClientIp;
import jakarta.servlet.http.HttpServletRequest;

/** Where a sign-in came from, kept on the session so a user can tell two similar devices apart. */
public record ClientInfo(String ip, String userAgent) {
    private static final int MAX_USER_AGENT = 255;

    public static ClientInfo from(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && userAgent.length() > MAX_USER_AGENT) {
            userAgent = userAgent.substring(0, MAX_USER_AGENT);
        }
        return new ClientInfo(ClientIp.of(request), userAgent);
    }

    public static ClientInfo unknown() {
        return new ClientInfo(null, null);
    }
}
