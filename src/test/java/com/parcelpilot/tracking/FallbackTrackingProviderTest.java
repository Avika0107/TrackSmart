package com.parcelpilot.tracking;

import com.parcelpilot.config.AppProperties;
import com.parcelpilot.model.OrderStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackTrackingProviderTest {

    private final MockTrackingProvider mock = new MockTrackingProvider();
    private final Ship24Provider unconfiguredShip24 = new Ship24Provider(""); // throws when used

    private AppProperties props(String provider, int budget) {
        AppProperties p = new AppProperties();
        p.getTracking().setProvider(provider);
        p.getTracking().setDailyBudget(budget);
        return p;
    }

    @Test
    void mockConfigured_returnsMockDirectly() {
        var fallback = new FallbackTrackingProvider(List.of(mock, unconfiguredShip24), props("mock", 10));
        TrackingResult r = fallback.track("FMP1", "Ekart");
        assertThat(r.provider()).isEqualTo("mock");
    }

    @Test
    void primaryFailure_fallsBackToMock() {
        var fallback = new FallbackTrackingProvider(List.of(mock, unconfiguredShip24), props("ship24", 10));
        TrackingResult r = fallback.track("FMP1", "Ekart");
        assertThat(r.provider()).isEqualTo("mock");
        assertThat(r.status()).isIn(OrderStatus.SHIPPED, OrderStatus.IN_TRANSIT, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED);
    }

    @Test
    void exhaustedBudget_fallsBackToMock() {
        var fallback = new FallbackTrackingProvider(List.of(mock, unconfiguredShip24), props("ship24", 0));
        TrackingResult r = fallback.track("FMP1", "Ekart");
        assertThat(r.provider()).isEqualTo("mock"); // budget 0 -> never touches the real provider
    }

    @Test
    void unknownProviderName_usesMock() {
        var fallback = new FallbackTrackingProvider(List.of(mock, unconfiguredShip24), props("nope", 10));
        assertThat(fallback.track("FMP1", "Ekart").provider()).isEqualTo("mock");
    }
}
