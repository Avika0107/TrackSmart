package com.ekraah.scheduler;

import com.ekraah.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Live refresh every 4h (configurable) + weekly cleanup of delivered timelines. */
@Component
public class OrderRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderRefreshScheduler.class);

    private final OrderService orders;
    private final com.ekraah.config.AppProperties props;

    private final com.ekraah.service.AlertService alerts;
    private final com.ekraah.security.SessionService sessions;

    public OrderRefreshScheduler(OrderService orders, com.ekraah.config.AppProperties props,
                                 com.ekraah.service.AlertService alerts, com.ekraah.security.SessionService sessions) {
        this.orders = orders;
        this.props = props;
        this.alerts = alerts;
        this.sessions = sessions;
    }

    @Scheduled(fixedDelayString = "${app.refresh.interval-ms:14400000}", initialDelay = 45_000)
    public void refreshActiveOrders() {
        int updated = orders.refreshAllDue();
        if (updated > 0) log.info("Scheduled refresh updated {} order(s)", updated);
    }

    @Scheduled(cron = "${app.cleanup.cron:0 0 3 * * SUN}")
    public void cleanupDeliveredTimelines() {
        int cleared = orders.deleteOldTimelines(props.getCleanup().getKeepDeliveredDays());
        if (cleared > 0) log.info("Cleanup cleared timelines of {} old delivered order(s)", cleared);
        long oldAlerts = alerts.deleteOlderThan(java.time.Duration.ofDays(30));   // alerts are a feed, not an archive
        if (oldAlerts > 0) log.info("Cleanup removed {} alert(s) older than 30 days", oldAlerts);
        long expired = sessions.deleteExpired();
        if (expired > 0) log.info("Cleanup removed {} expired session(s)", expired);
    }
}
