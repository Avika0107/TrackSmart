package com.parcelpilot.risk;

import com.parcelpilot.config.AppProperties;
import com.parcelpilot.model.OrderStatus;
import com.parcelpilot.model.RiskLevel;
import com.parcelpilot.weather.WeatherSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DelayRiskServiceTest {

    private DelayRiskService service;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        // defaults mirror application.yml: rain>20 +3, thunder +3, wind>40 +2, vis<1 +1, heat>42 +1
        service = new DelayRiskService(props, new TemplateMessageBuilder());
    }

    private WeatherSnapshot weather(double precip, double wind, int wmo, double visKm, double temp) {
        return new WeatherSnapshot("Mumbai", temp, precip, wind, wmo, visKm, "mock");
    }

    @Test
    void clearWeatherIsLow() {
        RiskAssessment a = service.assess("Mumbai", OrderStatus.IN_TRANSIT, weather(0, 10, 1, 10, 29));
        assertThat(a.level()).isEqualTo(RiskLevel.LOW);
        assertThat(a.score()).isZero();
        assertThat(a.message()).contains("clear");
    }

    @Test
    void heavyRainThreshold_isStrictlyGreaterThan() {
        assertThat(service.assess("Mumbai", OrderStatus.IN_TRANSIT, weather(20, 10, 1, 10, 29)).score()).isZero();
        assertThat(service.assess("Mumbai", OrderStatus.IN_TRANSIT, weather(20.1, 10, 1, 10, 29)).score()).isEqualTo(3);
        assertThat(service.assess("Mumbai", OrderStatus.IN_TRANSIT, weather(20.1, 10, 1, 10, 29)).level()).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void thunderstormAddsThree() {
        RiskAssessment a = service.assess("Mumbai", OrderStatus.IN_TRANSIT, weather(0, 10, 95, 10, 27));
        assertThat(a.score()).isEqualTo(3);
        assertThat(a.reasons()).anyMatch(r -> r.contains("Thunderstorm"));
    }

    @Test
    void windBoundary() {
        assertThat(service.assess("Mumbai", OrderStatus.IN_TRANSIT, weather(0, 40, 1, 10, 29)).score()).isZero();
        assertThat(service.assess("Mumbai", OrderStatus.IN_TRANSIT, weather(0, 40.5, 1, 10, 29)).score()).isEqualTo(2);
    }

    @Test
    void fogAndHeatBoundaries() {
        assertThat(service.assess("X", OrderStatus.IN_TRANSIT, weather(0, 10, 45, 1, 29)).score()).isZero();
        assertThat(service.assess("X", OrderStatus.IN_TRANSIT, weather(0, 10, 45, 0.9, 29)).score()).isEqualTo(1);
        assertThat(service.assess("X", OrderStatus.IN_TRANSIT, weather(0, 10, 1, 10, 42)).score()).isZero();
        assertThat(service.assess("X", OrderStatus.IN_TRANSIT, weather(0, 10, 1, 10, 43)).score()).isEqualTo(1);
    }

    @Test
    void stormyComboIsHigh() {
        // 35mm rain (+3) + thunderstorm (+3) + 52km/h wind (+2) = 8 -> HIGH
        RiskAssessment a = service.assess("Mumbai", OrderStatus.IN_TRANSIT, weather(35.2, 52, 95, 3, 26));
        assertThat(a.score()).isEqualTo(8);
        assertThat(a.level()).isEqualTo(RiskLevel.HIGH);
        assertThat(a.message()).contains("Thunderstorm");
    }

    @Test
    void outForDeliveryHalvesScore() {
        // rain(3) + wind(2) = 5 -> OFD: 5/2 = 2 -> LOW
        RiskAssessment a = service.assess("Mumbai", OrderStatus.OUT_FOR_DELIVERY, weather(25, 45, 1, 10, 29));
        assertThat(a.score()).isEqualTo(2);
        assertThat(a.level()).isEqualTo(RiskLevel.LOW);
        assertThat(a.reasons()).anyMatch(r -> r.contains("halved"));
    }

    @Test
    void deliveredNeverShowsRisk() {
        RiskAssessment a = service.assess("Mumbai", OrderStatus.DELIVERED, weather(35, 52, 95, 1, 45));
        assertThat(a.level()).isEqualTo(RiskLevel.LOW);
        assertThat(a.score()).isZero();
        assertThat(a.reasons()).isEmpty();
    }

    @Test
    void levelBoundaries() {
        assertThat(DelayRiskService.levelFor(0)).isEqualTo(RiskLevel.LOW);
        assertThat(DelayRiskService.levelFor(2)).isEqualTo(RiskLevel.LOW);
        assertThat(DelayRiskService.levelFor(3)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(DelayRiskService.levelFor(4)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(DelayRiskService.levelFor(5)).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void reasonsAreExposedForTheUi() {
        RiskAssessment a = service.assess("Mumbai", OrderStatus.IN_TRANSIT, weather(35.2, 52, 95, 3, 26));
        assertThat(a.reasons()).hasSize(3);
        assertThat(a.reasons().get(0)).contains("20 mm"); // threshold documented in the reason
    }
}
