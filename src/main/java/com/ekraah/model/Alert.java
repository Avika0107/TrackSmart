package com.ekraah.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** One in-app delay alert (bell menu). Only masked identifiers are kept, like the audit log. */
@Document("alerts")
public class Alert {

    public enum Kind { HIGH_RISK, EXCEPTION, OUT_FOR_DELIVERY }

    @Id
    private String id;
    private String userId;
    private String orderId;          // may be null for an order alerted before its first save
    private String trackingNumber;   // dedupe key with userId + kind (the order id may not exist yet)
    private Kind kind;
    private String message;
    private Instant createdAt;
    private boolean read;

    public static Alert of(Order order, Kind kind, String message) {
        Alert a = new Alert();
        a.userId = order.getUserId();
        a.orderId = order.getId();
        a.trackingNumber = order.getTrackingNumber();
        a.kind = kind;
        a.message = message;
        a.createdAt = Instant.now();
        return a;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getTrackingNumber() { return trackingNumber; }
    public void setTrackingNumber(String trackingNumber) { this.trackingNumber = trackingNumber; }
    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }
}
