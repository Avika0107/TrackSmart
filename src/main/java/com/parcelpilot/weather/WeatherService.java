package com.parcelpilot.weather;

import com.parcelpilot.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Selects the weather strategy. The mock provider bypasses the cache so the demo
 * "stormy" toggle is instant; Open-Meteo results are cached for 1h.
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private final MockWeatherClient mock;
    private final OpenMeteoClient openMeteo;
    private final String provider;
    private final Cache cache;

    public WeatherService(MockWeatherClient mock, OpenMeteoClient openMeteo,
                          AppProperties props, CacheManager cacheManager) {
        this.mock = mock;
        this.openMeteo = openMeteo;
        this.provider = props.getWeather().getProvider() == null ? "mock" : props.getWeather().getProvider();
        this.cache = cacheManager.getCache("weather");
    }

    public WeatherSnapshot forecast(String city) {
        if ("open-meteo".equalsIgnoreCase(provider)) {
            if (cache == null) return openMeteo.forecast(city);
            String key = city.toLowerCase();
            WeatherSnapshot cached = cache.get(key, WeatherSnapshot.class);
            if (cached != null) return cached;
            WeatherSnapshot snapshot = openMeteo.forecast(city);
            cache.put(key, snapshot);
            return snapshot;
        }
        return mock.forecast(city);
    }

    /** Never throws: weather problems must degrade, not break the dashboard. */
    public Optional<WeatherSnapshot> forecastSafe(String city) {
        try {
            return Optional.of(forecast(city));
        } catch (Exception e) {
            log.warn("Weather unavailable for {} ({}); skipping risk update", city, e.getMessage());
            return Optional.empty();
        }
    }

    public void setMockMode(String mode) {
        mock.setMode(mode);
        if (cache != null) cache.clear();
        log.info("Demo weather mode set to '{}'", mock.getMode());
    }

    public String getMockMode() { return mock.getMode(); }
}
