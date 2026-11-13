package com.mnyace.shared.email;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailTemplatesTest {
    private static final Map<String, String> INVITE = Map.of(
            "ASSETS_URL", "https://cdn.example.com/email",
            "ACCEPT_URL", "https://example.com/signup?token=abc_123-XYZ",
            "INVITED_BY_NAME", "Sarah Johnson",
            "INVITED_BY_EMAIL", "sarah@example.com",
            "SUPPORT_EMAIL", "help@example.com", "RECIPIENT_EMAIL", "friend@example.com");
    private static final Map<String, String> SIGNUP_CODE = Map.of(
            "ASSETS_URL", "https://cdn.example.com/email", "CODE", "482913", "EXPIRES_IN", "10 minutes", "SUPPORT_EMAIL", "help@example.com", "RECIPIENT_EMAIL", "friend@example.com");
    private static final Map<String, String> RESET_CODE = Map.of(
            "ASSETS_URL", "https://cdn.example.com/email", "CODE", "482913", "EXPIRES_IN", "15 minutes", "SUPPORT_EMAIL", "help@example.com", "RECIPIENT_EMAIL", "friend@example.com");

    @Test
    void everyTemplateRendersWithNoPlaceholderLeft() {
        for (var entry : Map.of("invite", INVITE, "signup-code", SIGNUP_CODE, "password-reset-code", RESET_CODE).entrySet()) {
            String html = EmailTemplates.render(entry.getKey(), entry.getValue());

            assertThat(html).doesNotContain("{{").doesNotContain("}}");
            entry.getValue().values().forEach(value -> assertThat(html).contains(value));
        }
    }

    @Test
    void valuesAreHtmlEscaped_soAnInviterNameCannotInjectMarkup() {
        Map<String, String> hostile = Map.of(
                "ACCEPT_URL", "https://example.com/signup?token=a&b=c",
                "ASSETS_URL", "https://cdn.example.com/email",
                "INVITED_BY_NAME", "<script>alert(1)</script>",
                "INVITED_BY_EMAIL", "a\"onmouseover=\"x@example.com",
                "SUPPORT_EMAIL", "help@example.com", "RECIPIENT_EMAIL", "friend@example.com");

        String html = EmailTemplates.render("invite", hostile);

        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;");
        assertThat(html).contains("token=a&amp;b=c").doesNotContain("\"onmouseover=\"");
    }

    @Test
    void aMissingValue_orAnUnusedOne_isAnError() {
        assertThatThrownBy(() -> EmailTemplates.render("signup-code", Map.of("ASSETS_URL", "x", "CODE", "1", "SUPPORT_EMAIL", "x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("EXPIRES_IN");
        Map<String, String> extra = new java.util.HashMap<>(SIGNUP_CODE);
        extra.put("TYPO", "x");
        assertThatThrownBy(() -> EmailTemplates.render("signup-code", extra))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TYPO");
    }

    @Test
    void anUnknownTemplate_isAnError() {
        assertThatThrownBy(() -> EmailTemplates.render("nope", Map.of()))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }

    /** Writes sample renders to target/email-previews so the design can be opened in a browser. */
    @Test
    void writesPreviewFiles() throws IOException {
        Path dir = Path.of("target", "email-previews");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("invite.html"), EmailTemplates.render("invite", INVITE));
        Files.writeString(dir.resolve("signup-code.html"), EmailTemplates.render("signup-code", SIGNUP_CODE));
        Files.writeString(dir.resolve("password-reset-code.html"), EmailTemplates.render("password-reset-code", RESET_CODE));

        assertThat(dir.resolve("invite.html")).exists();
    }
}
