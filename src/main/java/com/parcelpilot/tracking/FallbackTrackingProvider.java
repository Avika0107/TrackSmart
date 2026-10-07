package com.parcelpilot.tracking;

import com.parcelpilot.config.AppProperties;
import com.parcelpilot.util.Mask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tries the configured provider (ship24 | 17track), falls back to the mock on
 * error, missing API key or quota exhaustion — and says so in the logs.
 */
@Component
public class FallbackTrackingProvider implements TrackingProvider {

    private static final Logger log = LoggerFactory.getLogger(FallbackTrackingProvider.class);

    private final TrackingProvider mock;
    private final Map<String, TrackingProvider> primary = new HashMap<>();
    private final TrackingBudget budget;
    private final String configuredName;

    public FallbackTrackingProvider(List<TrackingProvider> providers, AppProperties props) {
        this.mock = providers.stream().filter(p -> p instanceof MockTrackingProvider).findFirst().orElseThrow();
        for (TrackingProvider p : providers) {
            if (p == mock) continue;
            if (p instanceof Ship24Provider) primary.put("ship24", p);
            if (p instanceof Track17Provider) primary.put("17track", p);
        }
        this.configuredName = props.getTracking().getProvider() == null
                ? "mock" : props.getTracking().getProvider().trim().toLowerCase();
        this.budget = new TrackingBudget(props.getTracking().getDailyBudget());
        log.info("Tracking: configured='{}', real providers available={}", configuredName, primary.keySet());
    }

    @Override
    public TrackingResult track(String awb, String courierHint) {
        TrackingProvider chosen = primary.getOrDefault(configuredName, mock);
        if (chosen == mock) {
            return mock.track(awb, courierHint);
        }
        if (!budget.tryAcquire()) {
            return mock.track(awb, courierHint); // TrackingBudget already logged the exhaustion
        }
        try {
            return chosen.track(awb, courierHint);
        } catch (Exception e) {
            log.warn("Primary tracking provider failed for {} ({}); falling back to mock",
                    Mask.tracking(awb), e.getMessage());
            return mock.track(awb, courierHint);
        }
    }
}
