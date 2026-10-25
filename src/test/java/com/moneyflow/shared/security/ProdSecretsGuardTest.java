package com.moneyflow.shared.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProdSecretsGuardTest {

    @Test
    void devDefaultSecret_isRejected() {
        assertThatThrownBy(() -> ProdSecretsGuard.validate(ProdSecretsGuard.DEV_DEFAULT_SECRET))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shortSecret_isRejected() {
        assertThatThrownBy(() -> ProdSecretsGuard.validate("too-short"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void emptyOrMissingSecret_isRejected() {
        assertThatThrownBy(() -> ProdSecretsGuard.validate("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ProdSecretsGuard.validate(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void strongSecret_isAccepted() {
        assertThatCode(() -> ProdSecretsGuard.validate("a".repeat(48))).doesNotThrowAnyException();
    }
}
