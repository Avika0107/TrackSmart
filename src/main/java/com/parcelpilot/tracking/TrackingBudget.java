package com.parcelpilot.tracking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

/** Guards the daily quota of the paid tracking provider (app.tracking.daily-budget). */
public class TrackingBudget {

    private static final Logger log = LoggerFactory.getLogger(TrackingBudget.class);

    private final int dailyLimit;
    private final AtomicInteger used = new AtomicInteger();
    private volatile LocalDate day = LocalDate.now();

    public TrackingBudget(int dailyLimit) {
        this.dailyLimit = dailyLimit;
    }

    /** @return false when today's budget is exhausted (caller must fall back to mock). */
    public boolean tryAcquire() {
        rollIfNeeded();
        int now = used.incrementAndGet();
        if (now > dailyLimit) {
            if (now == dailyLimit + 1) {
                log.warn("Daily tracking API budget of {} exhausted; using mock provider until tomorrow", dailyLimit);
            }
            return false;
        }
        return true;
    }

    private void rollIfNeeded() {
        LocalDate today = LocalDate.now();
        if (!day.equals(today)) {
            synchronized (this) {
                if (!day.equals(today)) {
                    day = today;
                    used.set(0);
                }
            }
        }
    }
}
