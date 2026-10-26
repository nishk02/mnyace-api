package com.mnyace.invite;

import com.mnyace.auth.AuthResponse;
import com.mnyace.auth.RefreshTokenCookieWriter;
import com.mnyace.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth/invites/{token}")
@RequiredArgsConstructor
public class InviteSignupController {
    private final InviteService inviteService;
    private final RefreshTokenCookieWriter cookieWriter;

    @GetMapping
    public ResponseEntity<ApiResponse<InviteResponse>> getInviteInfo(@PathVariable String token) {
        InviteResponse response = inviteService.getInviteInfo(token);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<Void>> submitSignup(
            @PathVariable String token,
            @Valid @RequestBody InviteSignupRequest request
    ) {
        inviteService.submitSignup(token, request);
        return ResponseEntity.ok(ApiResponse.success(null, "Verification code sent to your email"));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<ApiResponse<AuthResponse>> verifyOtp(
            @PathVariable String token,
            @Valid @RequestBody VerifyOtpRequest request,
            @RequestHeader(value = "X-Client-Platform", required = false) String platform
    ) {
        AuthResponse response = inviteService.verifyOtp(token, request);

        if (cookieWriter.isWeb(platform)) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, cookieWriter.buildSetCookieHeader(response.refreshToken()))
                    .body(ApiResponse.success(response.withoutRefreshToken(), "Account verified — welcome to MnyAce"));
        }
        return ResponseEntity.ok(ApiResponse.success(response, "Account verified — welcome to MnyAce"));
    }

    @PostMapping("/resend-otp")
    public ResponseEntity<ApiResponse<Void>> resendOtp(@PathVariable String token) {
        inviteService.resendOtp(token);
        return ResponseEntity.ok(ApiResponse.success(null, "A new code has been sent to your email"));
    }
}