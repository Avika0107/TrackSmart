package com.parcelpilot.weather;

/**
 * Next 24-48h weather facts for the delay rules. Open-Meteo is free for
 * non-commercial use and needs no API key (see README).
 */
public record WeatherSnapshot(
        String city,
        double tempC,
        double precipitationMm24h,
        double windKmph,
        int wmoCode,
        double visibilityKm,
        String source) {
}
