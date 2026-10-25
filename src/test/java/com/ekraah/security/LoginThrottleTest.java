package com.ekraah.security;

import com.ekraah.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginThrottleTest {

    /** A clock the test can move forward. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-26T10:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void lockLiftsWhenTheWindowPasses() {
        TestClock clock = new TestClock();
        LoginThrottle throttle = new LoginThrottle(clock);
        for (int i = 0; i < LoginThrottle.MAX_FAILS_PER_PHONE; i++) throttle.recordFailure("9999999999", "1.1.1.1");

        assertThatThrownBy(() -> throttle.checkAllowed("9999999999", "2.2.2.2"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("15 minutes");

        clock.now = clock.now.plus(LoginThrottle.WINDOW).plusSeconds(1);
        assertThatCode(() -> throttle.checkAllowed("9999999999", "2.2.2.2")).doesNotThrowAnyException();
    }

    @Test
    void underTheLimitIsAllowed() {
        LoginThrottle throttle = new LoginThrottle(new TestClock());
        for (int i = 0; i < LoginThrottle.MAX_FAILS_PER_PHONE - 1; i++) throttle.recordFailure("9999999999", "1.1.1.1");
        assertThatCode(() -> throttle.checkAllowed("9999999999", "1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void remainingTimeIsRoundedUpToWholeMinutes() {
        TestClock clock = new TestClock();
        LoginThrottle throttle = new LoginThrottle(clock);
        for (int i = 0; i < LoginThrottle.MAX_FAILS_PER_PHONE; i++) throttle.recordFailure("9999999999", null);
        clock.now = clock.now.plus(Duration.ofMinutes(14)).plusSeconds(30);
        assertThatThrownBy(() -> throttle.checkAllowed("9999999999", null))
                .hasMessageContaining("1 minute,");
    }
}
