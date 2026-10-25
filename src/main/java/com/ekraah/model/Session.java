package com.ekraah.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One login (browser or extension). Access tokens (JWT, 15 min) carry this session's id, and
 * every API request checks it is still active — so logout, password reset or account deletion
 * take effect immediately instead of when a long-lived token expires.
 *
 * The refresh token itself is never stored, only its SHA-256 hash. It is rotated on every use;
 * the previous hash is kept so a replay of an already-used token (a stolen copy) is detected and
 * the whole session revoked.
 */
@Document("sessions")
public class Session {

    public enum Kind { WEB, EXTENSION }

    @Id
    private String id;
    private String userId;
    private Kind kind;
    private String tokenHash;
    private String previousTokenHash;
    private Instant createdAt;
    private Instant lastUsedAt;
    private Instant expiresAt;
    private Instant revokedAt;
    private String revokedReason;

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt != null && now.isBefore(expiresAt);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getPreviousTokenHash() { return previousTokenHash; }
    public void setPreviousTokenHash(String previousTokenHash) { this.previousTokenHash = previousTokenHash; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
    public String getRevokedReason() { return revokedReason; }
    public void setRevokedReason(String revokedReason) { this.revokedReason = revokedReason; }
}
