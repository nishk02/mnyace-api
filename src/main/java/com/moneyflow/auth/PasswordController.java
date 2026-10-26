package com.moneyflow.auth;

import com.moneyflow.shared.dto.ApiResponse;
import com.moneyflow.shared.security.BaseController;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me/password")
@RequiredArgsConstructor
public class PasswordController extends BaseController {
    private final PasswordService passwordService;
    private final RefreshTokenCookieWriter cookieWriter;

    @PutMapping
    public ResponseEntity<ApiResponse<AuthResponse>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @RequestHeader(value = "X-Client-Platform", required = false) String platform
    ) {
        AuthResponse response = passwordService.changePassword(getCurrentUserId(), request);
        return cookieWriter.authResponse(response, platform, "Password changed. Other devices were signed out.");
    }
}
