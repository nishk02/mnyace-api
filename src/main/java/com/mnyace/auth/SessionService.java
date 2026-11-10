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
    private final SessionGate sessionGate;

    /** The user's live sessions, most recently active first. */
    @Transactional(readOnly = true)
    public List<SessionResponse> listFor(String userId, String currentSessionId) {
        List<RefreshToken> live = sessionGate.liveSessionsOldestFirst(userId);
        return java.util.stream.IntStream.range(0, live.size())
                .mapToObj(i -> {
                    RefreshToken token = live.get(i);
                    return new SessionResponse(
                            token.getFamilyId(),
                            token.getDeviceLabel(),
                            toInstant(token.getSessionStartedAt()),
                            toInstant(token.getLastActiveAt()),
                            token.getIpAddress(),
                            token.getUserAgent(),
                            token.getFamilyId().equals(currentSessionId),
                            i >= sessionGate.maxSessions());
                })
                .sorted(Comparator.comparing(SessionResponse::lastActiveAt).reversed())
                .toList();
    }

    /** Signs out one of the user's other sessions, deleting its rows. */
    @Transactional
    public void signOut(String userId, String sessionId, String currentSessionId) {
        if (sessionId.equals(currentSessionId)) {
            throw ApiException.badRequest("CURRENT_SESSION", "Use logout to sign out this device.", null);
        }
        List<RefreshToken> rows = refreshTokenRepository.findAllByFamilyIdAndUser_Id(sessionId, userId);
        boolean live = rows.stream().anyMatch(token -> token.isActive(LocalDateTime.now()));
        if (!live) {
            throw ApiException.notFound("Session not found");
        }
        refreshTokenRepository.deleteAll(rows);
    }

    /** Timestamps are stored in server-local time; clients get an unambiguous UTC instant. */
    private static Instant toInstant(LocalDateTime local) {
        return local.atZone(ZoneId.systemDefault()).toInstant();
    }
}
