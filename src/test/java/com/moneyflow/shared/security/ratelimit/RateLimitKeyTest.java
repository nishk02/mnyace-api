package com.moneyflow.shared.security.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitKeyTest {

    @Test
    void ipv4_isUsedAsIs() {
        assertThat(RateLimitFilter.bucketKeyFor("203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void ipv6_inTheSameSlash64_shareOneKey() {
        assertThat(RateLimitFilter.bucketKeyFor("2001:db8:1:2::1"))
                .isEqualTo(RateLimitFilter.bucketKeyFor("2001:db8:1:2:ffff:ffff:ffff:ffff"))
                .isEqualTo(RateLimitFilter.bucketKeyFor("2001:0db8:0001:0002:0000:0000:0000:0042"));
    }

    @Test
    void ipv6_inDifferentSlash64s_getDifferentKeys() {
        assertThat(RateLimitFilter.bucketKeyFor("2001:db8:1:2::1"))
                .isNotEqualTo(RateLimitFilter.bucketKeyFor("2001:db8:1:3::1"));
    }

    @Test
    void ipv4MappedIpv6_collapsesToTheIpv4Address() {
        assertThat(RateLimitFilter.bucketKeyFor("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void unparseableValue_isUsedAsIs() {
        assertThat(RateLimitFilter.bucketKeyFor("not:an:ip:at:all:zz")).isEqualTo("not:an:ip:at:all:zz");
    }
}
