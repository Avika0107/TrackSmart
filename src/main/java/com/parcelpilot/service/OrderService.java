package com.parcelpilot.service;

import com.parcelpilot.dto.RawEmail;
import com.parcelpilot.exception.ApiException;
import com.parcelpilot.mail.ParsedEmail;
import com.parcelpilot.model.AuditAction;
import com.parcelpilot.model.Order;
import com.parcelpilot.model.OrderEvent;
import com.parcelpilot.model.OrderSource;
import com.parcelpilot.model.OrderStatus;
import com.parcelpilot.model.Platform;
import com.parcelpilot.model.RiskInfo;
import com.parcelpilot.model.TrackingMode;
import com.parcelpilot.model.User;
import com.parcelpilot.repository.OrderRepository;
import com.parcelpilot.repository.UserRepository;
import com.parcelpilot.risk.DelayRiskService;
import com.parcelpilot.risk.RiskAssessment;
import com.parcelpilot.tracking.TrackingResult;
import com.parcelpilot.tracking.TrackingService;
import com.parcelpilot.util.CourierDetector;
import com.parcelpilot.util.Mask;
import com.parcelpilot.weather.WeatherService;
import com.parcelpilot.weather.WeatherSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final Duration RISK_STALE_AFTER = Duration.ofHours(1);

    private final OrderRepository orders;
    private final UserRepository users;
    private final TrackingService tracking;
    private final WeatherService weather;
    private final DelayRiskService riskService;
    private final AuditService audit;

    public OrderService(OrderRepository orders, UserRepository users, TrackingService tracking,
                        WeatherService weather, DelayRiskService riskService, AuditService audit) {
        this.orders = orders;
        this.users = users;
        this.tracking = tracking;
        this.weather = weather;
        this.riskService = riskService;
        this.audit = audit;
    }

    public enum EmailOutcome { CREATED, UPDATED, DUPLICATE }

    public record EmailResult(EmailOutcome outcome, Order order) {}

    // ---------- Email pipeline ----------

    public EmailResult upsertFromEmail(User user, ParsedEmail parsed, RawEmail email) {
        String domain = domainOf(email.from());

        if (email.messageId() != null
                && orders.findByUserIdAndSourceMessageId(user.getId(), email.messageId()).isPresent()) {
            audit.record(user.getId(), domain, AuditAction.DUPLICATE,
                    "Duplicate message ignored (AWB " + Mask.tracking(parsed.trackingNumber()) + ")");
            return new EmailResult(EmailOutcome.DUPLICATE, null);
        }

        Optional<Order> existing = orders
                .findFirstByUserIdAndTrackingNumberOrderByLastUpdatedDesc(user.getId(), parsed.trackingNumber());

        Order order = existing.orElseGet(Order::new);
        boolean created = existing.isEmpty();
        if (created) {
            order.setUserId(user.getId());
            order.setPlatform(parsed.platform());
            order.setTrackingNumber(parsed.trackingNumber());
            order.setCourier(parsed.courier());
            // Amazon TBA/order ids cannot go to third-party APIs: email-only mode.
            order.setTrackingMode(parsed.platform() == Platform.AMAZON ? TrackingMode.EMAIL_ONLY : TrackingMode.API);
            order.setSource(OrderSource.EMAIL);
            order.setSourceMessageId(email.messageId());
            order.setStatus(parsed.status() == null ? OrderStatus.SHIPPED : parsed.status());
            order.getEvents().add(new OrderEvent("Order email received from " + domain, null, Instant.now()));
        } else {
            if (parsed.status() != null) order.setStatus(parsed.status());
            order.getEvents().add(new OrderEvent(
                    "Status email received from " + domain + (parsed.delayed() ? " (retailer reported a delay)" : ""),
                    null, Instant.now()));
        }
        if (parsed.estimatedDelivery() != null) {
            order.setEstimatedDelivery(etaInstant(parsed.estimatedDelivery()));
        }
        if (order.getStatus() == OrderStatus.DELIVERED && order.getDeliveredAt() == null) {
            order.setDeliveredAt(Instant.now());
        }
        order.setLastUpdated(Instant.now());
        computeRisk(order);
        orders.save(order);

        String summary = created
                ? "Extracted AWB ending " + last4(order.getTrackingNumber()) + " from " + domain
                : "Updated order " + Mask.tracking(order.getTrackingNumber()) + " from " + domain
                        + " (status " + order.getStatus() + ")";
        audit.record(user.getId(), domain, AuditAction.PROCESSED, summary);

        if (!user.isForwardingVerified()) {
            user.setForwardingVerified(true);
            users.save(user);
        }
        log.info("Email from {} -> {} order {}", domain, created ? "created" : "updated",
                Mask.tracking(order.getTrackingNumber()));
        return new EmailResult(created ? EmailOutcome.CREATED : EmailOutcome.UPDATED, order);
    }

    // ---------- CRUD ----------

    public List<Order> list(String userId, OrderStatus status, Platform platform, String q, String sort) {
        List<Order> all = orders.findByUserId(userId, Sort.by(Sort.Direction.DESC, "lastUpdated"));
        refreshStaleRisk(all);

        return all.stream()
                .filter(o -> status == null || o.getStatus() == status)
                .filter(o -> platform == null || o.getPlatform() == platform)
                .filter(o -> q == null || q.isBlank()
                        || o.getTrackingNumber().toLowerCase().contains(q.toLowerCase())
                        || (o.getCourier() != null && o.getCourier().toLowerCase().contains(q.toLowerCase()))
                        || (o.getPlatform() != null && o.getPlatform().name().toLowerCase().contains(q.toLowerCase())))
                .sorted(comparatorFor(sort))
                .toList();
    }

    public Order getOwned(String userId, String orderId) {
        return orders.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Order not found."));
    }

    public Order addManual(String userId, String trackingNumber, Platform platform) {
        String number = trackingNumber.trim().toUpperCase();
        Optional<Order> dup = orders.findFirstByUserIdAndTrackingNumberOrderByLastUpdatedDesc(userId, number);
        if (dup.isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "You are already tracking this number.");
        }
        Order order = new Order();
        order.setUserId(userId);
        order.setTrackingNumber(number);
        order.setPlatform(platform == null ? Platform.OTHER : platform);
        order.setCourier(CourierDetector.detect(number, null).orElse("Unknown courier"));
        order.setTrackingMode(order.getCourier().equals("Amazon") ? TrackingMode.EMAIL_ONLY : TrackingMode.API);
        order.setSource(OrderSource.MANUAL);
        order.setStatus(OrderStatus.UNKNOWN);
        order.getEvents().add(new OrderEvent("Order added manually", null, Instant.now()));

        if (order.getTrackingMode() == TrackingMode.API) {
            applyTracking(order, tracking.track(order.getTrackingNumber(), order.getCourier()));
        }
        order.setLastUpdated(Instant.now());
        computeRisk(order);
        return orders.save(order);
    }

    public void delete(String userId, String orderId) {
        Order order = getOwned(userId, orderId);
        orders.delete(order);
    }

    public Order refresh(String userId, String orderId) {
        Order order = getOwned(userId, orderId);
        if (order.getTrackingMode() == TrackingMode.EMAIL_ONLY) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This order is tracked via retailer email only, so live scanning isn't available.");
        }
        applyTracking(order, tracking.trackForce(order.getTrackingNumber(), order.getCourier()));
        order.setLastUpdated(Instant.now());
        computeRisk(order);
        return orders.save(order);
    }

    public OrderDetail detail(String userId, String orderId) {
        Order order = getOwned(userId, orderId);
        WeatherSnapshot snapshot = order.getCurrentCity() == null ? null
                : weather.forecastSafe(order.getCurrentCity()).orElse(null);
        return new OrderDetail(order, snapshot);
    }

    public record OrderDetail(Order order, WeatherSnapshot weather) {}

    // ---------- Scheduler / dev helpers ----------

    /** Scheduled refresh: non-delivered, API-tracked orders only. EMAIL_ONLY orders are skipped. */
    public int refreshAllDue() {
        List<Order> due = orders.findByStatusInAndTrackingMode(
                List.copyOf(OrderStatus.ACTIVE), TrackingMode.API);
        int updated = 0;
        for (Order order : due) {
            try {
                applyTracking(order, tracking.track(order.getTrackingNumber(), order.getCourier()));
                order.setLastUpdated(Instant.now());
                computeRisk(order);
                orders.save(order);
                updated++;
            } catch (Exception e) {
                log.warn("Refresh failed for {}: {}", Mask.tracking(order.getTrackingNumber()), e.getMessage());
            }
        }
        return updated;
    }

    public int deleteOldTimelines(int keepDeliveredDays) {
        Instant cutoff = Instant.now().minus(Duration.ofDays(keepDeliveredDays));
        List<Order> old = orders.findByStatusAndDeliveredAtBefore(OrderStatus.DELIVERED, cutoff);
        for (Order order : old) {
            order.setEvents(List.of()); // keep the card, drop the timeline (spec: 30 days)
            orders.save(order);
        }
        return old.size();
    }

    public Order forceStatus(String orderId, OrderStatus status) {
        Order order = orders.findById(orderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Order not found."));
        order.setStatus(status);
        if (status == OrderStatus.DELIVERED && order.getDeliveredAt() == null) order.setDeliveredAt(Instant.now());
        order.setLastUpdated(Instant.now());
        computeRisk(order);
        return orders.save(order);
    }

    // ---------- Stats ----------

    public java.util.Map<String, Object> stats(String userId) {
        List<Order> all = orders.findByUserId(userId, Sort.unsorted());
        java.util.Map<String, Long> byStatus = new java.util.LinkedHashMap<>();
        java.util.Map<String, Long> byPlatform = new java.util.LinkedHashMap<>();
        int atRisk = 0;
        int onTime = 0;
        for (Order o : all) {
            byStatus.merge(String.valueOf(o.getStatus()), 1L, Long::sum);
            byPlatform.merge(String.valueOf(o.getPlatform()), 1L, Long::sum);
            if (o.getRisk() != null && o.getRisk().getLevel() != com.parcelpilot.model.RiskLevel.LOW
                    && o.getStatus() != OrderStatus.DELIVERED) {
                atRisk++;
            } else if (o.getStatus() != OrderStatus.DELIVERED) {
                onTime++;
            }
        }
        long delivered = byStatus.getOrDefault("DELIVERED", 0L);
        return java.util.Map.of(
                "total", (long) all.size(),
                "byStatus", byStatus,
                "byPlatform", byPlatform,
                "atRisk", (long) atRisk,
                "onTime", (long) onTime,
                "delivered", delivered);
    }

    // ---------- Risk ----------

    /** Rules only: city + status + weather -> risk. Called on refresh and when a read finds stale risk. */
    public void computeRisk(Order order) {
        if (order.getStatus() == OrderStatus.DELIVERED) {
            order.setRisk(null); // UI hides risk for delivered orders
            return;
        }
        if (order.getCurrentCity() == null || order.getCurrentCity().isBlank()) {
            order.setRisk(null);
            return;
        }
        Optional<WeatherSnapshot> snapshot = weather.forecastSafe(order.getCurrentCity());
        if (snapshot.isEmpty()) {
            order.setRisk(null);
            return;
        }
        RiskAssessment a = riskService.assess(order.getCurrentCity(), order.getStatus(), snapshot.get());
        RiskInfo info = new RiskInfo();
        info.setLevel(a.level());
        info.setScore(a.score());
        info.setReasons(a.reasons());
        info.setMessage(a.message());
        info.setComputedAt(Instant.now());
        order.setRisk(info);
    }

    private void refreshStaleRisk(List<Order> ordersList) {
        boolean dirty = false;
        for (Order o : ordersList) {
            if (o.getStatus() == OrderStatus.DELIVERED || o.getCurrentCity() == null) continue;
            boolean stale = o.getRisk() == null || o.getRisk().getComputedAt() == null
                    || Duration.between(o.getRisk().getComputedAt(), Instant.now()).compareTo(RISK_STALE_AFTER) > 0;
            if (stale) {
                computeRisk(o);
                orders.save(o);
                dirty = true;
            }
        }
        if (dirty) log.debug("Recomputed stale risk scores on read");
    }

    // ---------- Internals ----------

    private void applyTracking(Order order, TrackingResult result) {
        order.setStatus(result.status());
        if (result.currentCity() != null && !result.currentCity().isBlank()) order.setCurrentCity(result.currentCity());
        if (result.estimatedDelivery() != null) order.setEstimatedDelivery(result.estimatedDelivery());
        if (result.deliveredAt() != null) order.setDeliveredAt(result.deliveredAt());
        if (result.events() != null && !result.events().isEmpty()) {
            order.setEvents(new java.util.ArrayList<>(result.events()));
        }
    }

    private static Comparator<Order> comparatorFor(String sort) {
        return switch (sort == null ? "" : sort) {
            case "eta" -> Comparator.comparing(Order::getEstimatedDelivery,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "risk" -> Comparator.comparing((Order o) -> o.getRisk() == null ? 0 : o.getRisk().getScore())
                    .reversed();
            default -> Comparator.comparing(Order::getLastUpdated,
                    Comparator.nullsLast(Comparator.reverseOrder()));
        };
    }

    private static Instant etaInstant(LocalDate date) {
        return date.atTime(LocalTime.NOON).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
    }

    private static String domainOf(String from) {
        if (from == null) return "unknown";
        int at = from.lastIndexOf('@');
        String d = at < 0 ? from : from.substring(at + 1);
        return d.toLowerCase(Locale.ROOT).replaceAll("[>\\s].*$", "");
    }

    private static String last4(String awb) {
        return awb == null ? "****" : awb.substring(Math.max(0, awb.length() - 4));
    }
}
