package com.parcelpilot.tracking;

/** Strategy interface for live tracking. Implementations are selected by app.tracking.provider. */
public interface TrackingProvider {

    /** @return tracking facts, or throw if the provider is unavailable/quota-exhausted. */
    TrackingResult track(String awb, String courierHint);
}
