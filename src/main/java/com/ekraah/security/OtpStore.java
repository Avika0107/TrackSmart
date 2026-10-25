package com.ekraah.security;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where issued OTPs live between "send code" and "verify code". In memory they only work on a
 * single instance (a code requested on A can't be verified on B), so the app uses MongoOtpStore;
 * the in-memory one remains for unit tests.
 */
public interface OtpStore {

    /** hash: BCrypt of the code. window/requests: rate limiting of OTP requests per phone. */
    record Entry(String hash, Instant expiresAt, int attempts, Instant windowStart, int requests) {}

    Optional<Entry> get(String phone);

    void put(String phone, Entry entry);

    void remove(String phone);

    final class InMemory implements OtpStore {
        private final Map<String, Entry> map = new ConcurrentHashMap<>();
        @Override public Optional<Entry> get(String phone) { return Optional.ofNullable(map.get(phone)); }
        @Override public void put(String phone, Entry entry) { map.put(phone, entry); }
        @Override public void remove(String phone) { map.remove(phone); }
    }
}
