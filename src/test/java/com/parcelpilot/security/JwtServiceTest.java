package com.parcelpilot.security;

import com.parcelpilot.config.AppProperties;
import com.parcelpilot.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private JwtService jwt;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getJwt().setSecret("test-secret-key-that-is-long-enough-0123456789");
        props.getJwt().setTtlMinutes(30);
        jwt = new JwtService(props);
    }

    @Test
    void roundtrip() {
        String token = jwt.issue("user-1", "9876543210");
        assertThat(jwt.verify(token)).isEqualTo("user-1");
    }

    @Test
    void garbageTokenRejected() {
        assertThatThrownBy(() -> jwt.verify("not.a.jwt"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Session expired");
    }

    @Test
    void tamperedTokenRejected() {
        String token = jwt.issue("user-1", "9876543210");
        String tampered = token.substring(0, token.length() - 3) + "abc";
        assertThatThrownBy(() -> jwt.verify(tampered)).isInstanceOf(ApiException.class);
    }

    @Test
    void shortSecretRejected() {
        AppProperties props = new AppProperties();
        props.getJwt().setSecret("too-short");
        assertThatThrownBy(() -> new JwtService(props)).isInstanceOf(Exception.class);
    }
}
