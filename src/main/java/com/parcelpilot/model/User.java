package com.parcelpilot.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("users")
public class User {

    @Id
    private String id;
    @Indexed(unique = true)
    private String phone;
    @Indexed(unique = true)
    private String inboundAlias;   // e.g. "rahul55" -> demoinbox+rahul55@gmail.com
    private String displayName;
    private String passwordHash;   // BCrypt; null until the user sets a password via signup/reset
    private Instant createdAt;
    private boolean forwardingVerified;
    private UserSettings settings = new UserSettings();

    public User() {}

    public static User of(String phone, String alias, String displayName) {
        User u = new User();
        u.phone = phone;
        u.inboundAlias = alias;
        u.displayName = displayName;
        u.createdAt = Instant.now();
        return u;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getInboundAlias() { return inboundAlias; }
    public void setInboundAlias(String inboundAlias) { this.inboundAlias = inboundAlias; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public boolean isForwardingVerified() { return forwardingVerified; }
    public void setForwardingVerified(boolean forwardingVerified) { this.forwardingVerified = forwardingVerified; }
    public UserSettings getSettings() { return settings; }
    public void setSettings(UserSettings settings) { this.settings = settings; }
}
