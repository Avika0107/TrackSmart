package com.parcelpilot.mail;

import com.parcelpilot.dto.RawEmail;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;

import java.io.IOException;

/** Jakarta Mail -> RawEmail. Text-only extraction; the body is dropped after parsing. */
public final class MimeUtils {

    private MimeUtils() {}

    public static RawEmail toRawEmail(Message message) throws Exception {
        return new RawEmail(
                header(message, "Message-ID"),
                firstAddress(message.getFrom()),
                firstAddress(message.getRecipients(Message.RecipientType.TO)),
                header(message, "Delivered-To"),
                header(message, "X-Original-To"),
                header(message, "X-Forwarded-To"),
                message.getSubject(),
                bodyText(message));
    }

    private static String header(Message m, String name) {
        try {
            String[] v = m.getHeader(name);
            return v == null || v.length == 0 ? null : v[0];
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstAddress(jakarta.mail.Address[] addresses) {
        return addresses == null || addresses.length == 0 ? null : addresses[0].toString();
    }

    private static String bodyText(Part part) throws Exception {
        if (part.isMimeType("text/plain")) return (String) part.getContent();
        if (part.isMimeType("text/html")) return (String) part.getContent();
        if (part.isMimeType("multipart/*")) {
            String plain = null, html = null;
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart bp = mp.getBodyPart(i);
                if (bp.isMimeType("multipart/*") && html == null) {
                    html = bodyText(bp); // recurse into nested multipart
                } else if (bp.isMimeType("text/plain") && plain == null) {
                    plain = (String) bp.getContent();
                } else if (bp.isMimeType("text/html") && html == null) {
                    html = (String) bp.getContent();
                }
            }
            return plain != null ? plain : html;
        }
        return "";
    }

    /** Reads an IMAP message into a RawEmail; unreadable mail becomes an empty shell the caller ignores. */
    public static RawEmail safeToRawEmail(Message message) {
        try {
            return toRawEmail(message);
        } catch (Exception e) {
            return new RawEmail(null, null, null, null, null, null, null, "");
        }
    }

    public static boolean isGmailForwardingConfirmation(String from) {
        return from != null && from.toLowerCase().contains("forwarding-noreply@google.com");
    }

    public static String confirmationCode(String subject, String body) {
        String text = (subject == null ? "" : subject) + " " + (body == null ? "" : body);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(\\d{9,12})\\b").matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
