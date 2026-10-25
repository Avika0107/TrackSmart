package com.ekraah.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** "Overdue" is calendar-based in India time: due yesterday or earlier, and still in flight. */
class OrderOverdueTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    private static Order due(LocalDate day, OrderStatus status) {
        Order o = new Order();
        o.setStatus(status);
        o.setEstimatedDelivery(day.atTime(LocalTime.NOON).atZone(IST).toInstant());   // how ETAs are stored
        return o;
    }

    @Test
    void dueYesterdayAndStillInTransitIsOverdue() {
        assertThat(due(TODAY.minusDays(1), OrderStatus.IN_TRANSIT).isOverdue(TODAY)).isTrue();
    }

    @Test
    void dueTodayIsNotOverdueYet() {
        assertThat(due(TODAY, OrderStatus.OUT_FOR_DELIVERY).isOverdue(TODAY)).isFalse();
    }

    @Test
    void finishedOrdersAreNeverOverdue() {
        for (OrderStatus done : OrderStatus.TERMINAL) {
            assertThat(due(TODAY.minusDays(5), done).isOverdue(TODAY)).as(done.name()).isFalse();
        }
    }

    @Test
    void noEtaMeansNotOverdue() {
        Order o = new Order();
        o.setStatus(OrderStatus.SHIPPED);
        assertThat(o.isOverdue(TODAY)).isFalse();
    }
}
