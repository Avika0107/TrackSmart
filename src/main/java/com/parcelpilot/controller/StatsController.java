package com.parcelpilot.controller;

import com.parcelpilot.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final OrderService orders;

    public StatsController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    @Operation(summary = "Counts by status, platform and risk")
    public Map<String, Object> stats(@AuthenticationPrincipal String userId) {
        return orders.stats(userId);
    }
}
