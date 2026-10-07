package com.parcelpilot.security;

import com.parcelpilot.config.AppProperties;
import com.parcelpilot.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Phone -> OTP store, in memory (single instance assumption, fine for this project;
 * use Redis for a cluster). Rate limits: N requests per phone per 10 minutes,
 * M wrong attempts per issued code.
 */
@Service
public class OtpService {

    private static final Duration WINDOW = Duration.ofMinutes(10);

    private final OtpSender sender;
    private final PasswordEncoder encoder;
    private final String fixedDemoOtp;
    private final int maxPerWindow;
    private final int maxAttempts;
    private final Duration ttl;

    private record Entry(String hash, Instant expiresAt, int attempts, Instant windowStart, int requests) {}

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    public OtpService(OtpSender sender, PasswordEncoder encoder, AppProperties props) {
        this.sender = sender;
        this.encoder = encoder;
        this.fixedDemoOtp = props.getOtp().getFixed() == null ? "" : props.getOtp().getFixed().trim();
        this.maxPerWindow = props.getOtp().getMaxPerWindow();
        this.maxAttempts = props.getOtp().getMaxAttempts();
        this.ttl = Duration.ofMinutes(props.getOtp().getTtlMinutes());
    }

    public void requestOtp(String phone) {
        Entry existing = store.get(phone);
        if (existing != null && Duration.between(existing.windowStart(), Instant.now()).compareTo(WINDOW) < 0) {
            if (existing.requests() >= maxPerWindow) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many OTP requests. Try again in a few minutes.");
            }
        }
        String otp = fixedDemoOtp.isBlank()
                ? String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000))
                : fixedDemoOtp;
        sender.send(phone, otp);
        store.put(phone, new Entry(encoder.encode(otp), Instant.now().plus(ttl), 0,
                existing != null && Duration.between(existing.windowStart(), Instant.now()).compareTo(WINDOW) < 0
                        ? existing.windowStart() : Instant.now(),
                existing != null && Duration.between(existing.windowStart(), Instant.now()).compareTo(WINDOW) < 0
                        ? existing.requests() + 1 : 1));
    }

    public void verify(String phone, String otp) {
        Entry e = store.get(phone);
        if (e == null || e.expiresAt().isBefore(Instant.now())) {
            store.remove(phone);
            throw new ApiException(HttpStatus.BAD_REQUEST, "No active code for this number. Request a new OTP.");
        }
        if (e.attempts() >= maxAttempts) {
            store.remove(phone);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many wrong attempts. Request a new OTP.");
        }
        if (!encoder.matches(otp, e.hash())) {
            store.put(phone, new Entry(e.hash(), e.expiresAt(), e.attempts() + 1, e.windowStart(), e.requests()));
            throw new ApiException(HttpStatus.BAD_REQUEST, "Incorrect OTP. Please try again.");
        }
        store.remove(phone);
    }
}
