package com.parcelpilot.tracking;

import com.parcelpilot.model.OrderEvent;
import com.parcelpilot.model.OrderStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic fake tracker: the same AWB always produces the same status and
 * timeline, so demos and tests are stable and no API key is needed.
 */
@Component("trackingMock")
public class MockTrackingProvider implements TrackingProvider {

    private static final String[] CITIES = {
            "Mumbai", "Delhi", "Bengaluru", "Hyderabad", "Pune", "Kolkata", "Chennai", "Jaipur", "Ahmedabad", "Nagpur"};

    @Override
    public TrackingResult track(String awb, String courierHint) {
        int seed = Math.abs(awb.hashCode());
        OrderStatus status = switch (seed % 5) {
            case 0 -> OrderStatus.SHIPPED;
            case 1, 2 -> OrderStatus.IN_TRANSIT;
            case 3 -> OrderStatus.OUT_FOR_DELIVERY;
            default -> OrderStatus.DELIVERED;
        };
        int events = 3 + seed % 3;
        String destination = CITIES[seed % CITIES.length];
        List<OrderEvent> timeline = new ArrayList<>();
        for (int i = 0; i < events; i++) {
            String city = i == events - 1 ? destination : CITIES[(seed + i * 7) % CITIES.length];
            String description = i == events - 1
                    ? switch (status) {
                        case OUT_FOR_DELIVERY -> "Out for delivery";
                        case DELIVERED -> "Delivered";
                        default -> "In transit at facility";
                    }
                    : "Shipment scanned at " + city + " hub";
            timeline.add(new OrderEvent(description, city, Instant.now().minus((events - i) * 18L, ChronoUnit.HOURS)));
        }
        Instant delivered = status == OrderStatus.DELIVERED ? Instant.now().minus(2, ChronoUnit.HOURS) : null;
        Instant eta = status == OrderStatus.DELIVERED
                ? delivered
                : Instant.now().plus(1 + seed % 4, ChronoUnit.DAYS);
        String city = status == OrderStatus.SHIPPED ? timeline.get(0).getLocation() : destination;
        return new TrackingResult(status, city, eta, delivered, timeline, "mock");
    }
}
