package com.ekraah.security;

import com.ekraah.config.AppProperties;
import com.ekraah.exception.ApiException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(AppProperties props) {
        this.key = Keys.hmacShaKeyFor(props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofMinutes(props.getJwt().getTtlMinutes());
    }

    /** Scope "web" can call the whole API; "extension" only the import endpoint (JwtAuthFilter). */
    public static final String SCOPE_WEB = "web";
    public static final String SCOPE_EXTENSION = "extension";

    /** What a verified access token says. sessionId is null for tokens issued without a session. */
    public record AccessToken(String userId, String sessionId, String scope) {}

    public String issue(String userId, String phone) {
        return issue(userId, phone, null, SCOPE_WEB);
    }

    /** Short-lived access token bound to a server-side session (SessionService), which every request re-checks. */
    public String issue(String userId, String phone, String sessionId, String scope) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(userId)
                .claim("phone", phone)
                .claim("scope", scope)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)));
        if (sessionId != null) builder.claim("sid", sessionId);
        return builder.signWith(key).compact();
    }

    /** @return the userId (subject) if the token is valid. */
    public String verify(String token) {
        return verifyAccess(token).userId();
    }

    public AccessToken verifyAccess(String token) {
        try {
            var claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token)
                    .getPayload();
            String scope = claims.get("scope", String.class);
            return new AccessToken(claims.getSubject(), claims.get("sid", String.class), scope == null ? SCOPE_WEB : scope);
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Session expired, please log in again.");
        }
    }

    public long ttlSeconds() { return ttl.toSeconds(); }
}
