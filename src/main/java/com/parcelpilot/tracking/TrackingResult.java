package com.parcelpilot.tracking;

import com.parcelpilot.model.OrderEvent;
import com.parcelpilot.model.OrderStatus;

import java.time.Instant;
import java.util.List;

public record TrackingResult(
        OrderStatus status,
        String currentCity,
        Instant estimatedDelivery,
        Instant deliveredAt,
        List<OrderEvent> events,
        String provider) {
}
