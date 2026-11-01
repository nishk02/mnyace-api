package com.mnyace.auth;

import java.time.LocalDateTime;

public record SessionResponse(
        String id,
        String deviceLabel,
        LocalDateTime signedInAt,
        LocalDateTime lastActiveAt,
        boolean current
) {
}
