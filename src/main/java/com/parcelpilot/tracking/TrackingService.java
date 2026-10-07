package com.parcelpilot.tracking;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

/**
 * Cached facade over the tracking strategy: tracking TTL 3h so repeat page loads
 * never burn API quota. trackForce evicts first (manual refresh button).
 */
@Service
public class TrackingService {

    private final FallbackTrackingProvider provider;
    private final Cache cache;

    public TrackingService(FallbackTrackingProvider provider, CacheManager cacheManager) {
        this.provider = provider;
        this.cache = cacheManager.getCache("tracking");
    }

    public TrackingResult track(String awb, String courierHint) {
        if (cache == null) return provider.track(awb, courierHint);
        TrackingResult cached = cache.get(awb, TrackingResult.class);
        if (cached != null) return cached;
        TrackingResult result = provider.track(awb, courierHint);
        cache.put(awb, result);
        return result;
    }

    public TrackingResult trackForce(String awb, String courierHint) {
        if (cache != null) cache.evictIfPresent(awb);
        return track(awb, courierHint);
    }
}
