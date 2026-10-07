package com.parcelpilot.weather;

/** Strategy interface for weather. Implementations selected by app.weather.provider. */
public interface WeatherClient {

    WeatherSnapshot forecast(String city);
}
