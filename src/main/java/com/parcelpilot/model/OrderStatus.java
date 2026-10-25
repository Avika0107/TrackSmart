package com.parcelpilot.model;

import java.util.Set;

public enum OrderStatus {
    ORDERED, SHIPPED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION, CANCELLED, UNKNOWN;

    /** Statuses that still need periodic live refresh. */
    public static final Set<OrderStatus> ACTIVE = Set.of(ORDERED, SHIPPED, IN_TRANSIT, OUT_FOR_DELIVERY, EXCEPTION);

    /** Terminal statuses: no further live refresh, no risk scoring. */
    public static final Set<OrderStatus> TERMINAL = Set.of(DELIVERED, CANCELLED);

    public boolean isTerminal() { return TERMINAL.contains(this); }
}
