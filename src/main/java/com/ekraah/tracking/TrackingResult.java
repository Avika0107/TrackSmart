package com.ekraah.tracking;

import com.ekraah.model.OrderEvent;
import com.ekraah.model.OrderStatus;

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
