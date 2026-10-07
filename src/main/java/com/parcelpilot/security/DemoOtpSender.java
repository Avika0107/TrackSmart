package com.parcelpilot.security;

import com.parcelpilot.util.Mask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Demo profile only: prints the OTP to the log and the UI shows a fixed demo code.
 * Replace with an SMS/WhatsApp implementation in production.
 */
@Component
@ConditionalOnProperty(name = "app.otp.sender", havingValue = "demo", matchIfMissing = true)
public class DemoOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(DemoOtpSender.class);

    @Override
    public void send(String phone, String otp) {
        log.info("DEMO OTP for {} : {}", Mask.phone(phone), otp);
    }
}
