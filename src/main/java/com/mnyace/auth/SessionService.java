package com.mnyace.auth;

import com.mnyace.shared.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
                        toInstant(token.getSessionStartedAt()),
                        toInstant(token.getCreatedAt()),
                        token.getFamilyId().equals(currentSessionId)))
                .sorted(Comparator.comparing(SessionResponse::lastActiveAt).reversed())
                .toList();
    }

    /** Signs out one of the user's other sessions. */
    @Transactional
    public void signOut(String userId, String sessionId, String currentSessionId) {
        if (sessionId.equals(currentSessionId)) {
            throw ApiException.badRequest("CURRENT_SESSION", "Use logout to sign out this device.", null);
        }
        List<RefreshToken> live = refreshTokenRepository.findAllByFamilyIdAndUser_IdAndRevokedAtIsNull(sessionId, userId);
        if (live.isEmpty()) {
            throw ApiException.notFound("Session not found");
        }
        LocalDateTime now = LocalDateTime.now();
        live.forEach(token -> token.setRevokedAt(now));
        refreshTokenRepository.saveAll(live);
    }

    /** Timestamps are stored in server-local time; clients get an unambiguous UTC instant. */
    private static Instant toInstant(LocalDateTime local) {
        return local.atZone(ZoneId.systemDefault()).toInstant();
    }
}
