package com.moneyflow.auth;

import com.moneyflow.shared.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;
    private final RefreshTokenCookieWriter cookieWriter;

    @PostMapping("/signin")
    public ResponseEntity<ApiResponse<AuthResponse>> signIn(
            @Valid @RequestBody SignInRequest request,
            @RequestHeader(value = "X-Client-Platform", required = false) String platform
    ) {
        AuthResponse response = authService.signIn(request);
        return cookieWriter.authResponse(response, platform, "Signed in successfully");
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @RequestBody(required = false) RefreshRequest request,
            @RequestHeader(value = "X-Client-Platform", required = false) String platform,
            @CookieValue(name = "refreshToken", required = false) String refreshTokenCookie
    ) {
        String presentedToken = cookieWriter.resolveIncomingToken(
                platform, refreshTokenCookie, request != null ? request.refreshToken() : null);
        AuthResponse response = authService.refresh(presentedToken);
        return cookieWriter.authResponse(response, platform, "Token refreshed successfully");
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @RequestBody(required = false) LogoutRequest request,
            @RequestHeader(value = "X-Client-Platform", required = false) String platform,
            @CookieValue(name = "refreshToken", required = false) String refreshTokenCookie
    ) {
        String presentedToken = cookieWriter.resolveIncomingToken(
                platform, refreshTokenCookie, request != null ? request.refreshToken() : null);
        authService.logout(presentedToken);

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (cookieWriter.isWeb(platform)) {
            builder = builder.header(HttpHeaders.SET_COOKIE, cookieWriter.buildClearCookieHeader());
        }
        return builder.body(ApiResponse.success(null, "Logged out successfully"));
    }
}
