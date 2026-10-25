package com.ekraah.dto;

/**
 * Normalized email passed into the pipeline — from IMAP, the inbound webhook or the demo simulator.
 * The body is dropped immediately after parsing and never logged or stored.
 *
 * @param authResults the receiving server's verdict (topmost Authentication-Results header), used
 *                    to verify the sender really is the retailer — the From header alone is forgeable
 * @param channel     where the email came from; decides whose verdict is trusted
 */
public record RawEmail(
        String messageId,
        String from,
        String to,
        String deliveredTo,
        String xOriginalTo,
        String xForwardedTo,
        String subject,
        String body,
        String authResults,
        Channel channel) {

    public enum Channel {
        /** Polled from the Gmail inbox: trust Gmail's own Authentication-Results. */
        IMAP,
        /** Posted by the HMAC-signed inbound webhook: trust the verdict the relay attached. */
        WEBHOOK,
        /** Demo panel / tests (demo profile only): no real sender to verify. */
        SIMULATED
    }

    /** Without sender verification data (channel null): such mail never passes the sender check. */
    public RawEmail(String messageId, String from, String to, String deliveredTo, String xOriginalTo,
                    String xForwardedTo, String subject, String body) {
        this(messageId, from, to, deliveredTo, xOriginalTo, xForwardedTo, subject, body, null, null);
    }
}
