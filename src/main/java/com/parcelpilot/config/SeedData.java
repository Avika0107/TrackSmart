package com.parcelpilot.config;

import com.parcelpilot.model.Order;
import com.parcelpilot.model.OrderEvent;
import com.parcelpilot.model.OrderSource;
import com.parcelpilot.model.OrderStatus;
import com.parcelpilot.model.Platform;
import com.parcelpilot.model.TrackingMode;
import com.parcelpilot.model.User;
import com.parcelpilot.repository.OrderRepository;
import com.parcelpilot.repository.UserRepository;
import com.parcelpilot.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Demo profile: one demo user (phone 9999999999, alias rahul55) with 6 varied
 * orders, including one HIGH-risk stormy example (mock weather starts in stormy
 * mode) and one delivered order.
 */
@Component
public class SeedData implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedData.class);

    static final String DEMO_PHONE = "9999999999";
    static final String DEMO_PASSWORD = "demo1234";

    private final UserRepository users;
    private final OrderRepository orders;
    private final OrderService orderService;
    private final Environment env;
    private final AppProperties props;
    private final PasswordEncoder encoder;

    public SeedData(UserRepository users, OrderRepository orders, OrderService orderService,
                    Environment env, AppProperties props, PasswordEncoder encoder) {
        this.users = users;
        this.orders = orders;
        this.orderService = orderService;
        this.env = env;
        this.props = props;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.getSeed().isEnabled() || !isDemo()) return;
        try {
            seedIfEmpty();
        } catch (Exception e) {
            log.error("--------------------------------------------------------------------");
            log.error("Seeding skipped: MongoDB is unreachable ({}). Start the database and restart.", e.getMessage());
            log.error("--------------------------------------------------------------------");
        }
    }

    private void seedIfEmpty() {
        ensureDemoUserPassword();   // upgrade path for DBs created before passwords existed
        if (users.count() > 0) return;

        User rahul = User.of("9999999999", "rahul55", "Rahul");
        rahul.setPasswordHash(encoder.encode(DEMO_PASSWORD));
        users.save(rahul);

        Instant now = Instant.now();
        List<Order> seed = List.of(
                order(rahul, Platform.FLIPKART, "FMP" + 4471203 + "", "Ekart", OrderStatus.IN_TRANSIT,
                        TrackingMode.API, now.plus(2, ChronoUnit.DAYS), "Mumbai", OrderSource.EMAIL, "seed-flipkart-1",
                        List.of(
                                new OrderEvent("Order confirmed", "Bengaluru", now.minus(3, ChronoUnit.DAYS)),
                                new OrderEvent("Shipped from seller hub", "Bengaluru", now.minus(2, ChronoUnit.DAYS)),
                                new OrderEvent("Arrived at Mumbai facility", "Mumbai", now.minus(10, ChronoUnit.HOURS))),
                        null),
                order(rahul, Platform.MYNTRA, "11234567891", "Delhivery", OrderStatus.OUT_FOR_DELIVERY,
                        TrackingMode.API, now.plus(8, ChronoUnit.HOURS), "Bengaluru", OrderSource.EMAIL, "seed-myntra-1",
                        List.of(
                                new OrderEvent("Shipped", "Delhi", now.minus(4, ChronoUnit.DAYS)),
                                new OrderEvent("In transit", "Hyderabad", now.minus(1, ChronoUnit.DAYS)),
                                new OrderEvent("Out for delivery", "Bengaluru", now.minus(2, ChronoUnit.HOURS))),
                        null),
                order(rahul, Platform.NYKAA, "5551234567", "Blue Dart", OrderStatus.SHIPPED,
                        TrackingMode.API, now.plus(3, ChronoUnit.DAYS), "Pune", OrderSource.EMAIL, "seed-nykaa-1",
                        List.of(
                                new OrderEvent("Picked up", "Mumbai", now.minus(1, ChronoUnit.DAYS)),
                                new OrderEvent("In transit", "Pune", now.minus(6, ChronoUnit.HOURS))),
                        null),
                order(rahul, Platform.AMAZON, "402-5684713-9912543", "Amazon", OrderStatus.SHIPPED,
                        TrackingMode.EMAIL_ONLY, now.plus(4, ChronoUnit.DAYS), null, OrderSource.EMAIL, "seed-amazon-1",
                        List.of(new OrderEvent("Order email received from amazon.in — tracked via email only",
                                null, now.minus(1, ChronoUnit.DAYS))),
                        null),
                order(rahul, Platform.AJIO, "990011223344", "Xpressbees", OrderStatus.DELIVERED,
                        TrackingMode.API, now.minus(5, ChronoUnit.DAYS), "Delhi", OrderSource.EMAIL, "seed-ajio-1",
                        List.of(
                                new OrderEvent("Shipped", "Jaipur", now.minus(9, ChronoUnit.DAYS)),
                                new OrderEvent("Out for delivery", "Delhi", now.minus(5, ChronoUnit.DAYS)),
                                new OrderEvent("Delivered", "Delhi", now.minus(5, ChronoUnit.DAYS))),
                        now.minus(5, ChronoUnit.DAYS)),
                order(rahul, Platform.FLIPKART, "FMP" + 1189902 + "", "Ekart", OrderStatus.DELIVERED,
                        TrackingMode.API, now.minus(40, ChronoUnit.DAYS), "Chennai", OrderSource.MANUAL, null,
                        List.of(new OrderEvent("Delivered", "Chennai", now.minus(40, ChronoUnit.DAYS))),
                        now.minus(40, ChronoUnit.DAYS)));

        for (Order o : seed) {
            orderService.computeRisk(o);
            orders.save(o);
        }
        log.info("Seeded demo user 999****9999 (alias rahul55) with {} orders", seed.size());
    }

    private static Order order(User user, Platform platform, String awb, String courier, OrderStatus status,
                               TrackingMode mode, Instant eta, String city, OrderSource source, String messageId,
                               List<OrderEvent> events, Instant deliveredAt) {
        Order o = new Order();
        o.setUserId(user.getId());
        o.setPlatform(platform);
        o.setTrackingNumber(awb);
        o.setCourier(courier);
        o.setStatus(status);
        o.setTrackingMode(mode);
        o.setEstimatedDelivery(eta);
        o.setCurrentCity(city);
        o.setSource(source);
        o.setSourceMessageId(messageId);
        o.setEvents(new java.util.ArrayList<>(events));
        o.setDeliveredAt(deliveredAt);
        o.setLastUpdated(Instant.now());
        return o;
    }

    /** Demo databases created before password login existed get the default demo password. */
    private void ensureDemoUserPassword() {
        users.findByPhone(DEMO_PHONE)
                .filter(u -> u.getPasswordHash() == null || u.getPasswordHash().isBlank())
                .ifPresent(u -> {
                    u.setPasswordHash(encoder.encode(DEMO_PASSWORD));
                    users.save(u);
                    log.info("Set default demo password for 999****9999 (demo1234)");
                });
    }

    private boolean isDemo() {
        for (String p : env.getActiveProfiles()) if (p.equals("demo")) return true;
        return env.getActiveProfiles().length == 0;
    }
}
