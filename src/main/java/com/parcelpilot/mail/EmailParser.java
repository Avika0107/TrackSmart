package com.parcelpilot.mail;

import com.parcelpilot.model.OrderStatus;
import com.parcelpilot.model.Platform;
import com.parcelpilot.util.CourierDetector;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, unit-tested extractor: sender + subject + body text -> tracking facts.
 * The body is passed in, mined, and discarded — never stored or logged.
 *
 * Per-courier AWB assumptions (documented in README):
 *  - Amazon: TBA########## or 171-#######-####### (EMAIL_ONLY, never sent to APIs)
 *  - Ekart (Flipkart): FMP########
 *  - Shadowfax: SF#########
 *  - India Post: RR123456789IN (13 chars ending IN)
 *  - Blue Dart: 10 digits; Delhivery: 10-18 digits; Xpressbees: 12-15 digits.
 *    The 10/12-15 overlaps are resolved using the sender domain hint.
 */
@Component
public class EmailParser {

    private static final Pattern AMAZON = Pattern.compile("\\b(TBA\\d{9,15}|\\d{3}-\\d{7}-\\d{7})\\b");
    private static final Pattern EKART = Pattern.compile("\\b(FMP\\d{6,12})\\b");
    private static final Pattern SHADOWFAX = Pattern.compile("\\b(SF\\d{9,15})\\b");
    private static final Pattern INDIA_POST = Pattern.compile("\\b([A-Z]{2}\\d{9}IN)\\b");
    private static final Pattern DIGITS = Pattern.compile("\\b(\\d{10,18})\\b");

    private static final DateTimeFormatter[] WITH_YEAR = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
    };
    private static final DateTimeFormatter[] NO_YEAR = {
            DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    };
    private static final Pattern ETA = Pattern.compile(
            "(?:arriv|deliver|expected|estimated)[^.\\n]{0,60}?"
            + "(\\d{4}-\\d{2}-\\d{2}"
            + "|[A-Za-z]{3,9}\\s+\\d{1,2}(?!\\d|-)(?:st|nd|rd|th)?(?:\\s*,?\\s*\\d{4})?"
            + "|\\d{1,2}(?!\\d|-)(?:st|nd|rd|th)?\\s+[A-Za-z]{3,9}(?:\\s*,?\\s*\\d{4})?"
            + "|[A-Za-z]{3,9},\\s*\\d{1,2}(?!\\d|-)(?:st|nd|rd|th)?\\s+[A-Za-z]{3,9}(?:\\s*,?\\s*\\d{4})?)",
            Pattern.CASE_INSENSITIVE);

    public Optional<ParsedEmail> parse(String senderEmail, String subject, String bodyText) {
        Platform platform = platformFrom(senderEmail);
        String domain = domainOf(senderEmail);
        String text = ((subject == null ? "" : subject + " \n ") + (bodyText == null ? "" : bodyText));

        String awb = extractAwb(text, domain).orElse(null);
        if (awb == null) return Optional.empty();   // no tracking facts -> caller ignores this email

        OrderStatus status = statusFrom(text);
        boolean delayed = text.toLowerCase().contains("delay");
        LocalDate eta = extractEta(text).orElse(null);
        String courier = CourierDetector.detect(awb, domain).orElse(null);
        return Optional.of(new ParsedEmail(platform, courier, awb, status, delayed, eta));
    }

    /** Order of checks: distinctive formats first, then digit-length heuristics with domain hint. */
    static Optional<String> extractAwb(String text, String domain) {
        String upper = text.toUpperCase(Locale.ENGLISH);
        for (Pattern p : new Pattern[]{AMAZON, EKART, SHADOWFAX, INDIA_POST}) {
            Matcher m = p.matcher(upper);
            if (m.find()) return Optional.of(m.group(1));
        }
        Matcher m = DIGITS.matcher(upper);
        while (m.find()) {
            String candidate = m.group(1);
            Optional<String> courier = CourierDetector.detect(candidate, domain);
            if (courier.isPresent()) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    static OrderStatus statusFrom(String text) {
        String v = text.toLowerCase();
        if (v.contains("out for delivery")) return OrderStatus.OUT_FOR_DELIVERY;
        if (v.contains("delivered")) return OrderStatus.DELIVERED;
        if (v.contains("shipped") || v.contains("dispatched") || v.contains("in transit") || v.contains("on the way")) {
            return OrderStatus.IN_TRANSIT;
        }
        if (v.contains("confirmed") || v.contains("order placed") || v.contains("thanks for your order")) {
            return OrderStatus.ORDERED;
        }
        if (v.contains("delay")) return OrderStatus.IN_TRANSIT; // delayed update keeps it in flight
        return null;
    }

    static Optional<LocalDate> extractEta(String text) {
        Matcher m = ETA.matcher(text);
        int safety = 0;
        while (m.find() && safety++ < 20) {
            LocalDate d = parseEtaCandidate(m.group(1));
            if (d != null) return Optional.of(d);
        }
        return Optional.empty();
    }

    private static LocalDate parseEtaCandidate(String rawIn) {
        String raw = rawIn.replaceAll("(?i)(st|nd|rd|th)\\b", "").trim();
        for (DateTimeFormatter f : WITH_YEAR) {
            try { return LocalDate.parse(raw, f); } catch (Exception ignored) { /* try next */ }
        }
        for (DateTimeFormatter f : NO_YEAR) {
            try {
                java.time.temporal.TemporalAccessor ta = f.parse(raw);
                LocalDate d = LocalDate.of(LocalDate.now().getYear(),
                        ta.get(java.time.temporal.ChronoField.MONTH_OF_YEAR),
                        ta.get(java.time.temporal.ChronoField.DAY_OF_MONTH));
                // A bare "7 Oct" parsed in January for a December-sent mail rolls forward, not backward.
                if (d.isBefore(LocalDate.now().minusDays(180))) d = d.plusYears(1);
                return d;
            } catch (Exception ignored) { /* try next */ }
        }
        return null;
    }

    static Platform platformFrom(String senderEmail) {
        String domain = domainOf(senderEmail);
        if (domain.contains("amazon")) return Platform.AMAZON;
        if (domain.contains("flipkart") || domain.contains("ekartlogistics")) return Platform.FLIPKART;
        if (domain.contains("myntra")) return Platform.MYNTRA;
        if (domain.contains("nykaa")) return Platform.NYKAA;
        if (domain.contains("ajio")) return Platform.AJIO;
        return Platform.OTHER;
    }

    static String domainOf(String senderEmail) {
        if (senderEmail == null) return "";
        int at = senderEmail.lastIndexOf('@');
        return at < 0 ? senderEmail.toLowerCase() : senderEmail.substring(at + 1).toLowerCase();
    }
}
