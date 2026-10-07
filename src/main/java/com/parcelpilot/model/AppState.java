package com.parcelpilot.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Tiny key/value store for inbox-wide state, e.g. the Gmail forwarding confirmation code. */
@Document("appState")
public class AppState {

    public static final String FORWARDING_CODE_KEY = "forwardingCode";

    @Id
    private String key;
    private String value;
    private Instant updatedAt;

    public AppState() {}

    public AppState(String key, String value) {
        this.key = key;
        this.value = value;
        this.updatedAt = Instant.now();
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
