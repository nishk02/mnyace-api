package com.mnyace.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientInfoTest {
    @Test
    void anIpv4Address_keepsItsFirstThreeBytes() {
        assertThat(ClientInfo.truncate("203.0.113.77")).isEqualTo("203.0.113.0");
    }

    @Test
    void anIpv6Address_keepsItsFirstSixBytes() {
        assertThat(ClientInfo.truncate("2001:db8:abcd:1234:5678:9abc:def0:1111")).isEqualTo("2001:db8:abcd:0:0:0:0:0");
    }

    @Test
    void anIpv4MappedIpv6Address_isTruncatedAsIpv4() {
        assertThat(ClientInfo.truncate("::ffff:203.0.113.77")).isEqualTo("203.0.113.0");
    }

    @Test
    void anUnknownOrMalformedValue_isNotStored() {
        assertThat(ClientInfo.truncate(null)).isNull();
        assertThat(ClientInfo.truncate("unknown")).isNull();
        assertThat(ClientInfo.truncate("evil.example.com")).isNull();
    }
}
