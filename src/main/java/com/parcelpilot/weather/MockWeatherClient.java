package com.parcelpilot.weather;

import com.parcelpilot.config.AppProperties;
import org.springframework.stereotype.Component;

/**
 * Demo weather: "clear" by day, "stormy" when the demo control panel toggles it
 * (or app.weather.mock-mode=stormy at startup). Deterministic, no network.
 */
@Component("weatherMock")
public class MockWeatherClient implements WeatherClient {

    public static final String CLEAR = "clear";
    public static final String STORMY = "stormy";

    private final String initialMode;
    private volatile String mode;

    public MockWeatherClient(AppProperties props) {
        this.initialMode = STORMY.equalsIgnoreCase(props.getWeather().getMockMode()) ? STORMY : CLEAR;
        this.mode = this.initialMode;
    }

    public void setMode(String mode) {
        this.mode = STORMY.equalsIgnoreCase(mode) ? STORMY : CLEAR;
    }

    public String getMode() { return mode; }
    public String getInitialMode() { return initialMode; }

    @Override
    public WeatherSnapshot forecast(String city) {
        if (STORMY.equals(mode)) {
            // Monsoon burst: 35 mm rain, thunderstorm WMO 95, gusty wind, reduced visibility.
            return new WeatherSnapshot(city, 26.4, 35.2, 52.0, 95, 3.0, "mock");
        }
        return new WeatherSnapshot(city, 29.1, 0.0, 11.6, 1, 10.0, "mock");
    }
}
