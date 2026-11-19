package com.mnyace.transaction;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantKeyTest {
    @Test
    void spellingsOfOneMerchant_shareAKey() {
        assertThat(MerchantKey.of("Swiggy ")).isEqualTo("SWIGGY");
        assertThat(MerchantKey.of("SWIGGY 1234")).isEqualTo("SWIGGY");
        assertThat(MerchantKey.of("swiggy*")).isEqualTo("SWIGGY");
        assertThat(MerchantKey.of("  swiggy   instamart ")).isEqualTo("SWIGGY INSTAMART");
    }

    @Test
    void digitsAndTheListedPunctuation_areRemoved_thenSpacesCollapsedAgain() {
        assertThat(MerchantKey.of("AMZN*Mktp 12/34 - IN")).isEqualTo("AMZNMKTP IN");
        assertThat(MerchantKey.of("a_b#c@d.e-f/g")).isEqualTo("ABCDEFG");
        assertThat(MerchantKey.of("PAYTM 98 76 STORE")).isEqualTo("PAYTM STORE");
    }

    @Test
    void otherPunctuationAndLettersStay() {
        assertThat(MerchantKey.of("McDonald's & Co")).isEqualTo("MCDONALD'S & CO");
    }

    @Test
    void aNameWithNothingLeft_hasNoKey() {
        assertThat(MerchantKey.of("123 ***")).isNull();
        assertThat(MerchantKey.of("   ")).isNull();
        assertThat(MerchantKey.of(null)).isNull();
    }

    @Test
    void upperCasing_doesNotDependOnTheServerLocale() {
        java.util.Locale previous = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertThat(MerchantKey.of("ice")).isEqualTo("ICE");
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }
}
