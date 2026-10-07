package com.parcelpilot.util;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CourierDetectorTest {

    @Test
    void detectsAmazonFormats() {
        assertThat(CourierDetector.detect("TBA309812456000", null)).contains("Amazon");
        assertThat(CourierDetector.detect("402-5684713-9912543", null)).contains("Amazon");
    }

    @Test
    void detectsEkartFmp() {
        assertThat(CourierDetector.detect("FMP4471203", "flipkart.com")).contains("Ekart");
    }

    @Test
    void detectsShadowfax() {
        assertThat(CourierDetector.detect("SF1234567890123", null)).contains("Shadowfax");
    }

    @Test
    void detectsIndiaPost() {
        assertThat(CourierDetector.detect("RM123456789IN", null)).contains("India Post");
    }

    @Test
    void tenDigitsIsBlueDart_unlessDelhiveryDomain() {
        assertThat(CourierDetector.detect("5551234567", null)).contains("Blue Dart");
        assertThat(CourierDetector.detect("5551234567", "delhivery.com")).contains("Delhivery");
    }

    @Test
    void elevenDigitsIsDelhivery() {
        assertThat(CourierDetector.detect("11234567891", null)).contains("Delhivery");
    }

    @Test
    void twelveToFifteenDigitsIsXpressbees_unlessDelhiveryDomain() {
        assertThat(CourierDetector.detect("990011223344", "ajio.com")).contains("Xpressbees");
        assertThat(CourierDetector.detect("990011223344", "delhivery.com")).contains("Delhivery");
    }

    @Test
    void courierFromDomain() {
        assertThat(CourierDetector.courierFromDomain("ekartlogistics.com")).contains("Ekart");
        assertThat(CourierDetector.courierFromDomain("bluedart.com")).contains("Blue Dart");
        assertThat(CourierDetector.courierFromDomain("gmail.com")).isEqualTo(Optional.empty());
    }

    @Test
    void unknownFormatsGiveEmpty() {
        assertThat(CourierDetector.detect("hello-world", null)).isEqualTo(Optional.empty());
        assertThat(CourierDetector.detect(null, null)).isEqualTo(Optional.empty());
    }
}
