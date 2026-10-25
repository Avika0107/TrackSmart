package com.ekraah.controller;

import com.ekraah.model.Alert;
import com.ekraah.service.AlertService;
import com.ekraah.util.Mask;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertService alerts;

    public AlertController(AlertService alerts) {
        this.alerts = alerts;
    }

    /** Bell menu: latest 30 alerts + unread count. Tracking numbers are masked, as everywhere in the UI. */
    public record AlertView(String id, String kind, String message, String orderId, String tracking,
                            Instant createdAt, boolean read) {
        static AlertView of(Alert a) {
            return new AlertView(a.getId(), a.getKind().name(), a.getMessage(), a.getOrderId(),
                    Mask.tracking(a.getTrackingNumber()), a.getCreatedAt(), a.isRead());
        }
    }

    @GetMapping
    @Operation(summary = "Latest delay alerts and the unread count")
    public Map<String, Object> list(@AuthenticationPrincipal String userId) {
        List<AlertView> list = alerts.latest(userId).stream().map(AlertView::of).toList();
        return Map.of("unread", alerts.unread(userId), "alerts", list);
    }

    @PostMapping("/read")
    @Operation(summary = "Mark all my alerts as read")
    public ResponseEntity<Void> markRead(@AuthenticationPrincipal String userId) {
        alerts.markAllRead(userId);
        return ResponseEntity.noContent().build();
    }
}
