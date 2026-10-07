package com.parcelpilot.dto;

/**
 * Normalized email passed into the pipeline — from IMAP or the demo simulator.
 * The body is dropped immediately after parsing and never logged or stored.
 */
public record RawEmail(
        String messageId,
        String from,
        String to,
        String deliveredTo,
        String xOriginalTo,
        String xForwardedTo,
        String subject,
        String body) {
}
