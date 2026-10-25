package com.moneyflow.auth;

import com.moneyflow.shared.exception.ApiException;
import com.moneyflow.shared.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RefreshTokenService refreshTokenService;

    // A dummy BCrypt hash (cost 10) used for unknown emails to keep signin
    // timing consistent and prevent email enumeration.
    private static final String DUMMY_PASSWORD_HASH =
            "$2a$10$6rtN/l7.pj9lD0ad1RxkMuuJY6LLt4b9gGK5RlfyXgSSQYNz91kry";

    @Transactional
    public AuthResponse signIn(SignInRequest request) {
        Optional<User> userOpt = userRepository.findByEmail(request.email());
        boolean passwordMatches = passwordEncoder.matches(
                request.password(),
                userOpt.map(User::getPasswordHash).orElse(DUMMY_PASSWORD_HASH)
        );

        if (userOpt.isEmpty() || !passwordMatches) {
            throw ApiException.unauthorized("Invalid email or password");
        }
        User user = userOpt.get();

        String accessToken = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole());
        RefreshTokenIssuer.IssuedRefreshToken refreshToken =
                refreshTokenService.issueNewFamily(user, request.deviceLabel());

        return new AuthResponse(accessToken, refreshToken.rawToken(), AuthResponse.UserSummary.from(user));
    }

    public AuthResponse refresh(String presentedRawToken) {
        if (presentedRawToken == null || presentedRawToken.isBlank()) {
            throw ApiException.unauthorized("Session expired. Please sign in again.");
        }
        RefreshTokenIssuer.IssuedRefreshToken rotated = refreshTokenService.rotate(presentedRawToken);
        User user = rotated.user();

        String accessToken = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole());

        return new AuthResponse(accessToken, rotated.rawToken(), AuthResponse.UserSummary.from(user));
    }

    @Transactional
    public void logout(String presentedRawToken) {
        if (presentedRawToken == null || presentedRawToken.isBlank()) {
            throw ApiException.unauthorized("Session expired. Please sign in again.");
        }
        refreshTokenService.revokeFamilyOf(presentedRawToken);
    }
}