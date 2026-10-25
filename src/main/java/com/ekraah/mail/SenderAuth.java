package com.ekraah.mail;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether an email really comes from the domain in its From header, using the receiving
 * server's Authentication-Results verdict (RFC 8601). The allowlist alone checks only the From
 * header, which anyone can forge — "From: shipping@flipkart.com" from any server.
 *
 * Passes when the verdict was written by the trusted server (its authserv-id, e.g. mx.google.com)
 * and shows dkim=pass for a domain aligned with the From domain, or dmarc=pass for it. Only the
 * topmost header may be passed in: receivers prepend their own, and anything below it could have
 * been written by the sender. Gmail auto-forwarding keeps the retailer's DKIM signature intact,
 * so forwarded order mail still passes.
 */
public final class SenderAuth {

    private static final Pattern DKIM_PASS = Pattern.compile(
            "\\bdkim\\s*=\\s*pass\\b[^;]*?\\bheader\\.(?:d|i)\\s*=\\s*@?([A-Za-z0-9.-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DMARC_PASS = Pattern.compile(
            "\\bdmarc\\s*=\\s*pass\\b[^;]*?\\bheader\\.from\\s*=\\s*([A-Za-z0-9.-]+)", Pattern.CASE_INSENSITIVE);

    private SenderAuth() {}

    public static boolean verified(String authResults, String fromDomain, String trustedAuthservId) {
        if (authResults == null || fromDomain == null || fromDomain.isBlank()
                || trustedAuthservId == null || trustedAuthservId.isBlank()) {
            return false;
        }
        // The authserv-id is the first token, before the first ';'.
        String header = authResults.replaceAll("\\s+", " ").trim();
        int semi = header.indexOf(';');
        String authservId = (semi < 0 ? header : header.substring(0, semi)).trim().split(" ")[0];
        if (!authservId.equalsIgnoreCase(trustedAuthservId.trim())) return false;

        String body = semi < 0 ? "" : header.substring(semi + 1);
        String from = fromDomain.toLowerCase(Locale.ROOT);
        for (Pattern p : new Pattern[]{DKIM_PASS, DMARC_PASS}) {
            Matcher m = p.matcher(body);
            while (m.find()) {
                if (aligned(m.group(1).toLowerCase(Locale.ROOT), from)) return true;
            }
        }
        return false;
    }

    /** Relaxed alignment: same domain, or one is a subdomain of the other (mail.nykaa.com ~ nykaa.com). */
    static boolean aligned(String signingDomain, String fromDomain) {
        String d = signingDomain.replaceAll("\\.$", "");
        return d.equals(fromDomain) || fromDomain.endsWith("." + d) || d.endsWith("." + fromDomain);
    }
}
