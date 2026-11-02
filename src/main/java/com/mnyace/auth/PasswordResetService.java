package com.mnyace.auth;

import com.mnyace.shared.exception.ApiException;
import com.mnyace.shared.exception.TooManyRequestsException;
import com.mnyace.shared.security.audit.SecurityEvent;
import com.mnyace.shared.security.audit.SecurityEvents;
import com.mnyace.shared.util.AppClock;
import com.mnyace.shared.util.EmailNormalizer;
import com.mnyace.shared.util.PasswordRules;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Forgot-password and reset-password. Not @Transactional as a whole: a spent guess must stay
 * spent when the request then fails, so only the success step runs in one transaction.
 */
@Service
public class PasswordResetService {
    private static final int CODE_TTL_MINUTES = 15;
    private static final int MAX_ATTEMPTS = 5;
    private static final String INVALID_CODE_MESSAGE = "This code is incorrect or has expired. Request a new one.";

    private final UserRepository userRepository;
    private final PasswordResetRepository passwordResetRepository;
    private final PasswordResetCodeHasher codeHasher;
    private final PasswordResetMailer mailer;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final SecurityEvents securityEvents;
    private final TransactionTemplate transaction;
    private final SecureRandom secureRandom = new SecureRandom();

    private final long dailyBudget;
    private final long perEmailDaily;
    private final long cooldownSeconds;

    public PasswordResetService(
            UserRepository userRepository,
            PasswordResetRepository passwordResetRepository,
            PasswordResetCodeHasher codeHasher,
            PasswordResetMailer mailer,
            PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokenService,
            SecurityEvents securityEvents,
            PlatformTransactionManager transactionManager,
            @Value("${password-reset.mail.daily-budget}") long dailyBudget,
            @Value("${password-reset.mail.per-email-daily}") long perEmailDaily,
            @Value("${password-reset.request-cooldown-seconds}") long cooldownSeconds
    ) {
        this.userRepository = userRepository;
        this.passwordResetRepository = passwordResetRepository;
        this.codeHasher = codeHasher;
        this.mailer = mailer;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.securityEvents = securityEvents;
        this.transaction = new TransactionTemplate(transactionManager);
        this.dailyBudget = dailyBudget;
        this.perEmailDaily = perEmailDaily;
        this.cooldownSeconds = cooldownSeconds;
    }

    /** Always returns normally for a valid request, so the caller cannot tell whether the email has an account. */
    public void requestReset(ForgotPasswordRequest request) {
        LocalDateTime dayStart = startOfTodayLocal();
        // Checked before looking the user up, so every email gets the same answer when the budget is gone.
        if (passwordResetRepository.countByCreatedAtGreaterThanEqual(dayStart) >= dailyBudget) {
            securityEvents.record(SecurityEvent.RATE_LIMITED, null, "mail_budget_daily");
            throw new TooManyRequestsException(
                    "Password reset emails are paused for today. Please try again tomorrow.", secondsUntilTomorrow());
        }

        // The code and its hash are made for unknown emails too, so both paths do the same work.
        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        Optional<User> found = userRepository.findByEmailIgnoreCase(EmailNormalizer.normalize(request.email()));
        if (found.isEmpty()) {
            codeHasher.hash("unknown", code);
            return;
        }
        User user = found.get();
        String codeHash = codeHasher.hash(user.getId(), code);

        if (passwordResetRepository.countByUser_IdAndCreatedAtGreaterThanEqual(user.getId(), dayStart) >= perEmailDaily) {
            securityEvents.record(SecurityEvent.RATE_LIMITED, user.getId(), "forgot_password_email_daily");
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        boolean cooling = passwordResetRepository.findFirstByUser_IdOrderByCreatedAtDesc(user.getId())
                .map(last -> last.getCreatedAt().plusSeconds(cooldownSeconds).isAfter(now))
                .orElse(false);
        if (cooling) {
            return;
        }

        passwordResetRepository.closeOpenCodes(user.getId(), now);
        PasswordReset reset = new PasswordReset();
        reset.setUser(user);
        reset.setCodeHash(codeHash);
        reset.setExpiresAt(now.plusMinutes(CODE_TTL_MINUTES));
        reset.setCreatedAt(now);
        passwordResetRepository.save(reset);

        securityEvents.record(SecurityEvent.PASSWORD_RESET_REQUESTED, user.getId(), null);
        mailer.send(user.getEmail(), code);
    }

    /** Sets the new password and signs the user out everywhere; it does not sign them in. */
    public void resetPassword(ResetPasswordRequest request) {
        // These checks never depend on the account, so they run first and never spend a guess.
        if (!request.newPassword().equals(request.confirmNewPassword())) {
            throw validationError("Passwords do not match");
        }
        if (PasswordRules.isTooLong(request.newPassword())) {
            throw validationError("New password is too long");
        }

        Optional<User> found = userRepository.findByEmailIgnoreCase(EmailNormalizer.normalize(request.email()));
        if (found.isEmpty()) {
            codeHasher.matches("unknown", request.code(), "0");
            throw rejected(null, "unknown_user");
        }
        User user = found.get();

        Optional<PasswordReset> open = passwordResetRepository.findFirstByUser_IdAndUsedAtIsNullOrderByCreatedAtDesc(user.getId());
        if (open.isEmpty()) {
            codeHasher.matches(user.getId(), request.code(), "0");
            throw rejected(user.getId(), "no_open_code");
        }
        PasswordReset reset = open.get();
        if (reset.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw rejected(user.getId(), "expired");
        }

        // Spend the guess before checking it, so a burst of parallel requests is capped too.
        if (passwordResetRepository.consumeAttempt(reset.getId(), MAX_ATTEMPTS) == 0) {
            securityEvents.record(SecurityEvent.OTP_LOCKOUT, user.getId(), "password_reset_max_attempts");
            throw rejected(user.getId(), "attempts_exhausted");
        }
        if (!codeHasher.matches(user.getId(), request.code(), reset.getCodeHash())) {
            throw rejected(user.getId(), "wrong_code");
        }

        boolean done = Boolean.TRUE.equals(transaction.execute(status -> {
            if (passwordResetRepository.markUsed(reset.getId(), LocalDateTime.now()) == 0) {
                return false;
            }
            user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
            userRepository.save(user);
            refreshTokenService.revokeAllForUser(user.getId());
            return true;
        }));
        if (!done) {
            throw rejected(user.getId(), "already_used");
        }
        securityEvents.record(SecurityEvent.PASSWORD_RESET, user.getId(), null);
    }

    private ApiException rejected(String userId, String reason) {
        securityEvents.record(SecurityEvent.PASSWORD_RESET_FAILED, userId, reason);
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OR_EXPIRED_CODE", INVALID_CODE_MESSAGE);
    }

    private static ApiException validationError(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    /** Start of today in IST, as the server-local time the created_at column holds. */
    private static LocalDateTime startOfTodayLocal() {
        return AppClock.today().atStartOfDay(AppClock.ZONE)
                .withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    private static long secondsUntilTomorrow() {
        ZonedDateTime now = ZonedDateTime.now(AppClock.ZONE);
        return Math.max(1, Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(AppClock.ZONE)).getSeconds());
    }
}
