package com.mnyace.auth;

import com.mnyace.shared.dto.ApiResponse;
import com.mnyace.shared.security.BaseController;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class AccountDeletionController extends BaseController {
    private final AccountDeletionService accountDeletionService;
    private final RefreshTokenCookieWriter cookieWriter;

    /** Deletes the caller and all their data; every session ends with it, so the client signs the user out. */
    @DeleteMapping
    public ResponseEntity<ApiResponse<Void>> deleteAccount(
            @Valid @RequestBody DeleteAccountRequest request,
            @RequestHeader(value = "X-Client-Platform", required = false) String platform
    ) {
        accountDeletionService.deleteAccount(getCurrentUserId(), request.password());

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (cookieWriter.isWeb(platform)) {
            builder = builder.header(HttpHeaders.SET_COOKIE, cookieWriter.buildClearCookieHeader());
        }
        return builder.body(ApiResponse.success(null, "Your account and data have been deleted"));
    }
}
