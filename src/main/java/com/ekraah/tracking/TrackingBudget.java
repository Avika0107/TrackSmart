package com.ekraah.tracking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Guards the daily quota of the paid tracking provider (app.tracking.daily-budget).
 * The count comes from a DailyCounter: shared in MongoDB in the app, so several instances
 * together can't spend more than the budget; in memory for unit tests.
 */
public class TrackingBudget {

    /** Atomically adds one call to today's count and returns the new total. */
    @FunctionalInterface
    public interface DailyCounter {
        long incrementAndGet(LocalDate day);
    }

    private static final Logger log = LoggerFactory.getLogger(TrackingBudget.class);

    private final int dailyLimit;
    private final DailyCounter counter;

    public TrackingBudget(int dailyLimit) {
        this(dailyLimit, inMemory());
    }

    public TrackingBudget(int dailyLimit, DailyCounter counter) {
        this.dailyLimit = dailyLimit;
        this.counter = counter;
    }

    /** @return false when today's budget is exhausted (caller must fall back to mock). */
    public boolean tryAcquire() {
        long now = counter.incrementAndGet(LocalDate.now());
        if (now > dailyLimit) {
            if (now == dailyLimit + 1) {
                log.warn("Daily tracking API budget of {} exhausted; using mock provider until tomorrow", dailyLimit);
            }
            return false;
        }
        return true;
    }

    /** Single-instance counter that resets each day. */
    static DailyCounter inMemory() {
        AtomicLong used = new AtomicLong();
        AtomicReference<LocalDate> current = new AtomicReference<>(LocalDate.now());
        return day -> {
            LocalDate seen = current.get();
            if (!seen.equals(day) && current.compareAndSet(seen, day)) used.set(0);
            return used.incrementAndGet();
        };
    }
}
