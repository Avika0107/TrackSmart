package com.parcelpilot.security;

import com.parcelpilot.config.AppProperties;
import com.parcelpilot.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtpServiceTest {

    private OtpService service;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getOtp().setFixed("123456");
        props.getOtp().setMaxPerWindow(3);
        props.getOtp().setMaxAttempts(5);
        props.getOtp().setTtlMinutes(5);
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        service = new OtpService((phone, otp) -> { /* test sender: no network */ }, encoder, props);
    }

    @Test
    void correctOtpVerifies() {
        service.requestOtp("9876543210");
        assertThatCode(() -> service.verify("9876543210", "123456")).doesNotThrowAnyException();
    }

    @Test
    void wrongOtpRejected() {
        service.requestOtp("9876543210");
        assertThatThrownBy(() -> service.verify("9876543210", "000000"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Incorrect");
    }

    @Test
    void verifyWithoutRequestFails() {
        assertThatThrownBy(() -> service.verify("9876543210", "123456"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No active code");
    }

    @Test
    void tooManyWrongAttemptsLocksCode() {
        service.requestOtp("9876543210");
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.verify("9876543210", "000000"))
                    .isInstanceOf(ApiException.class);
        }
        // the 6th attempt is rate-limited before even checking the code
        assertThatThrownBy(() -> service.verify("9876543210", "123456"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Too many wrong attempts");
    }

    @Test
    void rateLimitsRequestsPerWindow() {
        service.requestOtp("9876543210");
        service.requestOtp("9876543210");
        service.requestOtp("9876543210");
        assertThatThrownBy(() -> service.requestOtp("9876543210"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Too many OTP requests");
    }
}
