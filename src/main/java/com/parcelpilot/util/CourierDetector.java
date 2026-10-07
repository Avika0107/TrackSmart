package com.parcelpilot.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Detects the courier from an AWB/tracking number, optionally biased by the
 * sender domain of the order email.
 *
 * Assumptions (documented in README):
 *  - Amazon internal numbers start with TBA (or a 171-…-… order id) and are never
 *    sent to third-party tracking APIs (EMAIL_ONLY mode).
 *  - Ekart (Flipkart) numbers start with FMP; Shadowfax with SF; India Post is
 *    13 chars ending in IN.
 *  - Delhivery 10-18 digits, Blue Dart 10 digits, Xpressbees 12-15 digits overlap,
 *    so the sender domain hint (delhivery.com vs xpressbees.com) breaks ties.
 */
public final class CourierDetector {

    private CourierDetector() {}

    private static final Map<String, Pattern> BY_PREFIX = new LinkedHashMap<>();
    private static final Map<String, String> BY_DOMAIN = new LinkedHashMap<>();

    static {
        BY_PREFIX.put("Amazon", Pattern.compile("^(TBA\\d{9,15}|\\d{3}-\\d{7}-\\d{7})$"));
        BY_PREFIX.put("Ekart", Pattern.compile("^FMP\\d{6,12}$"));
        BY_PREFIX.put("Shadowfax", Pattern.compile("^SF\\d{9,15}$"));
        BY_PREFIX.put("India Post", Pattern.compile("^[A-Z]{2}\\d{9}IN$"));

        BY_DOMAIN.put("ekartlogistics.com", "Ekart");
        BY_DOMAIN.put("flipkart.com", "Ekart");
        BY_DOMAIN.put("delhivery.com", "Delhivery");
        BY_DOMAIN.put("bluedart.com", "Blue Dart");
        BY_DOMAIN.put("xpressbees.com", "Xpressbees");
        BY_DOMAIN.put("shadowfax.in", "Shadowfax");
        BY_DOMAIN.put("amazon.in", "Amazon");
        BY_DOMAIN.put("amazon.com", "Amazon");
    }

    public static Optional<String> detect(String trackingNumber, String senderDomain) {
        if (trackingNumber == null) return Optional.empty();
        String n = trackingNumber.trim().toUpperCase();

        for (var e : BY_PREFIX.entrySet()) {
            if (e.getValue().matcher(n).matches()) return Optional.of(e.getKey());
        }
        // Distinctive courier-prefixed formats handled above; now digit-length heuristics.
        if (n.matches("\\d+")) {
            String domain = senderDomain == null ? "" : senderDomain.toLowerCase();
            if (n.length() == 10) {
                return Optional.of(domain.startsWith("delhivery") ? "Delhivery" : "Blue Dart");
            }
            if (n.length() == 11) {
                return Optional.of("Delhivery");
            }
            if (n.length() >= 12 && n.length() <= 15) {
                if (domain.startsWith("delhivery")) return Optional.of("Delhivery");
                return Optional.of("Xpressbees");
            }
            if (n.length() >= 16 && n.length() <= 18) return Optional.of("Delhivery");
        }
        return Optional.empty();
    }

    public static Optional<String> courierFromDomain(String senderDomain) {
        if (senderDomain == null) return Optional.empty();
        return Optional.ofNullable(BY_DOMAIN.get(senderDomain.toLowerCase()));
    }
}
