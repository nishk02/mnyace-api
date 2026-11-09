package com.mnyace.auth;

import java.time.Instant;

public record SessionResponse(
        String id,
        String deviceLabel,
        Instant signedInAt,
        Instant lastActiveAt,
        String ipAddress,
        String userAgent,
        boolean current,
        boolean restricted
) {
}
