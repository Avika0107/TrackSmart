package com.ekraah.service;

import com.ekraah.dto.OrderDtos.ImportOrderRequest;
import com.ekraah.dto.RawEmail;
import com.ekraah.exception.ApiException;
import com.ekraah.mail.ParsedEmail;
import com.ekraah.model.AuditAction;
import com.ekraah.model.Order;
import com.ekraah.model.OrderEvent;
import com.ekraah.model.OrderSource;
import com.ekraah.model.OrderStatus;
import com.ekraah.model.Platform;
import com.ekraah.model.RiskInfo;
import com.ekraah.model.TrackingMode;
import com.ekraah.model.User;
import com.ekraah.repository.OrderRepository;
import com.ekraah.repository.UserRepository;
import com.ekraah.risk.DelayRiskService;
import com.ekraah.risk.RiskAssessment;
import com.ekraah.tracking.TrackingResult;
import com.ekraah.tracking.TrackingService;
import com.ekraah.util.CourierDetector;
import com.ekraah.util.Mask;
import com.ekraah.weather.WeatherService;
import com.ekraah.weather.WeatherSnapshot;
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
    private final AlertService alerts;

    public OrderService(OrderRepository orders, UserRepository users, TrackingService tracking,
                        WeatherService weather, DelayRiskService riskService, AuditService audit,
                        AlertService alerts) {
        this.orders = orders;
        this.users = users;
        this.tracking = tracking;
        this.weather = weather;
        this.riskService = riskService;
        this.audit = audit;
        this.alerts = alerts;
    }

    public enum EmailOutcome { CREATED, UPDATED, DUPLICATE }

    public record EmailResult(EmailOutcome outcome, Order order) {}

    // ---------- Email pipeline ----------

    public EmailResult upsertFromEmail(User user, ParsedEmail parsed, RawEmail email) {
        String domain = domainOf(email.from());

        // A replayed email (IMAP re-delivery, re-forward) must not re-apply: check both the email
        // that created an order and every email applied to one since.
        if (email.messageId() != null
                && (orders.findByUserIdAndSourceMessageId(user.getId(), email.messageId()).isPresent()
                    || orders.existsByUserIdAndMessageIds(user.getId(), email.messageId()))) {
            audit.record(user.getId(), domain, AuditAction.DUPLICATE,
                    "Duplicate message ignored (AWB " + Mask.tracking(parsed.trackingNumber()) + ")");
            return new EmailResult(EmailOutcome.DUPLICATE, null);
        }

        // Order-confirmation email (no tracking number yet): create a placeholder card
        // that is upgraded to full tracking when the shipped email arrives.
        if (!parsed.hasTracking()) {
            if (parsed.cancelled()) {
                return cancelMatchingOrder(user, parsed, email, domain);
            }
            return upsertConfirmation(user, parsed, email, domain);
        }

        // Cancellation email with a tracking/order number: close that order.
        if (parsed.cancelled()) {
            return cancelMatchingOrder(user, parsed, email, domain);
        }

        Optional<Order> existing = orders
                .findFirstByUserIdAndTrackingNumberOrderByLastUpdatedDesc(user.getId(), parsed.trackingNumber());

        // Shipping email for an order we already track as a confirmation placeholder:
        // merge the tracking number into the existing card instead of duplicating it.
        if (existing.isEmpty()) {
            Optional<Order> pending = orders.findFirstByUserIdAndTrackingModeOrderByLastUpdatedDesc(
                    user.getId(), TrackingMode.EMAIL_PENDING);
            if (pending.isPresent() && pending.get().getPlatform() == parsed.platform()) {
                return upgradePendingOrder(pending.get(), parsed, email, domain);
            }
        }

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
            if (order.getStatus() == OrderStatus.CANCELLED) order.setRisk(null);
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
        if (order.getStatus() == OrderStatus.CANCELLED) {
            order.setRisk(null); // terminal: no delay risk on a cancelled order
        } else {
            computeRisk(order);
        }
        rememberMessage(order, email);
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

    /** Cancellation email: mark the matching order (or pending placeholder) CANCELLED. */
    private EmailResult cancelMatchingOrder(User user, ParsedEmail parsed, RawEmail email, String domain) {
        Optional<Order> target = parsed.hasTracking()
                ? orders.findFirstByUserIdAndTrackingNumberOrderByLastUpdatedDesc(user.getId(), parsed.trackingNumber())
                : Optional.empty();
        // No AWB on the cancellation mail (common): cancel the newest pending confirmation
        // for the same platform — a just-placed order cancelled before shipping.
        if (target.isEmpty()) {
            target = orders.findFirstByUserIdAndTrackingModeOrderByLastUpdatedDesc(
                    user.getId(), TrackingMode.EMAIL_PENDING)
                    .filter(o -> o.getPlatform() == parsed.platform() && o.getStatus() != OrderStatus.CANCELLED);
        }
        if (target.isEmpty()) {
            audit.record(user.getId(), domain, AuditAction.IGNORED_NO_TRACKING,
                    "Cancellation email but no matching order on this account");
            return new EmailResult(EmailOutcome.UPDATED, null);
        }

        Order order = target.get();
        if (order.getStatus() == OrderStatus.CANCELLED) {
            audit.record(user.getId(), domain, AuditAction.DUPLICATE,
                    "Order already cancelled (" + Mask.tracking(order.getTrackingNumber()) + ")");
            return new EmailResult(EmailOutcome.DUPLICATE, order);
        }
        order.setStatus(OrderStatus.CANCELLED);
        order.getEvents().add(new OrderEvent("Order cancelled — cancellation email received from " + domain, null, Instant.now()));
        order.setLastUpdated(Instant.now());
        order.setRisk(null); // terminal: no delay risk on a cancelled order
        rememberMessage(order, email);
        orders.save(order);
        audit.record(user.getId(), domain, AuditAction.PROCESSED,
                "Order " + Mask.tracking(order.getTrackingNumber()) + " marked CANCELLED from " + domain + " email");
        log.info("Email from {} -> order {} cancelled", domain, Mask.tracking(order.getTrackingNumber()));
        return new EmailResult(EmailOutcome.UPDATED, order);
    }

    /** Confirmation email ("order placed", no AWB yet) -> placeholder card shown as Ordered. */
    private EmailResult upsertConfirmation(User user, ParsedEmail parsed, RawEmail email, String domain) {
        // Latest pending placeholder wins: if several confirmations stack up, promote the
        // newest one so the next shipping email upgrades the order the user just placed.
        Optional<Order> pending = orders.findFirstByUserIdAndTrackingModeOrderByLastUpdatedDesc(
                user.getId(), TrackingMode.EMAIL_PENDING);
        if (pending.isPresent() && pending.get().getPlatform() == parsed.platform()) {
            Order p = pending.get();
            p.setSourceMessageId(email.messageId());
            p.getEvents().add(new OrderEvent("Order confirmation received from " + domain, null, Instant.now()));
            if (parsed.estimatedDelivery() != null) p.setEstimatedDelivery(etaInstant(parsed.estimatedDelivery()));
            p.setLastUpdated(Instant.now());
            rememberMessage(p, email);
            orders.save(p);
            audit.record(user.getId(), domain, AuditAction.PROCESSED,
                    "Order confirmation from " + domain + " (tracking number not issued yet)");
            return new EmailResult(EmailOutcome.UPDATED, p);
        }

        Order order = new Order();
        order.setUserId(user.getId());
        order.setPlatform(parsed.platform());
        order.setTrackingNumber("PENDING-" + System.currentTimeMillis());
        order.setTrackingMode(TrackingMode.EMAIL_PENDING);
        order.setSource(OrderSource.EMAIL);
        order.setSourceMessageId(email.messageId());
        order.setStatus(OrderStatus.ORDERED);
        if (parsed.estimatedDelivery() != null) order.setEstimatedDelivery(etaInstant(parsed.estimatedDelivery()));
        order.getEvents().add(new OrderEvent("Order placed — confirmation email received from " + domain, null, Instant.now()));
        order.setLastUpdated(Instant.now());
        rememberMessage(order, email);
        orders.save(order);
        audit.record(user.getId(), domain, AuditAction.PROCESSED,
                "Order confirmation from " + domain + " (tracking number not issued yet)");
        log.info("Email from {} -> confirmation placeholder order", domain);
        return new EmailResult(EmailOutcome.CREATED, order);
    }

    /** Shipping email arrived for a confirmation placeholder: attach the real tracking number. */
    private EmailResult upgradePendingOrder(Order pending, ParsedEmail parsed, RawEmail email, String domain) {
        pending.setTrackingNumber(parsed.trackingNumber());
        pending.setCourier(parsed.courier());
        // Amazon TBA/order ids cannot go to third-party APIs: email-only mode.
        pending.setTrackingMode(parsed.platform() == Platform.AMAZON ? TrackingMode.EMAIL_ONLY : TrackingMode.API);
        pending.setSourceMessageId(email.messageId());
        if (parsed.status() != null && parsed.status() != OrderStatus.ORDERED) pending.setStatus(parsed.status());
        if (parsed.estimatedDelivery() != null) pending.setEstimatedDelivery(etaInstant(parsed.estimatedDelivery()));
        pending.getEvents().add(new OrderEvent(
                "Tracking number issued — now tracked via " + (parsed.courier() != null ? parsed.courier() : "email") + (parsed.delayed() ? " (retailer reported a delay)" : ""),
                null, Instant.now()));
        pending.setLastUpdated(Instant.now());
        computeRisk(pending);
        rememberMessage(pending, email);
        orders.save(pending);
        audit.record(pending.getUserId(), domain, AuditAction.PROCESSED,
                "Extracted AWB ending " + last4(parsed.trackingNumber()) + " from " + domain
                        + " — order now fully tracked");
        log.info("Email from {} -> upgraded pending order with AWB {}", domain, Mask.tracking(parsed.trackingNumber()));
        return new EmailResult(EmailOutcome.UPDATED, pending);
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

    // ---------- Browser extension import ----------

    public enum ImportOutcome { CREATED, UPDATED, ALREADY_TRACKED }

    public record ImportResult(ImportOutcome outcome, Order order) {}

    /** Couriers whose AWBs are plain digits and overlap by length (see CourierDetector). */
    private static final java.util.Set<String> DIGIT_COURIERS = java.util.Set.of("Blue Dart", "Delhivery", "Xpressbees");

    /**
     * Facts read by the extension from a retailer's order page. Idempotent: sending the same
     * order again updates or returns the existing card instead of duplicating it.
     *  - With a tracking number: live tracking, re-validated by CourierDetector. Upgrades the
     *    PAGE_ONLY card of the same retailer order id, or an EMAIL_PENDING placeholder.
     *  - Order id only (Flipkart never shows the Ekart AWB), or any order the page says is already
     *    delivered: a PAGE_ONLY card whose status and dates come from the page; clicking Send
     *    again later refreshes them. A tracking number can be attached later (attachTracking).
     */
    public ImportResult importFromExtension(String userId, ImportOrderRequest req) {
        String number = blankToNull(req.trackingNumber());
        String orderId = blankToNull(req.retailerOrderId());
        if (number == null && orderId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No tracking number or order id was found on this page.");
        }
        if (orderId != null) orderId = orderId.toUpperCase(Locale.ROOT);
        String source = "browser extension (" + req.platform().name().toLowerCase(Locale.ROOT) + ")";
        // Already finished (delivered / returned / cancelled): nothing left to track, so don't spend
        // tracking-API quota — the order id and the page's facts are enough. Needs an order id as key.
        boolean finished = req.status() != null && req.status().isTerminal();
        return number == null || (finished && orderId != null)
                ? importByOrderId(userId, req, orderId, source)
                : importByTrackingNumber(userId, req, number.toUpperCase(Locale.ROOT), orderId, source);
    }

    private ImportResult importByTrackingNumber(String userId, ImportOrderRequest req, String number,
                                                String orderId, String source) {
        Platform platform = req.platform();
        String courier = resolveCourier(number, req.courierHint())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "That doesn't look like a tracking number we support yet."));

        Optional<Order> existing = orders.findFirstByUserIdAndTrackingNumberOrderByLastUpdatedDesc(userId, number);
        if (existing.isPresent()) {
            audit.record(userId, source, AuditAction.DUPLICATE,
                    "Already tracking " + Mask.tracking(number) + " — extension import skipped");
            return new ImportResult(ImportOutcome.ALREADY_TRACKED, existing.get());
        }

        // A card we already have for this order, still without a tracking number: the page-only
        // card of the same order id, else the newest email-confirmation placeholder of this store.
        Optional<Order> placeholder = Optional.ofNullable(orderId)
                .flatMap(id -> orders.findFirstByUserIdAndRetailerOrderIdOrderByLastUpdatedDesc(userId, id))
                .filter(o -> o.getTrackingMode() == TrackingMode.PAGE_ONLY);
        if (placeholder.isEmpty()) placeholder = pendingPlaceholder(userId, platform);
        boolean created = placeholder.isEmpty();

        Order order = placeholder.orElseGet(Order::new);
        if (created) {
            order.setUserId(userId);
            order.setPlatform(platform);
            order.setSource(OrderSource.EXTENSION);
            order.setStatus(OrderStatus.SHIPPED);   // a tracking number on the page means it shipped
            addEvent(order, "Imported from the " + platform + " order page via browser extension");
        } else {
            addEvent(order, "Tracking number added from the " + platform + " order page via browser extension");
        }
        order.setTrackingNumber(number);
        order.setCourier(courier);
        if (orderId != null) order.setRetailerOrderId(orderId);
        // Amazon TBA/order ids cannot go to third-party APIs: email-only mode.
        order.setTrackingMode(courier.equals("Amazon") ? TrackingMode.EMAIL_ONLY : TrackingMode.API);
        if (order.getStatus() == OrderStatus.ORDERED) order.setStatus(OrderStatus.SHIPPED);

        if (order.getTrackingMode() == TrackingMode.API) {
            applyTracking(order, tracking.track(number, courier));
        }
        order.setLastUpdated(Instant.now());
        computeRisk(order);
        Order saved = orders.save(order);

        audit.record(userId, source, AuditAction.PROCESSED,
                (created ? "Imported AWB ending " : "Added AWB ending ") + last4(number)
                        + (created ? "" : " to an existing order") + " via browser extension");
        log.info("Extension import -> {} order {}", created ? "created" : "upgraded", Mask.tracking(number));
        return new ImportResult(created ? ImportOutcome.CREATED : ImportOutcome.UPDATED, saved);
    }

    /** Page shows only the retailer's order id: follow the order through that page. */
    private ImportResult importByOrderId(String userId, ImportOrderRequest req, String orderId, String source) {
        Optional<Order> existing = orders.findFirstByUserIdAndRetailerOrderIdOrderByLastUpdatedDesc(userId, orderId);
        if (existing.isPresent() && !isPlaceholder(existing.get())) {
            // Already followed by tracking number: live/email data beats what the page says.
            audit.record(userId, source, AuditAction.DUPLICATE,
                    "Order " + Mask.tracking(orderId) + " already tracked — extension import skipped");
            return new ImportResult(ImportOutcome.ALREADY_TRACKED, existing.get());
        }

        Optional<Order> target = existing.or(() -> pendingPlaceholder(userId, req.platform()));
        boolean created = target.isEmpty();
        Order order = target.orElseGet(Order::new);
        if (created) {
            order.setUserId(userId);
            order.setPlatform(req.platform());
            order.setSource(OrderSource.EXTENSION);
            order.setTrackingNumber(orderId);       // shown on the card; the store gives no AWB
            order.setTrackingMode(TrackingMode.PAGE_ONLY);
            order.setStatus(OrderStatus.ORDERED);
            addEvent(order, "Imported from the " + req.platform()
                    + " order page via browser extension (the store shows no tracking number)");
        }
        // An EMAIL_PENDING placeholder keeps its mode, so the shipping email can still upgrade it.
        order.setRetailerOrderId(orderId);
        applyPageFacts(order, req);
        order.setLastUpdated(Instant.now());
        computeRisk(order);                         // no courier city -> no weather risk
        Order saved = orders.save(order);

        audit.record(userId, source, AuditAction.PROCESSED,
                (created ? "Imported order " : "Updated order ") + Mask.tracking(orderId)
                        + " from the store's order page via browser extension (status " + saved.getStatus() + ")");
        log.info("Extension import -> {} page-only order {}", created ? "created" : "updated", Mask.tracking(orderId));
        return new ImportResult(created ? ImportOutcome.CREATED : ImportOutcome.UPDATED, saved);
    }

    /**
     * The user typed the courier's tracking number into a card that had none (e.g. a Flipkart
     * order — the Ekart number arrives by SMS, never on the page): switch it to live tracking.
     */
    public Order attachTracking(String userId, String orderId, String trackingNumber) {
        Order order = getOwned(userId, orderId);
        if (!isPlaceholder(order)) {
            throw new ApiException(HttpStatus.CONFLICT, "This order already has a tracking number.");
        }
        if (isFinished(order)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This order is already " + order.getStatus().name().toLowerCase(Locale.ROOT) + " — nothing left to track.");
        }
        String number = trackingNumber.trim().toUpperCase(Locale.ROOT);
        String courier = resolveCourier(number, null)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "That doesn't look like a tracking number we support yet."));
        orders.findFirstByUserIdAndTrackingNumberOrderByLastUpdatedDesc(userId, number)
                .filter(other -> !other.getId().equals(order.getId()))
                .ifPresent(other -> {
                    throw new ApiException(HttpStatus.CONFLICT, "You are already tracking this number on another card.");
                });

        if (order.getRetailerOrderId() == null && order.getTrackingMode() == TrackingMode.PAGE_ONLY) {
            order.setRetailerOrderId(order.getTrackingNumber());   // keep the store's id before replacing it
        }
        order.setTrackingNumber(number);
        order.setCourier(courier);
        order.setTrackingMode(courier.equals("Amazon") ? TrackingMode.EMAIL_ONLY : TrackingMode.API);
        if (order.getStatus() == OrderStatus.ORDERED || order.getStatus() == OrderStatus.UNKNOWN) {
            order.setStatus(OrderStatus.SHIPPED);
        }
        addEvent(order, "Tracking number added manually — now tracked via " + courier);
        if (order.getTrackingMode() == TrackingMode.API) {
            applyTracking(order, tracking.trackForce(number, courier));
        }
        order.setLastUpdated(Instant.now());
        computeRisk(order);
        Order saved = orders.save(order);
        audit.record(userId, "manual", AuditAction.PROCESSED,
                "Tracking number ending " + last4(number) + " added to order " + Mask.tracking(order.getRetailerOrderId()));
        log.info("Attached AWB {} to order {}", Mask.tracking(number), Mask.tracking(order.getRetailerOrderId()));
        return saved;
    }

    /** Status and dates printed on the order page; only used for cards without live tracking. */
    private void applyPageFacts(Order order, ImportOrderRequest req) {
        OrderStatus before = order.getStatus();
        if (req.status() != null) order.setStatus(req.status());
        if (req.expectedDelivery() != null) order.setEstimatedDelivery(etaInstant(req.expectedDelivery()));
        if (order.getStatus() == OrderStatus.DELIVERED) {
            if (req.deliveredOn() != null) order.setDeliveredAt(etaInstant(req.deliveredOn()));
            else if (order.getDeliveredAt() == null) order.setDeliveredAt(Instant.now());
        }
        if (order.getStatus() != before) {
            addEvent(order, "Status from the " + order.getPlatform() + " order page: " + order.getStatus());
        }
    }

    /** Newest email-confirmation placeholder of this store that isn't already tied to another order id. */
    private Optional<Order> pendingPlaceholder(String userId, Platform platform) {
        return orders.findFirstByUserIdAndTrackingModeOrderByLastUpdatedDesc(userId, TrackingMode.EMAIL_PENDING)
                .filter(o -> o.getPlatform() == platform && o.getStatus() != OrderStatus.CANCELLED
                        && o.getRetailerOrderId() == null);
    }

    /** Records an applied email so a replay of it is recognised as a duplicate. */
    private static void rememberMessage(Order order, RawEmail email) {
        if (email.messageId() == null) return;
        List<String> ids = new java.util.ArrayList<>(order.getMessageIds() == null ? List.of() : order.getMessageIds());
        if (!ids.contains(email.messageId())) ids.add(email.messageId());
        order.setMessageIds(ids);
    }

    /** Delivered, cancelled or returned: no tracking, no risk, not counted as on-time. */
    private static boolean isFinished(Order o) {
        return o.getStatus() != null && o.getStatus().isTerminal();
    }

    private static boolean isPlaceholder(Order o) {
        return o.getTrackingMode() == TrackingMode.PAGE_ONLY || o.getTrackingMode() == TrackingMode.EMAIL_PENDING;
    }

    /** Timelines cleared by deleteOldTimelines are immutable List.of(), so copy before adding. */
    private static void addEvent(Order order, String description) {
        List<OrderEvent> events = new java.util.ArrayList<>(order.getEvents() == null ? List.of() : order.getEvents());
        events.add(new OrderEvent(description, null, Instant.now()));
        order.setEvents(events);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * The number's format decides; the page's courier name only breaks ties between the
     * digit-only couriers (a 10-digit AWB is Blue Dart unless the page says Delhivery).
     */
    private static Optional<String> resolveCourier(String number, String courierHint) {
        Optional<String> detected = CourierDetector.detect(number, null);
        if (detected.isEmpty()) return Optional.empty();
        String hint = courierHint == null ? "" : courierHint.trim();
        if (number.matches("\\d+") && DIGIT_COURIERS.contains(detected.get()) && DIGIT_COURIERS.contains(hint)) {
            return Optional.of(hint);
        }
        return detected;
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
        if (order.getTrackingMode() == TrackingMode.PAGE_ONLY) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This store doesn't share a tracking number. Open the order on the store's site and click Send in the EkRaah extension to update it.");
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

    /**
     * Demo weather toggle: re-score every active order now. Stored risk is otherwise only
     * recomputed once it is older than an hour, so the toggle had no visible effect.
     */
    public int recomputeActiveRisk() {
        List<Order> active = orders.findByStatusIn(List.copyOf(OrderStatus.ACTIVE));
        for (Order order : active) {
            computeRisk(order);
            orders.save(order);
        }
        return active.size();
    }

    /**
     * Daily: overdue-ness changes with the calendar, not with an order update, so orders nobody
     * refreshes (email-only, store-page-only) would never raise their OVERDUE alert otherwise.
     */
    public int alertOverdueOrders() {
        Instant startOfToday = LocalDate.now(ZoneId.of("Asia/Kolkata")).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant();
        List<Order> late = orders.findByStatusInAndEstimatedDeliveryBefore(List.copyOf(OrderStatus.ACTIVE), startOfToday);
        late.forEach(alerts::evaluate);
        return late.size();
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
            boolean riskyWeather = o.getRisk() != null && o.getRisk().getLevel() != com.ekraah.model.RiskLevel.LOW;
            if ((riskyWeather || o.isOverdue()) && !isFinished(o)) {   // late already counts as at risk
                atRisk++;
            } else if (!isFinished(o)) {
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
        scoreRisk(order);
        // Every status/risk change path ends here, so alerts are evaluated in one place.
        alerts.evaluate(order);
    }

    private void scoreRisk(Order order) {
        if (isFinished(order)) {
            order.setRisk(null); // UI hides risk for delivered / cancelled / returned orders
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
            if (isFinished(o) || o.getCurrentCity() == null) continue;
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
