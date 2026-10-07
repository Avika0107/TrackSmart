package com.parcelpilot.tracking;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockTrackingProviderTest {

    private final MockTrackingProvider provider = new MockTrackingProvider();

    @Test
    void deterministicForSameAwb() {
        TrackingResult a = provider.track("FMP4471203", "Ekart");
        TrackingResult b = provider.track("FMP4471203", "Ekart");
        assertThat(a.status()).isEqualTo(b.status());
        assertThat(a.currentCity()).isEqualTo(b.currentCity());
        assertThat(a.events()).hasSize(b.events().size());
    }

    @Test
    void alwaysProducesTimelineAndCity() {
        for (int i = 0; i < 30; i++) {
            TrackingResult r = provider.track("AWB-" + i, null);
            assertThat(r.events()).isNotEmpty();
            assertThat(r.currentCity()).isNotBlank();
            assertThat(r.estimatedDelivery()).isNotNull();
        }
    }
}
