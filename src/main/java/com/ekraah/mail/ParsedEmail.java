package com.ekraah.mail;

import com.ekraah.model.OrderStatus;
import com.ekraah.model.Platform;

import java.time.LocalDate;

/** Only the tracking facts extracted from an email. No body, no item names, no prices. */
public record ParsedEmail(
        Platform platform,
        String courier,
        String trackingNumber,
        OrderStatus status,
        boolean delayed,
        boolean cancelled,
        LocalDate estimatedDelivery) {

    public boolean hasTracking() { return trackingNumber != null && !trackingNumber.isBlank(); }
}
