package com.mnyace.auth;

import com.mnyace.shared.dto.ApiResponse;
import com.mnyace.shared.security.BaseController;
import com.mnyace.shared.security.audit.SecurityEvent;
import com.mnyace.shared.security.audit.SecurityEvents;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController extends BaseController {
    private final RefreshTokenService refreshTokenService;
    private final RefreshTokenCookieWriter cookieWriter;
    private final SecurityEvents securityEvents;

    /** Signs the caller out of every device by revoking all of their refresh tokens. */
    @DeleteMapping
    public ResponseEntity<ApiResponse<Void>> signOutEverywhere(
            @RequestHeader(value = "X-Client-Platform", required = false) String platform
    ) {
        String userId = getCurrentUserId();
        refreshTokenService.revokeAllForUser(userId);
        securityEvents.record(SecurityEvent.SIGNOUT_ALL, userId, null);

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (cookieWriter.isWeb(platform)) {
            builder = builder.header(HttpHeaders.SET_COOKIE, cookieWriter.buildClearCookieHeader());
        }
        return builder.body(ApiResponse.success(null, "Signed out of all devices"));
    }
}
