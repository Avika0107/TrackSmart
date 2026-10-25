package com.parcelpilot.weather;

import com.parcelpilot.config.AppProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class OpenMeteoClientTest {

    private MockWebServer server;
    private OpenMeteoClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        AppProperties props = new AppProperties();
        props.getWeather().setGeocodeUrl(server.url("/geocode").toString());
        props.getWeather().setForecastUrl(server.url("/forecast").toString());
        client = new OpenMeteoClient(props);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void mapsGeocodeAndForecast() {
        server.enqueue(new MockResponse().setBody("""
                {"results":[{"latitude":19.07,"longitude":72.87,"name":"Mumbai"}]}
                """));
        server.enqueue(new MockResponse().setBody("""
                {"daily":{"temperature_2m_min":[24.1,25.0],"temperature_2m_max":[31.2,33.0],
                          "precipitation_sum":[12.5,4.1],"wind_speed_10m_max":[22.4,30.8],
                          "wind_gusts_10m_max":[40.2,52.0]},
                 "hourly":{"temperature_2m":[26.0],"snowfall":[0.0],"showers":[0.5],"snow_depth":[0.0],
                           "rain":[2.0],"precipitation":[2.5],"precipitation_probability":[80,45],
                           "apparent_temperature":[30.1],"dew_point_2m":[24.0],
                           "weather_code":[61,95,3],"pressure_msl":[1010.0],"surface_pressure":[1002.0],
                           "visibility":[8000,9000,5000]}}
                """));

        WeatherSnapshot snap = client.forecast("Mumbai");

        assertThat(snap.city()).isEqualTo("Mumbai");
        assertThat(snap.tempC()).isEqualTo(33.0);              // max over 2 days
        assertThat(snap.precipitationMm24h()).isEqualTo(12.5); // worst daily sum
        assertThat(snap.windKmph()).isEqualTo(30.8);
        assertThat(snap.windGustsKmph()).isEqualTo(52.0);      // gust peak
        assertThat(snap.wmoCode()).isEqualTo(95);              // worst hourly code (thunderstorm)
        assertThat(snap.visibilityKm()).isEqualTo(5.0);        // min hourly, metres -> km
        assertThat(snap.precipitationProbabilityPct()).isEqualTo(80.0);
        assertThat(snap.source()).isEqualTo("open-meteo");
    }

    @Test
    void missingOptionalVarsFallBackToZero() {
        server.enqueue(new MockResponse().setBody("""
                {"results":[{"latitude":19.07,"longitude":72.87,"name":"Mumbai"}]}
                """));
        server.enqueue(new MockResponse().setBody("""
                {"daily":{"temperature_2m_max":[31.2],"precipitation_sum":[0.0],
                          "wind_speed_10m_max":[22.4]},
                 "hourly":{"visibility":[10000]}}
                """));

        WeatherSnapshot snap = client.forecast("Mumbai");

        assertThat(snap.windGustsKmph()).isZero();
        assertThat(snap.precipitationProbabilityPct()).isZero();
        assertThat(snap.wmoCode()).isZero();                    // clear sky default
        assertThat(snap.visibilityKm()).isEqualTo(10.0);
    }

    @Test
    void geocodeMissThrows() {
        server.enqueue(new MockResponse().setBody("{\"results\":[]}"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.forecast("Nowhereville"))
                .isInstanceOf(IllegalStateException.class);
    }
}
