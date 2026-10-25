package com.ekraah.security;

import com.ekraah.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force guard for password login. Failed attempts are counted per phone number (stops
 * guessing one account's password) and per client IP (stops one client trying many numbers).
 * Over the limit, login is refused with 429 until the window passes — even with the right
 * password, so an attacker can't use the lock as an oracle.
 *
 * In memory, like OtpService: a single-instance assumption (use Redis for a cluster).
 */
@Component
public class LoginThrottle {

    static final int MAX_FAILS_PER_PHONE = 5;
    static final int MAX_FAILS_PER_IP = 20;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private record Window(Instant start, int fails) {}

    private final Map<String, Window> byPhone = new ConcurrentHashMap<>();
    private final Map<String, Window> byIp = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginThrottle() {
        this(Clock.systemUTC());
    }

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /** Throws 429 when this phone or IP has used up its failed attempts for the current window. */
    public void checkAllowed(String phone, String ip) {
        Instant now = clock.instant();
        Duration wait = maxWait(remaining(byPhone, phone, MAX_FAILS_PER_PHONE, now),
                remaining(byIp, ip, MAX_FAILS_PER_IP, now));
        if (wait != null) {
            long minutes = Math.max(1, (wait.toSeconds() + 59) / 60);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many failed attempts. Try again in " + minutes + " minute" + (minutes == 1 ? "" : "s")
                            + ", or use Forgot password.");
        }
    }

    public void recordFailure(String phone, String ip) {
        Instant now = clock.instant();
        bump(byPhone, phone, now);
        bump(byIp, ip, now);
    }

    /** A successful login clears the phone's counter (not the IP's: one success shouldn't unlock a sprayer). */
    public void recordSuccess(String phone) {
        if (phone != null) byPhone.remove(phone);
    }

    private static void bump(Map<String, Window> map, String key, Instant now) {
        if (key == null) return;
        map.compute(key, (k, w) -> w == null || expired(w, now) ? new Window(now, 1) : new Window(w.start(), w.fails() + 1));
    }

    /** Time left on the lock, or null when this key may still try. */
    private static Duration remaining(Map<String, Window> map, String key, int max, Instant now) {
        if (key == null) return null;
        Window w = map.get(key);
        if (w == null) return null;
        if (expired(w, now)) {
            map.remove(key, w);
            return null;
        }
        return w.fails() >= max ? Duration.between(now, w.start().plus(WINDOW)) : null;
    }

    private static boolean expired(Window w, Instant now) {
        return !now.isBefore(w.start().plus(WINDOW));
    }

    private static Duration maxWait(Duration a, Duration b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) >= 0 ? a : b;
    }
}
