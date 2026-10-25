package com.ekraah.service;

import com.ekraah.exception.ApiException;
import com.ekraah.model.Session;
import com.ekraah.model.User;
import com.ekraah.repository.UserRepository;
import com.ekraah.security.JwtService;
import com.ekraah.security.LoginThrottle;
import com.ekraah.security.OtpService;
import com.ekraah.security.SessionService;
import com.ekraah.util.Mask;
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
    private final InboundAliases aliases;
    private final LoginThrottle throttle;
    private final SessionService sessions;

    public AuthService(OtpService otp, JwtService jwt, UserRepository users, PasswordEncoder encoder,
                       InboundAliases aliases, LoginThrottle throttle, SessionService sessions) {
        this.otp = otp;
        this.jwt = jwt;
        this.users = users;
        this.encoder = encoder;
        this.aliases = aliases;
        this.throttle = throttle;
        this.sessions = sessions;
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

    /** token: 15-min access token. refreshToken: renews it (HttpOnly cookie for the web app). */
    public record AuthResult(String token, long expiresIn, User user, String refreshToken) {}

    /** Opens a session and issues its first access token. Extension sessions get the narrow scope. */
    private AuthResult start(User user, String phone, Session.Kind kind) {
        SessionService.Issued issued = sessions.create(user.getId(), kind);
        String scope = kind == Session.Kind.EXTENSION ? JwtService.SCOPE_EXTENSION : JwtService.SCOPE_WEB;
        return new AuthResult(jwt.issue(user.getId(), phone, issued.session().getId(), scope),
                jwt.ttlSeconds(), user, issued.refreshToken());
    }

    /** Refresh: rotates the session's refresh token and issues a new access token. */
    public AuthResult refresh(String refreshToken) {
        SessionService.Issued issued = sessions.refresh(refreshToken);
        User user = users.findById(issued.session().getUserId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Session expired, please log in again."));
        String scope = issued.session().getKind() == Session.Kind.EXTENSION ? JwtService.SCOPE_EXTENSION : JwtService.SCOPE_WEB;
        return new AuthResult(jwt.issue(user.getId(), user.getPhone(), issued.session().getId(), scope),
                jwt.ttlSeconds(), user, issued.refreshToken());
    }

    public void logout(String refreshToken) {
        sessions.revokeByRefreshToken(refreshToken);
    }

    /** Password login — verifying possession of the phone is not enough anymore. */
    public AuthResult login(String phone, String password) {
        return login(phone, password, null, Session.Kind.WEB);
    }

    /**
     * Password login, throttled per phone and per client IP (LoginThrottle): unknown numbers and
     * wrong passwords both count as failures and get the same message, so the lock can't be used
     * to discover which numbers have accounts.
     */
    public AuthResult login(String phone, String password, String clientIp) {
        return login(phone, password, clientIp, Session.Kind.WEB);
    }

    public AuthResult login(String phone, String password, String clientIp, Session.Kind kind) {
        String normalized = normalizePhone(phone);
        if (password == null || password.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Password is required.");
        }
        throttle.checkAllowed(normalized, clientIp);
        User user = users.findByPhone(normalized).orElse(null);
        if (user == null) {
            throttle.recordFailure(normalized, clientIp);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Incorrect phone number or password.");
        }
        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "No password set for this number yet — use Create account to set one.");
        }
        if (!encoder.matches(password, user.getPasswordHash())) {
            throttle.recordFailure(normalized, clientIp);
            log.warn("Failed login for {}", Mask.phone(normalized));
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Incorrect phone number or password.");
        }
        throttle.recordSuccess(normalized);
        return start(user, normalized, kind);
    }

    /**
     * Signup with OTP: creates the account and sets its first password. Also the
     * one-time password setup path for accounts that were created without one.
     */
    public AuthResult register(String phone, String code, String password) {
        return register(phone, code, password, Session.Kind.WEB);
    }

    public AuthResult register(String phone, String code, String password, Session.Kind kind) {
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
        return start(saved, normalized, kind);
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
        // Whoever knew the old password may still be logged in somewhere: end every session.
        int revoked = sessions.revokeAll(user.getId(), "password reset");
        log.info("Password reset for {} ({} session(s) ended)", Mask.phone(normalized), revoked);
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
        // Random, unguessable alias — never derived from the phone (see InboundAliases).
        User user = User.of(phone, aliases.newUniqueAlias(), "Shopper " + phone.substring(phone.length() - 4));
        User saved = users.save(user);
        log.info("Provisioned user {}", Mask.phone(phone));   // the alias is a secret: never logged
        return saved;
    }

    public Optional<User> findByPhone(String phone) {
        return users.findByPhone(normalizePhone(phone));
    }
}
