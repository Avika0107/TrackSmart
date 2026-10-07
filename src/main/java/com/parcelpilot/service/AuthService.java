package com.parcelpilot.service;

import com.parcelpilot.exception.ApiException;
import com.parcelpilot.model.User;
import com.parcelpilot.repository.UserRepository;
import com.parcelpilot.security.JwtService;
import com.parcelpilot.security.OtpService;
import com.parcelpilot.util.Mask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Phone + password login. The phone number alone is never enough: OTP verifies
 * phone possession only during signup and password reset. The phone is ONLY for
 * login — order data arrives via forwarded emails, because retailers do not
 * share order data by phone number.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final OtpService otp;
    private final JwtService jwt;
    private final UserRepository users;
    private final PasswordEncoder encoder;

    public AuthService(OtpService otp, JwtService jwt, UserRepository users, PasswordEncoder encoder) {
        this.otp = otp;
        this.jwt = jwt;
        this.users = users;
        this.encoder = encoder;
    }

    /** Strips formatting; expects a 10-digit Indian number (accepts +91 / 91 prefixes). */
    public String normalizePhone(String raw) {
        if (raw == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Phone number is required.");
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() == 12 && digits.startsWith("91")) digits = digits.substring(2);
        if (digits.length() != 10 || !digits.matches("[6-9]\\d{9}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Enter a valid 10-digit Indian mobile number.");
        }
        return digits;
    }

    public void requestOtp(String phone) {
        otp.requestOtp(normalizePhone(phone));
    }

    public record AuthResult(String token, long expiresIn, User user) {}

    /** Password login — verifying possession of the phone is not enough anymore. */
    public AuthResult login(String phone, String password) {
        String normalized = normalizePhone(phone);
        if (password == null || password.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password is required.");
        }
        User user = users.findByPhone(normalized)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Incorrect phone number or password."));
        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "No password set for this number yet — use Create account to set one.");
        }
        if (!encoder.matches(password, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Incorrect phone number or password.");
        }
        return new AuthResult(jwt.issue(user.getId(), normalized), jwt.ttlSeconds(), user);
    }

    /**
     * Signup with OTP: creates the account and sets its first password. Also the
     * one-time password setup path for accounts that were created without one.
     */
    public AuthResult register(String phone, String code, String password) {
        String normalized = normalizePhone(phone);
        validatePassword(password);
        Optional<User> existing = users.findByPhone(normalized);
        if (existing.isPresent() && hasPassword(existing.get())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Account already exists. Please log in, or reset your password.");
        }
        otp.verify(normalized, code);   // possession check BEFORE creating anything persistent
        User user = existing.orElseGet(() -> provision(normalized));
        user.setPasswordHash(encoder.encode(password));
        User saved = users.save(user);
        log.info("Password set for {}", Mask.phone(normalized));
        return new AuthResult(jwt.issue(saved.getId(), normalized), jwt.ttlSeconds(), saved);
    }

    /** Forgot password: OTP proves possession, then the password is replaced. No JWT is issued. */
    public void resetPassword(String phone, String code, String password) {
        String normalized = normalizePhone(phone);
        validatePassword(password);
        User user = users.findByPhone(normalized)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No account found for this number."));
        otp.verify(normalized, code);
        user.setPasswordHash(encoder.encode(password));
        users.save(user);
        log.info("Password reset for {}", Mask.phone(normalized));
    }

    private static boolean hasPassword(User u) {
        return u.getPasswordHash() != null && !u.getPasswordHash().isBlank();
    }

    private static void validatePassword(String password) {
        if (password == null || password.length() < 8 || password.length() > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password must be 8-72 characters.");
        }
    }

    private User provision(String phone) {
        String alias = uniqueAlias(phone);
        User user = User.of(phone, alias, "Shopper " + phone.substring(phone.length() - 4));
        User saved = users.save(user);
        log.info("Provisioned user {} with alias '{}'", phone.substring(0, 2) + "****" + phone.substring(6), alias);
        return saved;
    }

    private String uniqueAlias(String phone) {
        String base = "pp" + Integer.toHexString(phone.hashCode());
        String candidate = base;
        int suffix = 1;
        while (users.findByInboundAlias(candidate).isPresent()) {
            candidate = base + suffix++;
        }
        return candidate;
    }

    public Optional<User> findByPhone(String phone) {
        return users.findByPhone(normalizePhone(phone));
    }
}
