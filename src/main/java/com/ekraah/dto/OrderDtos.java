package com.ekraah.dto;

import com.ekraah.model.OrderStatus;
import com.ekraah.model.Platform;
import com.ekraah.weather.WeatherSnapshot;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public final class OrderDtos {

    private OrderDtos() {}

    /** NOTE: no userId field — the userId always comes from the JWT, never from the body. */
    public record CreateOrderRequest(@NotBlank @Size(max = 40) String trackingNumber, Platform platform) {}

    /**
     * Facts sent by the browser extension from a retailer's order page. Either a tracking
     * number (live tracking) or, when the page shows none (Flipkart), the retailer order id
     * plus the status and dates printed on the page. NOTE: no userId field — same rule as
     * CreateOrderRequest.
     */
    public record ImportOrderRequest(
            @NotNull Platform platform,
            @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9-]+") String trackingNumber,   // optional
            @Size(max = 40) String courierHint,       // optional: courier name printed on the page
            @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9-]+") String retailerOrderId,  // e.g. OD123…, 402-…-…
            OrderStatus status,                       // optional: status shown on the page
            LocalDate expectedDelivery,               // optional: "Delivery by …"
            LocalDate deliveredOn) {}                 // optional: "Delivered on …"

    /** Tracking number typed into a card that had none (e.g. a Flipkart order). No userId field. */
    public record AttachTrackingRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9-]+") String trackingNumber) {}

    /** outcome: CREATED | UPDATED (merged into an existing card) | ALREADY_TRACKED. */
    public record ImportOrderResponse(String outcome, com.ekraah.model.Order order) {}

    public record OrderDetailResponse(com.ekraah.model.Order order, WeatherSnapshot weather) {}

    public record SettingsRequest(String theme, Boolean notificationsOn, String defaultCity) {}
}

// - ImportOrderRequest mirrors exactly what extension/background.js posts to /api/orders/import.
// - @NotNull platform: the extension always knows the store (from retailers.js), unlike manual adds.
// - trackingNumber and retailerOrderId are each optional, but OrderService rejects a request with
//   neither. @Pattern skips nulls, so it only applies when a value is sent: letters, digits and
//   hyphens only — anything that looks like page text is rejected before it reaches OrderService.
// - status / expectedDelivery / deliveredOn are used only for order-id-only (PAGE_ONLY) cards;
//   when a tracking number is present the courier's live data wins over what the page says —
//   except status DELIVERED, which makes OrderService import by order id (nothing left to track).
// - AttachTrackingRequest: POST /api/orders/{id}/tracking, from the dashboard's "Add tracking
//   number" box on a PAGE_ONLY card.
// - LocalDate fields arrive as ISO strings ("2026-10-24"); the extension does the date parsing.
// - ImportOrderResponse carries the outcome so the popup can say "already tracking" instead of
//   pretending a duplicate was newly added.
// - Validation only runs when the controller parameter is annotated @Valid (OrderController.importOrder).
