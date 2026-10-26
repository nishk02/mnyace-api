package com.moneyflow.auth;

import com.moneyflow.shared.exception.ApiException;
import com.moneyflow.shared.security.JwtUtil;
import com.moneyflow.shared.security.ratelimit.RateLimiterService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
public class PasswordService {
    // BCrypt only reads the first 72 bytes, so anything longer would silently weaken the password
    private static final int MAX_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final JwtUtil jwtUtil;
    private final RateLimiterService passwordChangeRateLimiter;

    public PasswordService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokenService,
            JwtUtil jwtUtil,
            @Qualifier("passwordChangeRateLimiter") RateLimiterService passwordChangeRateLimiter
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.jwtUtil = jwtUtil;
        this.passwordChangeRateLimiter = passwordChangeRateLimiter;
    }

    /** Changes the password, signs the user out everywhere, and returns a fresh session for the caller. */
    @Transactional
    public AuthResponse changePassword(String userId, ChangePasswordRequest request) {
        if (!request.newPassword().equals(request.confirmNewPassword())) {
            throw ApiException.badRequest("Passwords do not match");
        }
        if (request.newPassword().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw ApiException.badRequest("New password is too long");
        }

        // Without a cap, anyone holding a stolen access token could test password guesses here.
        if (!passwordChangeRateLimiter.consume(userId).allowed()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                    "Too many attempts. Please wait and try again.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("Not authenticated"));

        // 400, not 401: clients treat a 401 as an expired session and would sign the user out for a typo.
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURRENT_PASSWORD",
                    "Current password is incorrect");
        }
        if (request.newPassword().equals(request.currentPassword())) {
            throw ApiException.badRequest("New password must be different from the current one");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        refreshTokenService.revokeAllForUser(userId);
        RefreshTokenIssuer.IssuedRefreshToken session =
                refreshTokenService.issueNewFamily(user, request.deviceLabel());
        String accessToken = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole());

        return new AuthResponse(accessToken, session.rawToken(), AuthResponse.UserSummary.from(user));
    }
}
