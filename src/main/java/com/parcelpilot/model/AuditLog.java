package com.parcelpilot.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Plain-language trail of what the mail pipeline did. NEVER contains raw email
 * text, item names, prices or addresses — only domain, action and a masked summary.
 */
@Document("auditLog")
public class AuditLog {

    @Id
    private String id;
    private String userId;
    private Instant time;
    private String senderDomain;
    private AuditAction action;
    private String summary;

    public AuditLog() {}

    public AuditLog(String userId, String senderDomain, AuditAction action, String summary) {
        this.userId = userId;
        this.time = Instant.now();
        this.senderDomain = senderDomain;
        this.action = action;
        this.summary = summary;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Instant getTime() { return time; }
    public void setTime(Instant time) { this.time = time; }
    public String getSenderDomain() { return senderDomain; }
    public void setSenderDomain(String senderDomain) { this.senderDomain = senderDomain; }
    public AuditAction getAction() { return action; }
    public void setAction(AuditAction action) { this.action = action; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
}
