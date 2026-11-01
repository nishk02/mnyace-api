package com.mnyace.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SessionService {
    private final RefreshTokenRepository refreshTokenRepository;

    /** The user's live sessions, most recently active first. */
    @Transactional(readOnly = true)
    public List<SessionResponse> listFor(String userId, String currentSessionId) {
        return refreshTokenRepository.findAllByUser_IdAndRevokedAtIsNullAndExpiresAtAfter(userId, LocalDateTime.now())
                .stream()
                .map(token -> new SessionResponse(
                        token.getFamilyId(),
                        token.getDeviceLabel(),
                        token.getSessionStartedAt(),
                        token.getCreatedAt(),
                        token.getFamilyId().equals(currentSessionId)))
                .sorted(Comparator.comparing(SessionResponse::lastActiveAt).reversed())
                .toList();
    }
}
