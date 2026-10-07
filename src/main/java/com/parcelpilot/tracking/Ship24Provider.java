package com.parcelpilot.tracking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parcelpilot.model.OrderEvent;
import com.parcelpilot.model.OrderStatus;
import com.parcelpilot.util.Mask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Ship24 (https://ship24.co). Free tier: 10 shipments/month, API key issued on signup.
 * If SHIP24_API_KEY is missing the provider throws and the fallback uses the mock.
 */
@Component("ship24")
public class Ship24Provider implements TrackingProvider {

    private static final Logger log = LoggerFactory.getLogger(Ship24Provider.class);
    private static final String URL = "https://api.ship24.com/api/v1/trackers/track";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;

    public Ship24Provider(@Value("${SHIP24_API_KEY:}") String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    boolean configured() { return !apiKey.isBlank(); }

    @Override
    public TrackingResult track(String awb, String courierHint) {
        if (!configured()) throw new IllegalStateException("SHIP24_API_KEY not configured");
        try {
            String body = mapper.writeValueAsString(java.util.Map.of("trackingNumber", awb));
            HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
                    .timeout(Duration.ofSeconds(15))
                    // Ship24 docs (docs.ship24.com/getting-started): Authorization: Bearer <api key>
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Ship24 HTTP " + response.statusCode());
            }
            return parse(mapper.readTree(response.body()));
        } catch (Exception e) {
            log.warn("Ship24 track failed for {}: {}", Mask.tracking(awb), e.getMessage());
            throw new IllegalStateException("Ship24 unavailable", e);
        }
    }

    TrackingResult parse(JsonNode root) {
        JsonNode t = root.path("data").path("trackings").path(0);
        JsonNode eventsNode = t.path("tracking").path("events");
        List<OrderEvent> events = new ArrayList<>();
        for (JsonNode e : eventsNode) {
            events.add(new OrderEvent(
                    e.path("status").asText("Event"),
                    e.path("location").asText(""),
                    instant(e.path("occurrenceDatetime").asText(null))));
        }
        OrderStatus status = mapStatus(t.path("shipment").path("shipmentStatus").asText(
                t.path("tracking").path("latestStatus").asText("")));
        Instant delivered = status == OrderStatus.DELIVERED && !events.isEmpty() ? events.get(0).getTime() : null;
        Instant eta = instant(t.path("shipment").path("estimatedDeliveryDate").asText(null));
        String city = events.isEmpty() ? "" : events.get(0).getLocation();
        return new TrackingResult(status, city, eta, delivered, events, "ship24");
    }

    static OrderStatus mapStatus(String s) {
        String v = s == null ? "" : s.toLowerCase().replace('_', ' ');
        if (v.contains("deliver")) return OrderStatus.DELIVERED;
        if (v.contains("out for delivery")) return OrderStatus.OUT_FOR_DELIVERY;
        if (v.contains("exception") || v.contains("fail") || v.contains("return")) return OrderStatus.EXCEPTION;
        if (v.contains("transit") || v.contains("shipped") || v.contains("dispatch")) return OrderStatus.IN_TRANSIT;
        return OrderStatus.UNKNOWN;
    }

    private static Instant instant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return Instant.parse(iso); } catch (DateTimeParseException e) { return null; }
    }
}
