package com.ekraah.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Retry bookkeeping for an email whose processing failed. Holds no email content. */
@Document("mail_failures")
public class MailFailure {

    @Id
    private String messageId;
    private int attempts;
    private Instant firstFailedAt;
    private Instant lastFailedAt;
    private boolean gaveUp;

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public Instant getFirstFailedAt() { return firstFailedAt; }
    public void setFirstFailedAt(Instant firstFailedAt) { this.firstFailedAt = firstFailedAt; }
    public Instant getLastFailedAt() { return lastFailedAt; }
    public void setLastFailedAt(Instant lastFailedAt) { this.lastFailedAt = lastFailedAt; }
    public boolean isGaveUp() { return gaveUp; }
    public void setGaveUp(boolean gaveUp) { this.gaveUp = gaveUp; }
}
