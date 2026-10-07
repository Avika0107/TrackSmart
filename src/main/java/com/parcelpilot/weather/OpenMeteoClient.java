package com.parcelpilot.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parcelpilot.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Open-Meteo: free, no API key, non-commercial use only (see README).
 * Geocoding results are cached permanently; forecasts are cached 1h upstream in WeatherService.
 */
@Component("weatherOpenMeteo")
public class OpenMeteoClient implements WeatherClient {

    private static final Logger log = LoggerFactory.getLogger(OpenMeteoClient.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Cache<String, double[]> geocodeCache =
            Caffeine.newBuilder().maximumSize(5_000).build(); // city -> [lat, lon]
    private final String geocodeUrl;
    private final String forecastUrl;

    public OpenMeteoClient(AppProperties props) {
        this.geocodeUrl = props.getWeather().getGeocodeUrl();
        this.forecastUrl = props.getWeather().getForecastUrl();
    }

    @Override
    public WeatherSnapshot forecast(String city) {
        double[] ll = geocodeCache.get(city.toLowerCase(), this::geocode);
        if (ll == null) throw new IllegalStateException("Could not geocode city: " + city);
        try {
            String url = forecastUrl + "?latitude=" + ll[0] + "&longitude=" + ll[1]
                    + "&daily=temperature_2m_max,precipitation_sum,wind_speed_10m_max,weathercode"
                    + "&hourly=visibility&forecast_days=2&timezone=auto";
            JsonNode d = fetchJson(url);
            JsonNode daily = d.path("daily");
            double temp = max(daily.path("temperature_2m_max"));
            double precip = max(daily.path("precipitation_sum"));
            double wind = max(daily.path("wind_speed_10m_max"));
            int wmo = worstWeatherCode(daily.path("weathercode"));
            double visibility = minVisibility(d.path("hourly").path("visibility"));
            return new WeatherSnapshot(city, temp, precip, wind, wmo, visibility, "open-meteo");
        } catch (Exception e) {
            log.warn("Open-Meteo forecast failed for {}: {}", city, e.getMessage());
            throw new IllegalStateException("Weather unavailable", e);
        }
    }

    private double[] geocode(String cityKey) {
        try {
            String url = geocodeUrl + "?name=" + URLEncoder.encode(cityKey, StandardCharsets.UTF_8)
                    + "&count=1&language=en&format=json";
            JsonNode r = fetchJson(url);
            JsonNode first = r.path("results").path(0);
            if (first.isMissingNode()) return null;
            return new double[]{first.path("latitude").asDouble(), first.path("longitude").asDouble()};
        } catch (Exception e) {
            log.warn("Open-Meteo geocoding failed for {}: {}", cityKey, e.getMessage());
            return null;
        }
    }

    private JsonNode fetchJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "ParcelPilot/0.1 (college project)")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + response.statusCode());
        return mapper.readTree(response.body());
    }

    private static double max(JsonNode arr) {
        double m = Double.NEGATIVE_INFINITY;
        for (JsonNode n : arr) m = Math.max(m, n.asDouble(0));
        return m == Double.NEGATIVE_INFINITY ? 0 : m;
    }

    private static double minVisibility(JsonNode arr) {
        double m = Double.POSITIVE_INFINITY;
        for (JsonNode n : arr) m = Math.min(m, n.asDouble(Double.POSITIVE_INFINITY)); // metres
        return m == Double.POSITIVE_INFINITY ? 10 : m / 1000.0;                       // -> km
    }

    private static int worstWeatherCode(JsonNode arr) {
        int worst = 0;
        for (JsonNode n : arr) worst = Math.max(worst, n.asInt(0));
        return worst;
    }
}
