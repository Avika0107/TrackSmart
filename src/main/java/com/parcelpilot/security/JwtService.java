package com.parcelpilot.security;

import com.parcelpilot.config.AppProperties;
import com.parcelpilot.exception.ApiException;
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

    public String issue(String userId, String phone) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim("phone", phone)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** @return the userId (subject) if the token is valid. */
    public String verify(String token) {
        try {
            return Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Session expired, please log in again.");
        }
    }

    public long ttlSeconds() { return ttl.toSeconds(); }
}
