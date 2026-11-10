package com.mnyace.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnyace.auth.SessionActivity;
import com.mnyace.auth.SessionGate;
import com.mnyace.shared.dto.ApiResponse;
import com.mnyace.auth.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final SessionGate sessionGate;
    private final SessionActivity sessionActivity;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        if (jwtUtil.isTokenValid(token)) {
            String userId = jwtUtil.extractUserId(token);
            String role = jwtUtil.extractRole(token);
            String sessionId = jwtUtil.extractSessionId(token);

            SessionGate.Placement placement = userRepository.existsById(userId)
                    ? sessionGate.placementOf(userId, sessionId)
                    : new SessionGate.Placement(SessionGate.State.NOT_LIVE, null);
            SessionGate.State state = placement.state();

            if (state != SessionGate.State.NOT_LIVE) {
                sessionActivity.touch(placement.token());
            }

            if (state == SessionGate.State.RESTRICTED && !isSessionManagement(request)) {
                rejectRestricted(response);
                return;
            }

            if (state != SessionGate.State.NOT_LIVE) {
                List<SimpleGrantedAuthority> authorities =
                        List.of(new SimpleGrantedAuthority("ROLE_" + role));

                UsernamePasswordAuthenticationToken authenticationToken =
                        new UsernamePasswordAuthenticationToken(userId, null, authorities);
                authenticationToken.setDetails(sessionId);

                SecurityContextHolder.getContext().setAuthentication(authenticationToken);
            }
        }

        filterChain.doFilter(request, response);
    }

    /** A session past the cap may only list sessions and sign others out, so it can free a slot for itself. */
    private boolean isSessionManagement(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean listing = "GET".equals(request.getMethod()) && "/api/sessions".equals(path);
        boolean signingOut = "DELETE".equals(request.getMethod())
                && ("/api/sessions".equals(path) || path.startsWith("/api/sessions/"));
        return listing || signingOut;
    }

    private void rejectRestricted(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        ApiResponse<?> body = ApiResponse.error("SESSION_LIMIT_REACHED",
                "Too many active sessions. Sign out of another device to continue.");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
