package com.parcelpilot.model;

import java.util.Set;

public enum OrderStatus {
    ORDERED, SHIPPED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION, UNKNOWN;

    /** Statuses that still need periodic live refresh. */
    public static final Set<OrderStatus> ACTIVE = Set.of(ORDERED, SHIPPED, IN_TRANSIT, OUT_FOR_DELIVERY, EXCEPTION);
}
