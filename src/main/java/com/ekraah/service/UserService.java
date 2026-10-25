package com.ekraah.service;

import com.ekraah.exception.ApiException;
import com.ekraah.model.Order;
import com.ekraah.model.User;
import com.ekraah.repository.OrderRepository;
import com.ekraah.repository.UserRepository;
import com.ekraah.util.Mask;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class UserService {

    /** City names: letters, spaces, dots, hyphens and apostrophes; max 40 chars. */
    private static final Pattern CITY_PATTERN = Pattern.compile("[\\p{L}][\\p{L}\\s.'-]{0,39}");

    private final UserRepository users;
    private final OrderRepository orders;
    private final AuditService audit;
    private final InboundAliases aliases;
    private final AlertService alerts;

    public UserService(UserRepository users, OrderRepository orders, AuditService audit, InboundAliases aliases,
                       AlertService alerts) {
        this.users = users;
        this.orders = orders;
        this.audit = audit;
        this.aliases = aliases;
        this.alerts = alerts;
    }

    /**
     * Replaces the inbound alias with a new random one: for accounts whose alias was derived from
     * the phone number (before InboundAliases) or whose address leaked. The old address stops
     * working at once, so forwarding must be set up again (forwardingVerified resets).
     */
    public User regenerateAlias(String userId) {
        User user = get(userId);
        user.setInboundAlias(aliases.newUniqueAlias());
        user.setForwardingVerified(false);
        users.save(user);
        audit.record(userId, null, com.ekraah.model.AuditAction.PROCESSED,
                "Forwarding address replaced — the old address no longer adds orders");
        return user;
    }

    public User get(String userId) {
        return users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Session expired, please log in again."));
    }

    public User updateDisplayName(String userId, String displayName) {
        String name = displayName == null ? "" : displayName.trim().replaceAll("\\s+", " ");
        if (name.length() < 2 || name.length() > 40) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Display name must be 2-40 characters.");
        }
        User user = get(userId);
        if (!name.equals(user.getDisplayName())) {
            user.setDisplayName(name);
            users.save(user);
            audit.record(userId, null, com.ekraah.model.AuditAction.PROCESSED,
                    "Profile display name updated");
        }
        return user;
    }

    public User updateSettings(String userId, String theme, Boolean notificationsOn, String defaultCity) {
        User user = get(userId);
        if (theme != null) {
            if (!theme.equals("light") && !theme.equals("dark")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Theme must be 'light' or 'dark'.");
            }
            user.getSettings().setTheme(theme);
        }
        if (notificationsOn != null) user.getSettings().setNotificationsOn(notificationsOn);
        if (defaultCity != null) {
            String city = defaultCity.trim();
            if (city.isEmpty()) {
                user.getSettings().setDefaultCity(null);         // empty string clears the preference
            } else {
                if (!CITY_PATTERN.matcher(city).matches()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST,
                            "City may only contain letters, spaces, dots, hyphens and apostrophes (max 40).");
                }
                user.getSettings().setDefaultCity(city);
            }
        }
        return users.save(user);
    }

    /**
     * Personal data export: profile + orders. Internal fields (password hash, Mongo ids,
     * userId, dedupe keys, pipeline events) are excluded by omission; the phone number is
     * masked — it never leaves the profile page in clear text.
     */
    public Map<String, Object> exportData(String userId) {
        User u = get(userId);
        List<Map<String, Object>> orderList = orders
                .findByUserId(userId, Sort.by(Sort.Direction.DESC, "lastUpdated"))
                .stream().map(UserService::exportOrder).toList();

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("theme", u.getSettings().getTheme());
        settings.put("notificationsOn", u.getSettings().isNotificationsOn());
        settings.put("defaultCity", u.getSettings().getDefaultCity());

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("phone", Mask.phone(u.getPhone()));
        profile.put("displayName", u.getDisplayName());
        profile.put("memberSince", u.getCreatedAt());
        profile.put("inboundAlias", u.getInboundAlias());
        profile.put("forwardingVerified", u.isForwardingVerified());
        profile.put("settings", settings);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exportedAt", Instant.now().toString());
        out.put("profile", profile);
        out.put("orders", orderList);
        return out;
    }

    private static Map<String, Object> exportOrder(Order o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("platform", String.valueOf(o.getPlatform()));
        m.put("trackingNumber", o.getTrackingNumber());
        m.put("courier", o.getCourier());
        m.put("status", String.valueOf(o.getStatus()));
        m.put("currentCity", o.getCurrentCity());
        m.put("estimatedDelivery", o.getEstimatedDelivery());
        m.put("deliveredAt", o.getDeliveredAt());
        m.put("lastUpdated", o.getLastUpdated());
        m.put("source", String.valueOf(o.getSource()));
        if (o.getRisk() != null) {
            Map<String, Object> risk = new LinkedHashMap<>();
            risk.put("level", String.valueOf(o.getRisk().getLevel()));
            risk.put("score", o.getRisk().getScore());
            risk.put("reasons", o.getRisk().getReasons());
            m.put("risk", risk);
        }
        return m;
    }

    /** Deletes the user and every piece of their data (GDPR-style purge). */
    public void deleteAccount(String userId) {
        User user = get(userId);
        orders.deleteAll(orders.findByUserId(userId, Sort.unsorted()));
        audit.deleteForUser(userId);
        alerts.deleteForUser(userId);
        users.delete(user);
    }
}
