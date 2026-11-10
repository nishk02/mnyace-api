package com.mnyace.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Decides which of a user's live sessions may use the API. The oldest {@code session.max-per-user} sessions are
 * active; any newer one is restricted to session management until an older one signs out.
 */
@Component
@RequiredArgsConstructor
public class SessionGate {
    public enum State { NOT_LIVE, ACTIVE, RESTRICTED }

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${session.max-per-user}")
    private int maxSessions;

    public int maxSessions() {
        return maxSessions;
    }

    /** One live token per session, oldest sign-in first; ties break on the session id so the order is stable. */
    @Transactional(readOnly = true)
    public List<RefreshToken> liveSessionsOldestFirst(String userId) {
        Map<String, RefreshToken> byFamily = refreshTokenRepository
                .findAllByUser_IdAndRevokedAtIsNullAndExpiresAtAfter(userId, LocalDateTime.now())
                .stream()
                .collect(Collectors.toMap(RefreshToken::getFamilyId, Function.identity(), (a, b) -> a));
        return byFamily.values().stream()
                .sorted(Comparator.comparing(RefreshToken::getSessionStartedAt).thenComparing(RefreshToken::getFamilyId))
                .toList();
    }

    /** A session's state and its live token; the token is null when the session is not live. */
    public record Placement(State state, RefreshToken token) {}

    @Transactional(readOnly = true)
    public State stateOf(String userId, String sessionId) {
        return placementOf(userId, sessionId).state();
    }

    @Transactional(readOnly = true)
    public Placement placementOf(String userId, String sessionId) {
        if (sessionId == null) {
            return new Placement(State.NOT_LIVE, null);
        }
        List<RefreshToken> live = liveSessionsOldestFirst(userId);
        for (int i = 0; i < live.size(); i++) {
            if (live.get(i).getFamilyId().equals(sessionId)) {
                return new Placement(i < maxSessions ? State.ACTIVE : State.RESTRICTED, live.get(i));
            }
        }
        return new Placement(State.NOT_LIVE, null);
    }
}
