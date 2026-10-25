package com.moneyflow.invite;

import com.moneyflow.shared.util.EmailNormalizer;
import com.moneyflow.auth.*;
import com.moneyflow.shared.email.EmailService;
import com.moneyflow.shared.exception.ApiException;
import com.moneyflow.shared.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

@Service
@RequiredArgsConstructor
public class InviteService {
    private static final int TOKEN_BYTES = 32;
    private static final int VALIDITY_DAYS = 7;
    private static final int OTP_TTL_MINUTES = 10;
    // Lifetime cap per invite, deliberately not reset by a resend: otherwise resending would
    // hand an attacker a fresh set of guesses every minute.
    private static final int MAX_OTP_ATTEMPTS = 10;
    private static final int RESEND_COOLDOWN_SECONDS = 60;

    private final InviteRepository inviteRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RefreshTokenService refreshTokenService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.max-users:50}")
    private int maxUsers;

    public InviteResult createInvite(String adminUserId, CreateInviteRequest request) {
        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> ApiException.unauthorized("Not authenticated"));

        String email = EmailNormalizer.normalize(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("A user with this email already exists");
        }

        Invite invite = new Invite();
        invite.setEmail(email);
        invite.setToken(generateToken());
        invite.setStatus(InviteStatus.PENDING);
        invite.setInvitedBy(admin);
        invite.setExpiresAt(LocalDateTime.now().plusDays(VALIDITY_DAYS));

        Invite saved = inviteRepository.save(invite);
        emailService.sendInviteEmail(saved.getEmail(), saved.getToken());

        long registeredMembers = userRepository.countByRole("MEMBER");
        String warning = registeredMembers >= maxUsers
                ? ("You're at or past your %d-user cap (%d registered members)." +
                "This invite was still created — the hard limit is enforced at sign-up, " +
                "not here.").formatted(maxUsers, registeredMembers)
                : null;

        return new InviteResult(InviteResponse.from(saved), warning);
    }

    /**
     * Step 1 of accepting an invitation
     */
    public void submitSignup(String token, InviteSignupRequest request) {
        Invite invite = getInviteOrThrow(token);
        requireStatus(invite, InviteStatus.PENDING, "This invite has already been used or is awaiting a code.");
        requireNotExpired(invite);

        if (!request.password().equals(request.confirmPassword())) {
            throw ApiException.badRequest("Passwords do not match");
        }

        invite.setPendingFirstName(request.firstName());
        invite.setPendingLastName(request.lastName());
        invite.setPendingPasswordHash(passwordEncoder.encode(request.password()));

        issueNewOtp(invite);
        invite.setStatus(InviteStatus.AWAITING_OTP);

        inviteRepository.save(invite);
    }

    public InviteResponse getInviteInfo(String token) {
        Invite invite = getInviteOrThrow(token);
        requireNotExpired(invite);

        return InviteResponse.from(invite);
    }

    /**
     * Step 2: the invited person submits the 6-digit code from their inbox.
     * On success this is the moment the real User row is finally created.
     */
    public AuthResponse verifyOtp(String token, VerifyOtpRequest request) {
        Invite invite = getInviteOrThrow(token);
        requireStatus(invite, InviteStatus.AWAITING_OTP, "This invite is not awaiting a verification code.");
        requireNotExpired(invite);

        if (invite.getOtpExpiresAt() == null || invite.getOtpExpiresAt().isBefore(LocalDateTime.now())) {
            throw ApiException.badRequest("This code has expired. Request a new one.");
        }
        if (userRepository.countByRole("MEMBER") >= maxUsers) {
            throw ApiException.conflict("The user limit for this workspace has been reached.");
        }

        // Spend the guess before checking it, so a burst of parallel requests is capped too.
        if (inviteRepository.consumeOtpAttempt(invite.getId(), MAX_OTP_ATTEMPTS) == 0) {
            throw ApiException.badRequest("Too many incorrect attempts. Ask for a new invite.");
        }

        if (!passwordEncoder.matches(request.code(), invite.getOtpCodeHash())) {
            throw ApiException.unauthorized("Incorrect code");
        }

        User user = new User();
        user.setFirstName(invite.getPendingFirstName());
        user.setLastName(invite.getPendingLastName());
        user.setEmail(EmailNormalizer.normalize(invite.getEmail()));
        user.setPasswordHash(invite.getPendingPasswordHash());
        user.setRole("MEMBER");
        User savedUser = userRepository.save(user);

        // Clear staged secrets now that they've done their job - no reason a
        // completed invite row should keep holding a password hash forever.
        invite.setStatus(InviteStatus.COMPLETED);
        invite.setPendingPasswordHash(null);
        invite.setOtpCodeHash(null);
        inviteRepository.save(invite);

        String accessToken = jwtUtil.generateToken(savedUser.getId(), savedUser.getEmail(), savedUser.getRole());
        RefreshTokenIssuer.IssuedRefreshToken refreshToken = refreshTokenService.issueNewFamily(savedUser, null);

        return new AuthResponse(accessToken, refreshToken.rawToken(), AuthResponse.UserSummary.from(savedUser));
    }

    /**
     * Step 2b: the code expired, landed in spam, or was never received. Issues a
     * fresh code without making the person restart from their name/password again.
     */
    public void resendOtp(String token) {
        Invite invite = getInviteOrThrow(token);
        requireStatus(invite, InviteStatus.AWAITING_OTP, "This invite is not awaiting a verification code.");
        requireNotExpired(invite);

        LocalDateTime cooldownEnds = invite.getUpdatedAt().plusSeconds(RESEND_COOLDOWN_SECONDS);
        if (cooldownEnds.isAfter(LocalDateTime.now())) {
            long secondsLeft = ChronoUnit.SECONDS.between(LocalDateTime.now(), cooldownEnds);
            throw ApiException.badRequest("Please wait " + secondsLeft + "s before requesting another code.");
        }

        issueNewOtp(invite);
        inviteRepository.save(invite);
    }

    private void issueNewOtp(Invite invite) {
        String code = generateOtpCode();
        invite.setOtpCodeHash(passwordEncoder.encode(code));
        invite.setOtpExpiresAt(LocalDateTime.now().plusMinutes(OTP_TTL_MINUTES));
        emailService.sendOtpEmail(invite.getEmail(), code);
    }

    private Invite getInviteOrThrow(String token) {
        return inviteRepository.findByToken(token)
                .orElseThrow(() -> ApiException.notFound("Invite not found"));
    }

    private void requireStatus(Invite invite, InviteStatus expected, String message) {
        if (invite.getStatus() != expected) {
            throw ApiException.conflict(message);
        }
    }

    private void requireNotExpired(Invite invite) {
        if (invite.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw ApiException.badRequest("This invite has expired. Ask for a new one.");
        }
    }

    private String generateOtpCode() {
        int code = secureRandom.nextInt(1_000_000);
        return String.format("%06d", code);
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
