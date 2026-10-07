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
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 17TRACK v2.2 (https://res.17track.net). New accounts get a one-time 200 free numbers.
 * v2.2 requires a number to be REGISTERED (/register) before info can be fetched;
 * registration is idempotent (already-registered numbers are soft-rejected, ignored).
 * If TRACK17_API_KEY is missing the provider throws and the fallback uses the mock.
 */
@Component("17track")
public class Track17Provider implements TrackingProvider {

    private static final Logger log = LoggerFactory.getLogger(Track17Provider.class);
    private static final String URL = "https://api.17track.net/track/v2.2/gettrackinfo";
    private static final String REGISTER_URL = "https://api.17track.net/track/v2.2/register";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;

    public Track17Provider(@Value("${TRACK17_API_KEY:}") String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    boolean configured() { return !apiKey.isBlank(); }

    @Override
    public TrackingResult track(String awb, String courierHint) {
        if (!configured()) throw new IllegalStateException("TRACK17_API_KEY not configured");
        try {
            register(awb);
            String body = mapper.writeValueAsString(List.of(java.util.Map.of("number", awb)));
            HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("17token", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("17TRACK HTTP " + response.statusCode());
            }
            return parse(mapper.readTree(response.body()));
        } catch (Exception e) {
            log.warn("17TRACK failed for {}: {}", Mask.tracking(awb), e.getMessage());
            throw new IllegalStateException("17TRACK unavailable", e);
        }
    }

    /** Registers the number with auto carrier detection; harmless if already registered. */
    private void register(String awb) throws Exception {
        String body = mapper.writeValueAsString(
                List.of(java.util.Map.of("number", awb, "auto_detection", true)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(REGISTER_URL))
                .timeout(Duration.ofSeconds(15))
                .header("17token", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("17TRACK register HTTP " + response.statusCode());
        }
    }

    TrackingResult parse(JsonNode root) {
        // v2.2: {"data":{"accepted":[{"track_info":{...}}],"rejected":[...]}}; legacy: {"data":[{"track_info":{...}}]}
        JsonNode info = root.path("data").path("accepted").path(0).path("track_info");
        if (info.isMissingNode() || info.isNull()) {
            info = root.path("data").path(0).path("track_info");
        }
        if (info.isMissingNode() || info.isNull()) {
            // Registered but no scan yet (code -18019909 "No tracking information at this time").
            return new TrackingResult(OrderStatus.UNKNOWN, "", null, null, List.of(), "17track");
        }
        JsonNode eventsNode = info.path("events");
        List<OrderEvent> events = new ArrayList<>();
        for (JsonNode e : eventsNode) {
            events.add(new OrderEvent(
                    e.path("description").asText("Event"),
                    e.path("location").asText(""),
                    instant(e.path("time_iso").asText(null))));
        }
        // 17TRACK lists newest last; we store newest first like carrier feeds.
        java.util.Collections.reverse(events);
        OrderStatus status = events.isEmpty() ? OrderStatus.UNKNOWN : statusFromEvent(events.get(0).getDescription());
        String city = events.isEmpty() ? "" : events.get(0).getLocation();
        return new TrackingResult(status, city, null,
                status == OrderStatus.DELIVERED && !events.isEmpty() ? events.get(0).getTime() : null,
                events, "17track");
    }

    static OrderStatus statusFromEvent(String description) {
        String v = description == null ? "" : description.toLowerCase();
        if (v.contains("deliver") && !v.contains("out for delivery") && !v.contains("attempt")) return OrderStatus.DELIVERED;
        if (v.contains("out for delivery")) return OrderStatus.OUT_FOR_DELIVERY;
        if (v.contains("exception") || v.contains("failed") || v.contains("returned")) return OrderStatus.EXCEPTION;
        if (v.contains("transit") || v.contains("arrived") || v.contains("departed") || v.contains("pickup")) return OrderStatus.IN_TRANSIT;
        return OrderStatus.UNKNOWN;
    }

    private static Instant instant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return OffsetDateTime.parse(iso).toInstant(); } catch (DateTimeParseException e) {
            try { return Instant.parse(iso); } catch (DateTimeParseException e2) { return null; }
        }
    }
}
