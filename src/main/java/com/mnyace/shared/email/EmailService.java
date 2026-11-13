package com.mnyace.shared.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class EmailService {
    private final JavaMailSender mailSender;

    @Value("${app.invite-signup-url}")
    private String inviteSignupUrl;

    @Value("${app.mail-from}")
    private String fromAddress;

    @Value("${app.email-assets-url}")
    private String assetsUrl;

    @Value("${app.support-email}")
    private String supportEmail;

    public void sendInviteEmail(String toEmail, String token, String invitedByName, String invitedByEmail) {
        String link = inviteSignupUrl + "?token=" + token;

        String text = """
                You've been invited to MnyAce by %1$s (%2$s).

                Complete your sign-up here (valid for 7 days):
                %3$s

                What is MnyAce?
                MnyAce is a simple app to keep your money records in one place. Note down what you earn and spend, see where your money goes, and build the habit of saving, one small step at a time.
                - Track your finances: add what comes in and what goes out, in a simple way
                - Define realistic goals: set a goal with a date and save towards it step by step

                Savings is a Habit. Keeping track is the Key.

                If you weren't expecting this, you can ignore this email.
                Need help? Write to %4$s
                """.formatted(invitedByName, invitedByEmail, link, supportEmail);
        String html = EmailTemplates.render("invite", Map.of(
                "ASSETS_URL", assetsUrl,
                "ACCEPT_URL", link,
                "INVITED_BY_NAME", invitedByName,
                "INVITED_BY_EMAIL", invitedByEmail,
                "SUPPORT_EMAIL", supportEmail,
                "RECIPIENT_EMAIL", toEmail));

        send(toEmail, "You're invited to MnyAce", text, html);
    }

    public void sendOtpEmail(String toEmail, String code) {
        String text = """
                Your MnyAce verification code is:

                %1$s

                This code expires in 10 minutes. If you didn't request this, you can ignore this email.
                Need help? Write to %2$s
                """.formatted(code, supportEmail);
        String html = EmailTemplates.render("signup-code", Map.of(
                "ASSETS_URL", assetsUrl,
                "CODE", code,
                "EXPIRES_IN", "10 minutes",
                "SUPPORT_EMAIL", supportEmail,
                "RECIPIENT_EMAIL", toEmail));

        send(toEmail, "Your MnyAce verification code", text, html);
    }

    public void sendPasswordResetEmail(String toEmail, String code) {
        String text = """
                Your MnyAce password reset code is:

                %1$s

                This code expires in 15 minutes. If you didn't request this, you can ignore this email; your password has not changed.
                Need help? Write to %2$s
                """.formatted(code, supportEmail);
        String html = EmailTemplates.render("password-reset-code", Map.of(
                "ASSETS_URL", assetsUrl,
                "CODE", code,
                "EXPIRES_IN", "15 minutes",
                "SUPPORT_EMAIL", supportEmail,
                "RECIPIENT_EMAIL", toEmail));

        send(toEmail, "Your MnyAce password reset code", text, html);
    }

    /** Sends plain text and HTML together, so mail apps that cannot show HTML still get the text. Replies go to the support mailbox. */
    private void send(String toEmail, String subject, String text, String html) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setReplyTo(supportEmail);
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(text, html);
            mailSender.send(message);
        } catch (MessagingException e) {
            throw new MailSendException("Could not build the email", e);
        }
    }
}
