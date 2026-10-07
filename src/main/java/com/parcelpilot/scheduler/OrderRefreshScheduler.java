package com.parcelpilot.scheduler;

import com.parcelpilot.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Live refresh every 4h (configurable) + weekly cleanup of delivered timelines. */
@Component
public class OrderRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderRefreshScheduler.class);

    private final OrderService orders;
    private final com.parcelpilot.config.AppProperties props;

    public OrderRefreshScheduler(OrderService orders, com.parcelpilot.config.AppProperties props) {
        this.orders = orders;
        this.props = props;
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
    }
}
