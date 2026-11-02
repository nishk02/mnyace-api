package com.mnyace.auth;

import com.mnyace.shared.email.EmailService;
import com.mnyace.shared.email.MailAsyncConfig;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Sends off the request thread, so response time does not reveal whether an email has an account. */
@Component
@RequiredArgsConstructor
public class PasswordResetMailer {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetMailer.class);

    private final EmailService emailService;

    @Async(MailAsyncConfig.MAIL_EXECUTOR)
    public void send(String email, String code) {
        try {
            emailService.sendPasswordResetEmail(email, code);
        } catch (Exception e) {
            // never log the code or the address
            log.warn("Password reset email failed: {}", e.getClass().getSimpleName());
        }
    }
}
