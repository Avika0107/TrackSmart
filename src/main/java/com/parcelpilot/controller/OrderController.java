package com.parcelpilot.controller;

import com.parcelpilot.dto.OrderDtos.CreateOrderRequest;
import com.parcelpilot.dto.OrderDtos.OrderDetailResponse;
import com.parcelpilot.model.Order;
import com.parcelpilot.model.OrderStatus;
import com.parcelpilot.model.Platform;
import com.parcelpilot.service.OrderService;
import com.parcelpilot.util.CourierDetector;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    @Operation(summary = "List orders with optional filters")
    public Iterable<Order> list(@AuthenticationPrincipal String userId,
                                @RequestParam(required = false) OrderStatus status,
                                @RequestParam(required = false) Platform platform,
                                @RequestParam(required = false) String q,
                                @RequestParam(required = false) String sort) {
        return orders.list(userId, status, platform, q, sort);
    }

    @GetMapping("/detect-courier")
    @Operation(summary = "Auto-detect courier for a tracking number")
    public Map<String, Object> detect(@RequestParam String number) {
        Optional<String> courier = CourierDetector.detect(number, null);
        return Map.of("courier", courier.orElse(""));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Order detail with current weather")
    public OrderDetailResponse detail(@AuthenticationPrincipal String userId, @PathVariable String id) {
        var d = orders.detail(userId, id);
        return new OrderDetailResponse(d.order(), d.weather());
    }

    @PostMapping
    @Operation(summary = "Add an order by tracking number")
    public ResponseEntity<Order> add(@AuthenticationPrincipal String userId,
                                     @Valid @RequestBody CreateOrderRequest body) {
        Order order = orders.addManual(userId, body.trackingNumber(), body.platform());
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an order")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal String userId, @PathVariable String id) {
        orders.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/refresh")
    @Operation(summary = "Force a live refresh of one order")
    public Order refresh(@AuthenticationPrincipal String userId, @PathVariable String id) {
        return orders.refresh(userId, id);
    }
}
