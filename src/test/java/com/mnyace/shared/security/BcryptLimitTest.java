package com.mnyace.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Documents the BCrypt behavior the 72-byte password limit exists for.
class BcryptLimitTest {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void encodingMoreThan72Bytes_isRefused() {
        assertThatThrownBy(() -> encoder.encode("a".repeat(73))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matching_onlyLooksAtTheFirst72Bytes() {
        String hash = encoder.encode("a".repeat(72));

        assertThat(encoder.matches("a".repeat(100), hash)).isTrue();
    }
}
