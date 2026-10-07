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
                {"daily":{"temperature_2m_max":[31.2,33.0],"precipitation_sum":[12.5,4.1],
                          "wind_speed_10m_max":[22.4,30.8],"weathercode":[61,3]},
                 "hourly":{"visibility":[8000,9000,5000]}}
                """));

        WeatherSnapshot snap = client.forecast("Mumbai");

        assertThat(snap.city()).isEqualTo("Mumbai");
        assertThat(snap.tempC()).isEqualTo(33.0);           // max over 2 days
        assertThat(snap.precipitationMm24h()).isEqualTo(12.5); // worst daily sum
        assertThat(snap.windKmph()).isEqualTo(30.8);
        assertThat(snap.wmoCode()).isEqualTo(61);
        assertThat(snap.visibilityKm()).isEqualTo(5.0);     // min hourly, metres -> km
        assertThat(snap.source()).isEqualTo("open-meteo");
    }

    @Test
    void geocodeMissThrows() {
        server.enqueue(new MockResponse().setBody("{\"results\":[]}"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.forecast("Nowhereville"))
                .isInstanceOf(IllegalStateException.class);
    }
}
