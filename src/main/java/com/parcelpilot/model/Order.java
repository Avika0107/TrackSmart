package com.parcelpilot.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document("orders")
public class Order {

    @Id
    private String id;
    private String userId;
    private Platform platform;
    private String trackingNumber;
    private String courier;
    private OrderStatus status = OrderStatus.UNKNOWN;
    private TrackingMode trackingMode = TrackingMode.API;
    private Instant estimatedDelivery;
    private String currentCity;
    private Instant lastUpdated;
    private OrderSource source = OrderSource.MANUAL;
    private String sourceMessageId;        // for email dedupe (unique per user)
    private List<OrderEvent> events = new ArrayList<>();
    private RiskInfo risk;
    private Instant deliveredAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Platform getPlatform() { return platform; }
    public void setPlatform(Platform platform) { this.platform = platform; }
    public String getTrackingNumber() { return trackingNumber; }
    public void setTrackingNumber(String trackingNumber) { this.trackingNumber = trackingNumber; }
    public String getCourier() { return courier; }
    public void setCourier(String courier) { this.courier = courier; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public TrackingMode getTrackingMode() { return trackingMode; }
    public void setTrackingMode(TrackingMode trackingMode) { this.trackingMode = trackingMode; }
    public Instant getEstimatedDelivery() { return estimatedDelivery; }
    public void setEstimatedDelivery(Instant estimatedDelivery) { this.estimatedDelivery = estimatedDelivery; }
    public String getCurrentCity() { return currentCity; }
    public void setCurrentCity(String currentCity) { this.currentCity = currentCity; }
    public Instant getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(Instant lastUpdated) { this.lastUpdated = lastUpdated; }
    public OrderSource getSource() { return source; }
    public void setSource(OrderSource source) { this.source = source; }
    public String getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(String sourceMessageId) { this.sourceMessageId = sourceMessageId; }
    public List<OrderEvent> getEvents() { return events; }
    public void setEvents(List<OrderEvent> events) { this.events = events; }
    public RiskInfo getRisk() { return risk; }
    public void setRisk(RiskInfo risk) { this.risk = risk; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }
}
