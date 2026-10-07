package com.parcelpilot.model;

import java.time.Instant;

public class OrderEvent {
    private String description;
    private String location;
    private Instant time;

    public OrderEvent() {}

    public OrderEvent(String description, String location, Instant time) {
        this.description = description;
        this.location = location;
        this.time = time;
    }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public Instant getTime() { return time; }
    public void setTime(Instant time) { this.time = time; }
}
