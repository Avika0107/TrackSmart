package com.parcelpilot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parcelpilot.dto.RawEmail;
import com.parcelpilot.mail.GmailMessageProcessor;
import com.parcelpilot.mail.ImapPoller;
import com.parcelpilot.model.Order;
import com.parcelpilot.model.OrderStatus;
import com.parcelpilot.service.OrderService;
import com.parcelpilot.weather.WeatherService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Demo profile only: drives the whole 3-minute presentation without real emails —
 * simulate the 8 sample emails, poll the (unconfigured) inbox, toggle stormy
 * weather and force status changes.
 */
@RestController
@RequestMapping("/api/dev")
@Profile("demo")
public class DevController {

    private final GmailMessageProcessor processor;
    private final ImapPoller poller;
    private final WeatherService weather;
    private final OrderService orders;
    private final ObjectMapper mapper;

    public DevController(GmailMessageProcessor processor, ImapPoller poller, WeatherService weather,
                         OrderService orders, ObjectMapper mapper) {
        this.processor = processor;
        this.poller = poller;
        this.weather = weather;
        this.orders = orders;
        this.mapper = mapper;
    }

    public record SimulateEmailRequest(String messageId, @NotBlank String from, String to,
                                       String subject, @NotBlank String body, Boolean html) {}

    @PostMapping("/simulate-email")
    @Operation(summary = "Run a sample email through the real pipeline")
    public Map<String, Object> simulate(@RequestBody SimulateEmailRequest body) {
        RawEmail email = new RawEmail(
                body.messageId() != null ? body.messageId() : "sim-" + System.nanoTime(),
                body.from(), body.to(), body.to(), null, null,
                body.subject() == null ? "" : body.subject(), body.body());
        String outcome = processor.process(email);
        return Map.of("outcome", outcome);
    }

    @PostMapping("/poll-now")
    @Operation(summary = "Manually trigger an IMAP poll")
    public Map<String, Object> pollNow() {
        return Map.of("processed", poller.pollNow());
    }

    @PostMapping("/set-weather")
    @Operation(summary = "Toggle demo weather: stormy | clear")
    public Map<String, Object> setWeather(@RequestParam String mode) {
        if (!mode.equalsIgnoreCase("stormy") && !mode.equalsIgnoreCase("clear")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode must be stormy or clear");
        }
        weather.setMockMode(mode);
        return Map.of("mode", weather.getMockMode());
    }

    @PostMapping("/orders/{id}/status")
    @Operation(summary = "Force an order status change (demo)")
    public Order forceStatus(@PathVariable String id, @RequestParam OrderStatus status) {
        return orders.forceStatus(id, status);
    }

    private List<Map<String, Object>> samplesCache;

    @GetMapping("/samples")
    @Operation(summary = "The built-in sample emails for the demo panel")
    public List<Map<String, Object>> samples() {
        if (samplesCache != null) return samplesCache;
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            for (Resource r : new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:sample-emails/*.json")) {
                list.add(mapper.convertValue(mapper.readTree(r.getInputStream()), Map.class));
            }
        } catch (Exception e) {
            // No samples on the classpath: the panel simply shows no preset buttons.
        }
        samplesCache = list;
        return list;
    }
}
