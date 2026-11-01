package com.mnyace.auth;

import java.time.Instant;

public record SessionResponse(
        String id,
        String deviceLabel,
        Instant signedInAt,
        Instant lastActiveAt,
        boolean current
) {
}
