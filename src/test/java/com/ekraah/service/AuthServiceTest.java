package com.ekraah.service;

import com.ekraah.config.AppProperties;
import com.ekraah.exception.ApiException;
import com.ekraah.model.User;
import com.ekraah.repository.UserRepository;
import com.ekraah.security.JwtService;
import com.ekraah.security.LoginThrottle;
import com.ekraah.security.OtpService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Password login rules: the phone number alone (even with a valid OTP) must
 * never be enough to get a JWT for an account that has a password.
 */
class AuthServiceTest {

    private static final String PHONE = "9999999999";
    private static final String PASSWORD = "correct horse battery";

    private AuthService service;
    private OtpService otpService;
    private UserRepository users;
    private PasswordEncoder encoder;
    private LoginThrottle throttle;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getOtp().setFixed("123456");
        props.getJwt().setSecret("test-secret-0123456789abcdef0123456789");

        encoder = new BCryptPasswordEncoder();
        otpService = new OtpService((phone, otp) -> { /* no-op test sender */ }, encoder, props);
        JwtService jwt = new JwtService(props);
        users = mock(UserRepository.class);
        when(users.findByInboundAlias(any())).thenReturn(Optional.empty());
        throttle = new LoginThrottle();
        service = new AuthService(otpService, jwt, users, encoder, new InboundAliases(users), throttle);
    }

    private User userWithPassword() {
        User u = User.of(PHONE, "rahul55", "Rahul");
        u.setPasswordHash(encoder.encode(PASSWORD));
        return u;
    }

    // ---- login -------------------------------------------------------------

    @Test
    void loginWithCorrectPasswordIssuesToken() {
        when(users.findByPhone(PHONE)).thenReturn(Optional.of(userWithPassword()));

        var result = service.login("+91 " + PHONE, PASSWORD);

        assertThat(result.token()).isNotBlank();
        assertThat(result.user().getPhone()).isEqualTo(PHONE);
    }

    @Test
    void loginWithWrongPasswordIsUnauthorized() {
        when(users.findByPhone(PHONE)).thenReturn(Optional.of(userWithPassword()));

        assertThatThrownBy(() -> service.login(PHONE, "wrong password"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Incorrect phone number or password")
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
    }

    @Test
    void loginForUnknownPhoneGetsSameMessageAsWrongPassword() {
        when(users.findByPhone(PHONE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(PHONE, PASSWORD))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Incorrect phone number or password");
    }

    @Test
    void loginWithoutPasswordGuidesToSignup() {
        User noPassword = User.of(PHONE, "rahul55", "Rahul");
        when(users.findByPhone(PHONE)).thenReturn(Optional.of(noPassword));

        assertThatThrownBy(() -> service.login(PHONE, PASSWORD))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No password set")
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.BAD_REQUEST);
    }

    @Test
    void loginRejectsInvalidPhone() {
        assertThatThrownBy(() -> service.login("12345", PASSWORD))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("valid 10-digit");
    }

    // ---- register (OTP + first password) ------------------------------------

    @Test
    void registerCreatesAccountWithHashedPassword() {
        when(users.findByPhone(PHONE)).thenReturn(Optional.empty());
        when(users.findByInboundAlias(any())).thenReturn(Optional.empty());
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
        otpService.requestOtp(PHONE);

        var result = service.register(PHONE, "123456", PASSWORD);

        assertThat(result.token()).isNotBlank();
        assertThat(result.user().getPasswordHash()).isNotBlank();
        assertThat(result.user().getPasswordHash()).isNotEqualTo(PASSWORD);   // stored hashed
        assertThat(encoder.matches(PASSWORD, result.user().getPasswordHash())).isTrue();
        // Random and unguessable — never derived from the phone number (it was "pp" + hex(hashCode)).
        assertThat(result.user().getInboundAlias()).matches("[a-z2-9]{12}")
                .doesNotContain(Integer.toHexString(PHONE.hashCode()));
    }

    @Test
    void registerSetsFirstPasswordForLegacyAccount() {
        User legacy = User.of(PHONE, "rahul55", "Rahul");   // no password yet
        when(users.findByPhone(PHONE)).thenReturn(Optional.of(legacy));
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
        otpService.requestOtp(PHONE);

        var result = service.register(PHONE, "123456", PASSWORD);

        assertThat(encoder.matches(PASSWORD, result.user().getPasswordHash())).isTrue();
    }

    @Test
    void registerOnExistingAccountIsConflict() {
        when(users.findByPhone(PHONE)).thenReturn(Optional.of(userWithPassword()));
        otpService.requestOtp(PHONE);

        assertThatThrownBy(() -> service.register(PHONE, "123456", PASSWORD))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Account already exists")
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
    }

    @Test
    void registerRequiresValidOtp() {
        when(users.findByPhone(PHONE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(PHONE, "000000", PASSWORD))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No active code");
    }

    @Test
    void registerRejectsShortPassword() {
        assertThatThrownBy(() -> service.register(PHONE, "123456", "short"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("8-72 characters");
    }

    // ---- reset password ------------------------------------------------------

    @Test
    void resetPasswordReplacesHashWithNewPassword() {
        User user = userWithPassword();
        when(users.findByPhone(PHONE)).thenReturn(Optional.of(user));
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));
        otpService.requestOtp(PHONE);

        service.resetPassword(PHONE, "123456", "a brand new password");

        assertThat(encoder.matches("a brand new password", user.getPasswordHash())).isTrue();
        assertThat(encoder.matches(PASSWORD, user.getPasswordHash())).isFalse();
    }

    @Test
    void resetPasswordForUnknownNumberFails() {
        when(users.findByPhone(PHONE)).thenReturn(Optional.empty());
        otpService.requestOtp(PHONE);

        assertThatThrownBy(() -> service.resetPassword(PHONE, "123456", PASSWORD))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No account found")
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
    }

    // ---- brute-force throttle --------------------------------------------------

    @Test
    void fiveWrongPasswordsLockTheNumberEvenForTheRightPassword() {
        when(users.findByPhone(PHONE)).thenReturn(Optional.of(userWithPassword()));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.login(PHONE, "wrong-guess", "10.0.0.1"))
                    .extracting(e -> ((ApiException) e).getStatus())
                    .isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        // Locked: the correct password is refused too, so the lock can't confirm a guess.
        assertThatThrownBy(() -> service.login(PHONE, PASSWORD, "10.0.0.2"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Too many failed attempts")
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void successfulLoginResetsThePhoneCounter() {
        when(users.findByPhone(PHONE)).thenReturn(Optional.of(userWithPassword()));
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.login(PHONE, "wrong-guess", "10.0.0.1")).isInstanceOf(ApiException.class);
        }
        assertThat(service.login(PHONE, PASSWORD, "10.0.0.1").token()).isNotBlank();
        // The counter restarted, so one more mistake doesn't lock the account.
        assertThatThrownBy(() -> service.login(PHONE, "wrong-guess", "10.0.0.1"))
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.UNAUTHORIZED);
    }

    @Test
    void oneIpTryingManyNumbersIsLocked() {
        when(users.findByPhone(any())).thenReturn(Optional.empty());
        for (int i = 0; i < 20; i++) {
            String phone = "98765" + String.format("%05d", i);
            assertThatThrownBy(() -> service.login(phone, "guess", "203.0.113.9")).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> service.login("9123456789", "guess", "203.0.113.9"))
                .hasMessageContaining("Too many failed attempts");
        // Another client is unaffected.
        assertThatThrownBy(() -> service.login("9123456789", "guess", "198.51.100.7"))
                .hasMessageContaining("Incorrect phone number or password");
    }
}
