package com.moneyflow.shared.security.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneyflow.shared.dto.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rate-limits POST /auth/signin and POST /auth/refresh, ahead of
 * JwtAuthFilter. These are permitAll (no JWT to gate them) — exactly why
 * they're worth protecting here: signin is the one place a password can be
 * guessed at all, and refresh is a DoS target because it does real work
 * (a DB lookup, a rotation) on every call.
 *
 * Signin gets two independent buckets — one per IP, one per email in the
 * request body — because they catch two different attack shapes: one
 * source hammering many accounts (IP), and one account being guessed from
 * many sources (email). Refresh only gets an IP bucket; a stolen/guessed
 * refresh token is already caught by reuse-detection (BR-26), so this is
 * purely a request-volume backstop.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimiterService signinIpRateLimiter;
    private final RateLimiterService signinEmailRateLimiter;
    private final RateLimiterService refreshIpRateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(
            @Qualifier("signinIpRateLimiter") RateLimiterService signinIpRateLimiter,
            @Qualifier("signinEmailRateLimiter") RateLimiterService signinEmailRateLimiter,
            @Qualifier("refreshIpRateLimiter") RateLimiterService refreshIpRateLimiter,
            ObjectMapper objectMapper
    ) {
        this.signinIpRateLimiter = signinIpRateLimiter;
        this.signinEmailRateLimiter = signinEmailRateLimiter;
        this.refreshIpRateLimiter = refreshIpRateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        boolean isSignin = isPost(request) && "/auth/signin".equals(request.getRequestURI());
        boolean isRefresh = isPost(request) && "/auth/refresh".equals(request.getRequestURI());

        if (!isSignin && !isRefresh) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);

        if (isRefresh) {
            RateLimiterService.Decision decision = refreshIpRateLimiter.consume(clientIp);
            if (!decision.allowed()) {
                rejectWithTooManyRequests(response, decision.retryAfterSeconds());
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        // isSignin: buffer the body so we can read the email for the
        // per-email bucket, then still hand an intact, re-readable body
        // down to the controller's @RequestBody binding.
        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);

        RateLimiterService.Decision ipDecision = signinIpRateLimiter.consume(clientIp);
        if (!ipDecision.allowed()) {
            rejectWithTooManyRequests(response, ipDecision.retryAfterSeconds());
            return;
        }

        String email = extractEmail(cachedRequest.getBody());
        if (email != null) {
            RateLimiterService.Decision emailDecision = signinEmailRateLimiter.consume(email);
            if (!emailDecision.allowed()) {
                rejectWithTooManyRequests(response, emailDecision.retryAfterSeconds());
                return;
            }
        }

        filterChain.doFilter(cachedRequest, response);
    }

    private boolean isPost(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod());
    }

    private String extractEmail(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            String email = node.path("email").asText(null);
            return email == null ? null : email.trim().toLowerCase();
        } catch (IOException e) {
            // Malformed JSON — let @Valid/@RequestBody rejection downstream
            // handle it normally; nothing to key an email bucket on here.
            return null;
        }
    }

    /**
     * Caddy is the only thing that can reach this container at all — the
     * api service is `expose`d on the Docker network, never `ports`-published
     * to the host — so X-Forwarded-For can only ever have been set by Caddy
     * itself, not forged by an external caller bypassing it.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void rejectWithTooManyRequests(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));

        ApiResponse<?> body = ApiResponse.error(
                "TOO_MANY_REQUESTS", "Too many attempts. Please wait and try again."
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}