package com.ekraah.security;

import com.ekraah.exception.ApiException;
import com.ekraah.model.Session;
import com.ekraah.repository.SessionRepository;
import com.ekraah.util.Mask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Server-side sessions behind the short-lived access tokens (see Session). Refresh tokens are
 * 256-bit random values; only their SHA-256 hash is stored, they rotate on every use, and
 * replaying an already-rotated one revokes the session (it means two parties hold the token).
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    public static final Duration LIFETIME = Duration.ofDays(30);

    /** A session plus the raw refresh token to hand to the client — the only time it exists in clear. */
    public record Issued(Session session, String refreshToken) {}

    private final SessionRepository sessions;
    private final SecureRandom random = new SecureRandom();

    public SessionService(SessionRepository sessions) {
        this.sessions = sessions;
    }

    public Issued create(String userId, Session.Kind kind) {
        String raw = newToken();
        Session s = new Session();
        s.setUserId(userId);
        s.setKind(kind);
        s.setTokenHash(hash(raw));
        s.setCreatedAt(Instant.now());
        s.setLastUsedAt(Instant.now());
        s.setExpiresAt(Instant.now().plus(LIFETIME));
        return new Issued(sessions.save(s), raw);
    }

    /** Exchanges a refresh token for a rotated one. Invalid, expired, revoked or replayed → 401. */
    public Issued refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) throw expired();
        String h = hash(rawRefreshToken);
        Session s = sessions.findByTokenHash(h).orElse(null);
        if (s == null) {
            // An already-rotated token came back: the legitimate client and someone else both
            // have it. Revoke the session so neither copy keeps working.
            sessions.findByPreviousTokenHash(h).ifPresent(stolen -> {
                revoke(stolen, "refresh token reuse");
                log.warn("Refresh token reuse detected; session revoked (user {})", Mask.tracking(stolen.getUserId()));
            });
            throw expired();
        }
        if (!s.isActive(Instant.now())) throw expired();
        String raw = newToken();
        s.setPreviousTokenHash(s.getTokenHash());
        s.setTokenHash(hash(raw));
        s.setLastUsedAt(Instant.now());
        return new Issued(sessions.save(s), raw);
    }

    /** Per-request check made by JwtAuthFilter: the access token's session must still be active. */
    public boolean isActive(String sessionId, String userId) {
        if (sessionId == null || userId == null) return false;
        return sessions.findById(sessionId)
                .filter(s -> userId.equals(s.getUserId()) && s.isActive(Instant.now()))
                .isPresent();
    }

    /** Logout: revokes the session the refresh token belongs to; unknown tokens are ignored. */
    public void revokeByRefreshToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) return;
        String h = hash(rawRefreshToken);
        // Also the previous token: a logout racing a refresh (another tab) arrives with the token
        // that refresh just rotated away — the user still asked for this session to end.
        sessions.findByTokenHash(h).or(() -> sessions.findByPreviousTokenHash(h))
                .ifPresent(s -> revoke(s, "logout"));
    }

    /** Password reset: every device must log in again with the new password. */
    public int revokeAll(String userId, String reason) {
        List<Session> open = sessions.findByUserIdAndRevokedAtIsNull(userId);
        open.forEach(s -> revoke(s, reason));
        return open.size();
    }

    public void deleteForUser(String userId) {
        sessions.deleteByUserId(userId);
    }

    /** Weekly cleanup: expired sessions are useless; keep revoked ones until they expire (reuse detection). */
    public long deleteExpired() {
        return sessions.deleteByExpiresAtBefore(Instant.now());
    }

    private void revoke(Session s, String reason) {
        if (s.getRevokedAt() != null) return;
        s.setRevokedAt(Instant.now());
        s.setRevokedReason(reason);
        sessions.save(s);
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException expired() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Session expired, please log in again.");
    }
}
