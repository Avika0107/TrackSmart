package com.parcelpilot.security;

/** Abstraction over how OTPs reach the user. Plug a real SMS/WhatsApp provider in prod. */
public interface OtpSender {
    void send(String phone, String otp);
}
