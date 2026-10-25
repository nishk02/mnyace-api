package com.moneyflow.shared.util;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class EmailNormalizerTest {

    @Test
    void lowercasesAndTrims() {
        assertThat(EmailNormalizer.normalize("  Alice@Example.COM ")).isEqualTo("alice@example.com");
    }

    @Test
    void nullStaysNull() {
        assertThat(EmailNormalizer.normalize(null)).isNull();
    }

    @Test
    void isNotAffectedByTheDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR")); // plain toLowerCase() turns 'I' into a dotless 'ı'
            assertThat(EmailNormalizer.normalize("INFO@EXAMPLE.COM")).isEqualTo("info@example.com");
        } finally {
            Locale.setDefault(original);
        }
    }
}
