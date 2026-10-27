package com.mnyace.shared.security.ratelimit;

import com.mnyace.shared.util.EmailNormalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mnyace.shared.dto.ApiResponse;
import com.mnyace.shared.security.ClientIp;
import com.mnyace.shared.security.audit.SecurityEvent;
import com.mnyace.shared.security.audit.SecurityEvents;
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
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Rate-limits POST /auth/signin, POST /auth/refresh, the invite POSTs and every /api/** call, ahead of
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
 * purely a request-volume backstop. /api/** gets one generous IP bucket as a flood
 * guard; it counts unauthenticated requests too, since this filter runs before the JWT check.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {
    // The invite POSTs (signup, verify-otp, resend-otp) are public too; verify-otp is a guessable 6-digit code.
    private static final Pattern INVITE_ACTION_PATH =
            Pattern.compile("/auth/invites/[^/]+/(signup|verify-otp|resend-otp)");

    // A signin body is an email, a password and an optional device label; anything near this is abuse.
    private static final int MAX_SIGNIN_BODY_BYTES = 4096;

    private final RateLimiterService signinIpRateLimiter;
    private final RateLimiterService signinEmailRateLimiter;
    private final RateLimiterService refreshIpRateLimiter;
    private final RateLimiterService inviteIpRateLimiter;
    private final RateLimiterService apiIpRateLimiter;
    private final ObjectMapper objectMapper;
    private final SecurityEvents securityEvents;

    public RateLimitFilter(
            @Qualifier("signinIpRateLimiter") RateLimiterService signinIpRateLimiter,
            @Qualifier("signinEmailRateLimiter") RateLimiterService signinEmailRateLimiter,
            @Qualifier("refreshIpRateLimiter") RateLimiterService refreshIpRateLimiter,
            @Qualifier("inviteIpRateLimiter") RateLimiterService inviteIpRateLimiter,
            @Qualifier("apiIpRateLimiter") RateLimiterService apiIpRateLimiter,
            ObjectMapper objectMapper,
            SecurityEvents securityEvents
    ) {
        this.signinIpRateLimiter = signinIpRateLimiter;
        this.signinEmailRateLimiter = signinEmailRateLimiter;
        this.refreshIpRateLimiter = refreshIpRateLimiter;
        this.inviteIpRateLimiter = inviteIpRateLimiter;
        this.apiIpRateLimiter = apiIpRateLimiter;
        this.objectMapper = objectMapper;
        this.securityEvents = securityEvents;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        // getServletPath(), not getRequestURI(): the latter is the raw, still-encoded path, so
        // /auth/%73ignin would skip this filter yet be decoded and routed to /auth/signin.
        String path = request.getServletPath();

        if (path.startsWith("/api/") && !"OPTIONS".equalsIgnoreCase(request.getMethod())) {
            RateLimiterService.Decision decision = apiIpRateLimiter.consume(bucketKeyFor(ClientIp.of(request)));
            if (!decision.allowed()) {
                rejectWithTooManyRequests(response, decision.retryAfterSeconds(), "api_ip");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        boolean isSignin = isPost(request) && "/auth/signin".equals(path);
        boolean isRefresh = isPost(request) && "/auth/refresh".equals(path);

        boolean isInviteAction = isPost(request) && INVITE_ACTION_PATH.matcher(path).matches();

        if (!isSignin && !isRefresh && !isInviteAction) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = bucketKeyFor(ClientIp.of(request));

        if (isInviteAction) {
            RateLimiterService.Decision decision = inviteIpRateLimiter.consume(clientIp);
            if (!decision.allowed()) {
                rejectWithTooManyRequests(response, decision.retryAfterSeconds(), "invite_ip");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        if (isRefresh) {
            RateLimiterService.Decision decision = refreshIpRateLimiter.consume(clientIp);
            if (!decision.allowed()) {
                rejectWithTooManyRequests(response, decision.retryAfterSeconds(), "refresh_ip");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        // isSignin: charge the IP bucket first, so a flood of oversized bodies is throttled
        // before any of them is read.
        RateLimiterService.Decision ipDecision = signinIpRateLimiter.consume(clientIp);
        if (!ipDecision.allowed()) {
            rejectWithTooManyRequests(response, ipDecision.retryAfterSeconds(), "signin_ip");
            return;
        }

        // Then buffer the (size-capped) body so we can read the email for the per-email
        // bucket, and still hand an intact, re-readable body to @RequestBody binding.
        CachedBodyHttpServletRequest cachedRequest;
        try {
            cachedRequest = new CachedBodyHttpServletRequest(request, MAX_SIGNIN_BODY_BYTES);
        } catch (CachedBodyHttpServletRequest.BodyTooLargeException e) {
            rejectWithPayloadTooLarge(response);
            return;
        }

        String email = extractEmail(cachedRequest.getBody());
        if (email != null) {
            RateLimiterService.Decision emailDecision = signinEmailRateLimiter.consume(email);
            if (!emailDecision.allowed()) {
                rejectWithTooManyRequests(response, emailDecision.retryAfterSeconds(), "signin_email");
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
            return EmailNormalizer.normalize(email);
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

    /** Keys IPv6 clients on their /64 prefix (one subscriber owns a whole /64); IPv4 and unparseable values are used as-is. */
    static String bucketKeyFor(String ip) {
        if (!ip.contains(":")) {
            return ip;
        }
        try {
            // A literal containing ':' is never resolved via DNS.
            byte[] address = InetAddress.getByName(ip).getAddress();
            if (address.length != 16) {
                return InetAddress.getByAddress(address).getHostAddress(); // IPv4-mapped form
            }
            return HexFormat.of().formatHex(address, 0, 8) + "::/64";
        } catch (UnknownHostException e) {
            return ip;
        }
    }

    private void rejectWithPayloadTooLarge(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ApiResponse<?> body = ApiResponse.error("PAYLOAD_TOO_LARGE", "Request body is too large.");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private void rejectWithTooManyRequests(HttpServletResponse response, long retryAfterSeconds, String bucket) throws IOException {
        securityEvents.record(SecurityEvent.RATE_LIMITED, null, bucket);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));

        ApiResponse<?> body = ApiResponse.error(
                "TOO_MANY_REQUESTS", "Too many attempts. Please wait and try again."
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}