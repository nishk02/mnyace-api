package com.mnyace.shared.email;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailServiceTest {
    private JavaMailSender mailSender;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        emailService = new EmailService(mailSender);
        ReflectionTestUtils.setField(emailService, "inviteSignupUrl", "https://example.com/signup");
        ReflectionTestUtils.setField(emailService, "assetsUrl", "https://cdn.example.com/email");
        ReflectionTestUtils.setField(emailService, "fromAddress", "MnyAce Team <no-reply@example.com>");
    }

    @Test
    void theInviteGoesOutAsTextAndHtml_withTheInviterAndTheLink() throws Exception {
        emailService.sendInviteEmail("friend@example.com", "tok-1", "Sarah Johnson", "sarah@example.com");

        MimeMessage sent = sent();
        assertThat(sent.getSubject()).isEqualTo("You're invited to MnyAce");
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("friend@example.com");
        assertThat(sent.getFrom()[0].toString()).contains("no-reply@example.com");
        String text = part(sent, "text/plain");
        String html = part(sent, "text/html");
        assertThat(text).contains("Sarah Johnson").contains("https://example.com/signup?token=tok-1")
                .contains("What is MnyAce?").contains("automated email");
        assertThat(html).contains("invited you to MnyAce.").doesNotContain("someone").contains("automated email").doesNotContain("Contact Sarah").contains("Sarah Johnson").contains("sarah@example.com").contains("Create your account")
                .contains("https://cdn.example.com/email/logo-v1.png").contains("https://cdn.example.com/email/icon-cash-v1.png")
                .contains("https://cdn.example.com/email/icon-piggy-bank-v1.png")
                .contains("What is MnyAce?").contains("Track your finances").contains("Define realistic goals")
                .doesNotContain("planned amounts")
                .contains("https://example.com/signup?token=tok-1").doesNotContain("{{");
    }

    @Test
    void theCodeEmails_carryTheCodeInBothParts() throws Exception {
        emailService.sendOtpEmail("friend@example.com", "482913");
        MimeMessage otp = sent();
        assertThat(part(otp, "text/plain")).contains("482913").contains("10 minutes");
        assertThat(part(otp, "text/html")).contains("482913").contains("10 minutes").contains("automated email");

        emailService.sendPasswordResetEmail("friend@example.com", "135790");
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, org.mockito.Mockito.times(2)).send(captor.capture());
        MimeMessage reset = saved(captor.getAllValues().get(1));
        assertThat(reset.getSubject()).isEqualTo("Your MnyAce password reset code");
        assertThat(part(reset, "text/plain")).contains("135790").contains("15 minutes");
        assertThat(part(reset, "text/html")).contains("135790").contains("15 minutes").contains("automated email");
    }

    private MimeMessage sent() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return saved(captor.getValue());
    }

    /** Sending finalizes a message's headers; without it a part reports the wrong content type. */
    private static MimeMessage saved(MimeMessage message) {
        try {
            message.saveChanges();
        } catch (jakarta.mail.MessagingException e) {
            throw new IllegalStateException(e);
        }
        return message;
    }

    /** The body of the first part of the given type, searching nested multiparts. */
    private static String part(Part message, String type) throws Exception {
        if (message.isMimeType(type)) {
            return (String) message.getContent();
        }
        if (message.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) message.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                String found = part(multipart.getBodyPart(i), type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
