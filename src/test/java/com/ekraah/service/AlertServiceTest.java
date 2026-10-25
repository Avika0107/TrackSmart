package com.ekraah.service;

import com.ekraah.model.Alert;
import com.ekraah.model.Order;
import com.ekraah.model.OrderStatus;
import com.ekraah.model.Platform;
import com.ekraah.model.RiskInfo;
import com.ekraah.model.RiskLevel;
import com.ekraah.model.User;
import com.ekraah.repository.AlertRepository;
import com.ekraah.repository.OrderRepository;
import com.ekraah.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** When an alert fires, how often, and for whom. */
class AlertServiceTest {

    private AlertRepository alerts;
    private OrderRepository orders;
    private User user;
    private AlertService service;

    @BeforeEach
    void setUp() {
        alerts = mock(AlertRepository.class);
        UserRepository users = mock(UserRepository.class);
        user = User.of("9999999999", "alias", "Rahul");
        user.setId("u1");
        when(users.findById("u1")).thenReturn(Optional.of(user));
        when(alerts.existsByUserIdAndTrackingNumberAndKindAndCreatedAtAfter(anyString(), anyString(), any(), any()))
                .thenReturn(false);
        orders = mock(OrderRepository.class);
        service = new AlertService(alerts, users, orders);
    }

    private static Order order(OrderStatus status, RiskLevel risk) {
        Order o = new Order();
        o.setId("o1");
        o.setUserId("u1");
        o.setPlatform(Platform.FLIPKART);
        o.setTrackingNumber("FMP4471203");
        o.setStatus(status);
        if (risk != null) {
            RiskInfo r = new RiskInfo();
            r.setLevel(risk);
            r.setMessage("Thunderstorms are expected near Mumbai.");
            o.setRisk(r);
        }
        return o;
    }

    private Alert saved() {
        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);
        verify(alerts).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void highRiskRaisesOneAlertWithMaskedOrderAndReason() {
        service.evaluate(order(OrderStatus.IN_TRANSIT, RiskLevel.HIGH));
        Alert a = saved();
        assertThat(a.getKind()).isEqualTo(Alert.Kind.HIGH_RISK);
        assertThat(a.getMessage()).contains("Flipkart order ••••1203").contains("Thunderstorms").doesNotContain("FMP4471203");
        assertThat(a.getOrderId()).isEqualTo("o1");
    }

    @Test
    void mediumRiskRaisesNothing() {
        service.evaluate(order(OrderStatus.IN_TRANSIT, RiskLevel.MEDIUM));
        verify(alerts, never()).save(any());
    }

    @Test
    void outForDeliveryAndExceptionRaiseTheirOwnKinds() {
        service.evaluate(order(OrderStatus.OUT_FOR_DELIVERY, null));
        assertThat(saved().getKind()).isEqualTo(Alert.Kind.OUT_FOR_DELIVERY);

        AlertRepository fresh = alerts;
        org.mockito.Mockito.clearInvocations(fresh);
        service.evaluate(order(OrderStatus.EXCEPTION, null));
        assertThat(saved().getKind()).isEqualTo(Alert.Kind.EXCEPTION);
    }

    @Test
    void alreadyAlertedWithinQuietPeriodIsNotRepeated() {
        when(alerts.existsByUserIdAndTrackingNumberAndKindAndCreatedAtAfter(eq("u1"), eq("FMP4471203"),
                eq(Alert.Kind.HIGH_RISK), any(Instant.class))).thenReturn(true);
        service.evaluate(order(OrderStatus.IN_TRANSIT, RiskLevel.HIGH));
        verify(alerts, never()).save(any());
    }

    @Test
    void outForDeliveryIsOncePerOrderEver() {
        service.evaluate(order(OrderStatus.OUT_FOR_DELIVERY, null));
        verify(alerts).existsByUserIdAndTrackingNumberAndKindAndCreatedAtAfter(
                "u1", "FMP4471203", Alert.Kind.OUT_FOR_DELIVERY, Instant.EPOCH);
    }

    @Test
    void finishedOrdersAndOptedOutUsersGetNothing() {
        service.evaluate(order(OrderStatus.DELIVERED, RiskLevel.HIGH));
        verify(alerts, never()).save(any());

        user.getSettings().setNotificationsOn(false);
        service.evaluate(order(OrderStatus.OUT_FOR_DELIVERY, RiskLevel.HIGH));
        verify(alerts, never()).save(any());
    }

    @Test
    void repositoryFailureNeverBreaksTheOrderUpdate() {
        when(alerts.save(any())).thenThrow(new RuntimeException("mongo down"));
        service.evaluate(order(OrderStatus.IN_TRANSIT, RiskLevel.HIGH));   // must not throw
    }

    @Test
    void alertStoredBeforeTheOrderHadAnIdGetsItWhenListed() {
        Order unsaved = order(OrderStatus.OUT_FOR_DELIVERY, null);
        unsaved.setId(null);                                   // scored before its first save
        Alert stored = Alert.of(unsaved, Alert.Kind.OUT_FOR_DELIVERY, "msg");
        when(alerts.findTop30ByUserIdOrderByCreatedAtDesc("u1")).thenReturn(java.util.List.of(stored));
        Order saved = order(OrderStatus.OUT_FOR_DELIVERY, null);   // id o1
        when(orders.findFirstByUserIdAndTrackingNumberOrderByLastUpdatedDesc("u1", "FMP4471203"))
                .thenReturn(Optional.of(saved));

        assertThat(service.latest("u1")).singleElement().extracting(Alert::getOrderId).isEqualTo("o1");
        verify(alerts).save(stored);
    }

    @Test
    void overdueOrderRaisesOneOverdueAlert() {
        Order late = order(OrderStatus.IN_TRANSIT, null);
        late.setEstimatedDelivery(Instant.now().minus(java.time.Duration.ofDays(3)));
        service.evaluate(late);
        Alert a = saved();
        assertThat(a.getKind()).isEqualTo(Alert.Kind.OVERDUE);
        assertThat(a.getMessage()).contains("past its expected delivery date");
        verify(alerts).existsByUserIdAndTrackingNumberAndKindAndCreatedAtAfter(
                "u1", "FMP4471203", Alert.Kind.OVERDUE, Instant.EPOCH);   // once per order
    }
}
