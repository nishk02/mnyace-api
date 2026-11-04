package com.mnyace.shared.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailMaskerTest {

    @Test
    void keepsTheFirstCharacterAndTheDomain() {
        assertThat(EmailMasker.mask("nishantsep2@gmail.com")).isEqualTo("n***@gmail.com");
    }

    @Test
    void aOneCharacterNameStillHidesNothingMoreThanItHas() {
        assertThat(EmailMasker.mask("a@x.com")).isEqualTo("a***@x.com");
    }

    @Test
    void thingsThatAreNotEmailsAreFullyMasked() {
        assertThat(EmailMasker.mask("not-an-email")).isEqualTo("***");
        assertThat(EmailMasker.mask("@x.com")).isEqualTo("***");
        assertThat(EmailMasker.mask("")).isEqualTo("***");
    }

    @Test
    void nullStaysNull() {
        assertThat(EmailMasker.mask(null)).isNull();
    }
}
