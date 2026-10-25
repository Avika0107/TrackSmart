package com.ekraah.service;

import com.ekraah.model.Alert;
import com.ekraah.model.Order;
import com.ekraah.model.OrderStatus;
import com.ekraah.model.Platform;
import com.ekraah.model.RiskLevel;
import com.ekraah.repository.AlertRepository;
import com.ekraah.repository.OrderRepository;
import com.ekraah.repository.UserRepository;
import com.ekraah.util.Mask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Turns order state into in-app alerts (bell menu, desktop notifications while the app is open).
 * Called after every risk computation, so it is stateless: an alert fires when the condition
 * holds and no alert of that kind exists for the order within its quiet period. That keeps the
 * many call sites (email, refresh, extension, scheduler) free of before/after bookkeeping.
 *
 *  - HIGH_RISK          risk level HIGH            at most once per order per day
 *  - EXCEPTION          courier reported a problem at most once per order per day
 *  - OUT_FOR_DELIVERY   arriving today             once per order
 *
 * Users who switched notifications off in Settings get none. Alert failures never break the
 * order update that triggered them.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    static final Duration DAILY = Duration.ofDays(1);

    private final AlertRepository alerts;
    private final UserRepository users;
    private final OrderRepository orders;

    public AlertService(AlertRepository alerts, UserRepository users, OrderRepository orders) {
        this.alerts = alerts;
        this.users = users;
        this.orders = orders;
    }

    public void evaluate(Order order) {
        try {
            if (order.getUserId() == null || order.getTrackingNumber() == null || order.getStatus() == null) return;
            if (order.getStatus().isTerminal()) return;
            if (order.getStatus() == OrderStatus.OUT_FOR_DELIVERY) {
                raise(order, Alert.Kind.OUT_FOR_DELIVERY, Instant.EPOCH,
                        "Your " + name(order) + " is out for delivery today.");
            }
            if (order.getStatus() == OrderStatus.EXCEPTION) {
                raise(order, Alert.Kind.EXCEPTION, Instant.now().minus(DAILY),
                        "The courier reported a problem with your " + name(order) + ". Open it for the latest update.");
            }
            if (order.getRisk() != null && order.getRisk().getLevel() == RiskLevel.HIGH) {
                String why = order.getRisk().getMessage() == null ? "" : " " + order.getRisk().getMessage();
                raise(order, Alert.Kind.HIGH_RISK, Instant.now().minus(DAILY),
                        "Possible delay for your " + name(order) + "." + why);
            }
        } catch (Exception e) {
            log.warn("Alert evaluation failed for {}: {}", Mask.tracking(order.getTrackingNumber()), e.getMessage());
        }
    }

    private void raise(Order order, Alert.Kind kind, Instant quietSince, String message) {
        if (alerts.existsByUserIdAndTrackingNumberAndKindAndCreatedAtAfter(
                order.getUserId(), order.getTrackingNumber(), kind, quietSince)) {
            return;
        }
        boolean wanted = users.findById(order.getUserId())
                .map(u -> u.getSettings() == null || u.getSettings().isNotificationsOn())
                .orElse(false);
        if (!wanted) return;
        alerts.save(Alert.of(order, kind, message));
        log.info("Alert {} for order {}", kind, Mask.tracking(order.getTrackingNumber()));
    }

    /** "Flipkart order ••••1203" */
    private static String name(Order order) {
        Platform p = order.getPlatform() == null ? Platform.OTHER : order.getPlatform();
        String store = p == Platform.OTHER ? "" : p.name().charAt(0) + p.name().substring(1).toLowerCase(Locale.ROOT) + " ";
        return store + "order " + Mask.tracking(order.getTrackingNumber()).replace('*', '•');
    }

    /**
     * Latest alerts. Risk is often scored before an order's first save (new orders, seed data),
     * so such alerts were stored without an order id; it is filled in here by tracking number,
     * once, so clicking the alert can open the order.
     */
    public List<Alert> latest(String userId) {
        List<Alert> list = alerts.findTop30ByUserIdOrderByCreatedAtDesc(userId);
        for (Alert a : list) {
            if (a.getOrderId() != null || a.getTrackingNumber() == null) continue;
            orders.findFirstByUserIdAndTrackingNumberOrderByLastUpdatedDesc(userId, a.getTrackingNumber())
                    .ifPresent(o -> {
                        a.setOrderId(o.getId());
                        alerts.save(a);
                    });
        }
        return list;
    }

    public long unread(String userId) {
        return alerts.countByUserIdAndReadFalse(userId);
    }

    public void markAllRead(String userId) {
        List<Alert> open = alerts.findByUserIdAndReadFalse(userId);
        open.forEach(a -> a.setRead(true));
        alerts.saveAll(open);
    }

    public void deleteForUser(String userId) {
        alerts.deleteByUserId(userId);
    }

    /** Weekly cleanup: alerts are a short-lived feed, not an archive. */
    public long deleteOlderThan(Duration age) {
        return alerts.deleteByCreatedAtBefore(Instant.now().minus(age));
    }
}
