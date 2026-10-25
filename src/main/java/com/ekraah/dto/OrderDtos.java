package com.ekraah.dto;

import com.ekraah.model.Platform;
import com.ekraah.weather.WeatherSnapshot;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class OrderDtos {

    private OrderDtos() {}

    /** NOTE: no userId field — the userId always comes from the JWT, never from the body. */
    public record CreateOrderRequest(@NotBlank @Size(max = 40) String trackingNumber, Platform platform) {}

    /**
     * Facts sent by the browser extension from a retailer's order page.
     * NOTE: no userId field — same rule as CreateOrderRequest.
     */
    public record ImportOrderRequest(
            @NotNull Platform platform,
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9-]+") String trackingNumber,
            @Size(max = 40) String courierHint,       // optional: courier name printed on the page
            @Size(max = 40) String retailerOrderId) {} // optional: e.g. OD123…, 402-…-…

    /** outcome: CREATED | UPDATED (merged into a pending card) | ALREADY_TRACKED. */
    public record ImportOrderResponse(String outcome, com.ekraah.model.Order order) {}

    public record OrderDetailResponse(com.ekraah.model.Order order, WeatherSnapshot weather) {}

    public record SettingsRequest(String theme, Boolean notificationsOn, String defaultCity) {}
}

// - ImportOrderRequest mirrors exactly what extension/background.js posts to /api/orders/import.
// - @NotNull platform: the extension always knows the store (from retailers.js), unlike manual adds.
// - @Pattern on trackingNumber: letters, digits and hyphens only — rejects anything that looks
//   like page text before it reaches OrderService; CourierDetector still validates the format.
// - courierHint / retailerOrderId are optional and size-capped so a broken page can't send huge
//   strings; they're hints only — the server never trusts them over its own detection.
// - ImportOrderResponse carries the outcome so the popup can say "already tracking" instead of
//   pretending a duplicate was newly added.
// - Validation only runs when the controller parameter is annotated @Valid (OrderController.importOrder).
