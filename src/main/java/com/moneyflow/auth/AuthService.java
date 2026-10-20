package com.moneyflow.auth;

import com.moneyflow.shared.exception.ApiException;
import com.moneyflow.shared.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public AuthResponse signIn(SignInRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> ApiException.unauthorized("Invalid email or password"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("Invalid email or password");
        }

        String accessToken = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole());
        RefreshTokenIssuer.IssuedRefreshToken refreshToken =
                refreshTokenService.issueNewFamily(user, request.deviceLabel());

        return new AuthResponse(accessToken, refreshToken.rawToken(), AuthResponse.UserSummary.from(user));
    }

    public AuthResponse refresh(RefreshRequest request) {
        RefreshTokenIssuer.IssuedRefreshToken rotated = refreshTokenService.rotate(request.refreshToken());
        User user = rotated.user();

        String accessToken = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole());

        return new AuthResponse(accessToken, rotated.rawToken(), AuthResponse.UserSummary.from(user));
    }

    @Transactional
    public void logout(LogoutRequest request) {
        refreshTokenService.revokeFamilyOf(request.refreshToken());
    }
}