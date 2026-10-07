package com.parcelpilot.mail;

import com.parcelpilot.config.AppProperties;
import com.parcelpilot.dto.RawEmail;
import com.parcelpilot.model.AuditAction;
import com.parcelpilot.model.User;
import com.parcelpilot.repository.UserRepository;
import com.parcelpilot.service.AuditService;
import com.parcelpilot.service.OrderService;
import com.parcelpilot.service.SetupService;
import com.parcelpilot.util.Mask;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * One pipeline for real IMAP mail and simulated mail:
 * resolve alias -> allowlist -> parse facts -> upsert order -> audit.
 * Non-allowlisted mail is never parsed or stored. Bodies are dropped after parsing.
 */
@Service
public class GmailMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(GmailMessageProcessor.class);

    private final EmailParser parser;
    private final OrderService orderService;
    private final UserRepository users;
    private final AuditService audit;
    private final SetupService setup;
    private final AppProperties props;

    public GmailMessageProcessor(EmailParser parser, OrderService orderService, UserRepository users,
                                 AuditService audit, SetupService setup, AppProperties props) {
        this.parser = parser;
        this.orderService = orderService;
        this.users = users;
        this.audit = audit;
        this.setup = setup;
        this.props = props;
    }

    /** @return a short outcome tag, useful for the dev endpoint and tests. */
    public String process(RawEmail email) {
        String domain = domainOf(email.from());
        try {
            // 1. Gmail forwarding-confirmation emails complete the user's setup.
            if (MimeUtils.isGmailForwardingConfirmation(email.from())) {
                String code = MimeUtils.confirmationCode(email.subject(), email.body());
                if (code != null) {
                    setup.saveForwardingCode(code);
                    audit.record(null, "google.com", AuditAction.PROCESSED,
                            "Gmail forwarding confirmation code detected");
                    return "FORWARDING_CODE_SAVED";
                }
                audit.record(null, "google.com", AuditAction.PROCESSED,
                        "Gmail forwarding email seen (no code found)");
                return "FORWARDING_EMAIL_NO_CODE";
            }

            // 2. Alias resolution: demoinbox+alias@gmail.com -> user. Unknown alias -> ignore silently.
            Optional<String> alias = AliasResolver.resolve(email);
            User user = alias.flatMap(a -> users.findByInboundAlias(a)).orElse(null);
            if (user == null) {
                audit.record(null, domain, AuditAction.IGNORED_NOT_ALLOWED_SENDER,
                        "No ParcelPilot user for this inbox alias");
                return "UNKNOWN_ALIAS";
            }

            // 3. Sender allowlist. Anything else is ignored and never parsed or stored.
            if (!allowed(domain)) {
                audit.record(user.getId(), domain, AuditAction.IGNORED_NOT_ALLOWED_SENDER,
                        "Sender not on allowlist");
                return "IGNORED_SENDER";
            }

            // 4. Parse: HTML -> text (jsoup), extract facts, discard the body immediately.
            String text = looksLikeHtml(email.body()) ? Jsoup.parse(email.body()).text() : email.body();
            Optional<ParsedEmail> parsed = parser.parse(email.from(), email.subject(), text);
            if (parsed.isEmpty()) {
                audit.record(user.getId(), domain, AuditAction.IGNORED_NO_TRACKING,
                        "No tracking number found in email");
                return "IGNORED_NO_TRACKING";
            }

            // 5. Idempotent upsert.
            var result = orderService.upsertFromEmail(user, parsed.get(), email);
            return result.outcome().name();
        } catch (Exception e) {
            log.error("Email processing failed for sender domain {}: {}", domain, e.getMessage());
            audit.record(null, domain, AuditAction.ERROR, "Processing error");
            return "ERROR";
        }
    }

    private boolean allowed(String domain) {
        return props.getAllowedSenders().stream().anyMatch(d -> domain.equals(d.toLowerCase(Locale.ROOT)));
    }

    private static boolean looksLikeHtml(String body) {
        if (body == null) return false;
        String lower = body.toLowerCase();
        return lower.contains("<html") || lower.contains("<div") || lower.contains("<p>")
                || lower.contains("<table") || lower.contains("<br");
    }

    private static String domainOf(String from) {
        if (from == null) return "unknown";
        int at = from.lastIndexOf('@');
        String d = at < 0 ? from : from.substring(at + 1);
        return d.toLowerCase(Locale.ROOT).replaceAll("[>\\s].*$", "");
    }
}
