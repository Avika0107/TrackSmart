package com.ekraah.service;

import com.ekraah.exception.ApiException;
import com.ekraah.model.Order;
import com.ekraah.model.OrderSource;
import com.ekraah.model.OrderStatus;
import com.ekraah.model.Platform;
import com.ekraah.model.TrackingMode;
import com.ekraah.repository.OrderRepository;
import com.ekraah.repository.UserRepository;
import com.ekraah.risk.DelayRiskService;
import com.ekraah.service.OrderService.ImportOutcome;
import com.ekraah.tracking.TrackingResult;
import com.ekraah.tracking.TrackingService;
import com.ekraah.weather.WeatherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Browser-extension import: the server re-validates the number, never duplicates a card,
 * merges into a pending confirmation, and keeps Amazon ids away from tracking APIs.
 */
class OrderServiceImportTest {

    private static final String USER = "user-1";

    private OrderService service;
    private OrderRepository orders;
    private TrackingService tracking;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        tracking = mock(TrackingService.class);
        service = new OrderService(orders, mock(UserRepository.class), tracking,
                mock(WeatherService.class), mock(DelayRiskService.class), mock(AuditService.class));

        when(orders.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orders.findFirstByUserIdAndTrackingNumberOrderByLastUpdatedDesc(eq(USER), anyString()))
                .thenReturn(Optional.empty());
        when(orders.findFirstByUserIdAndTrackingModeOrderByLastUpdatedDesc(USER, TrackingMode.EMAIL_PENDING))
                .thenReturn(Optional.empty());
        // No city in the result -> computeRisk skips weather, keeping this a pure unit test.
        when(tracking.track(anyString(), anyString()))
                .thenReturn(new TrackingResult(OrderStatus.IN_TRANSIT, null, null, null, List.of(), "mock"));
    }

    @Test
    void createsNewApiTrackedOrder() {
        var result = service.importFromExtension(USER, Platform.FLIPKART, " fmpc1234567890 ", "Ekart", "OD123456789012345");

        assertThat(result.outcome()).isEqualTo(ImportOutcome.CREATED);
        Order o = result.order();
        assertThat(o.getUserId()).isEqualTo(USER);
        assertThat(o.getTrackingNumber()).isEqualTo("FMPC1234567890");
        assertThat(o.getCourier()).isEqualTo("Ekart");
        assertThat(o.getSource()).isEqualTo(OrderSource.EXTENSION);
        assertThat(o.getTrackingMode()).isEqualTo(TrackingMode.API);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.IN_TRANSIT);   // from the tracking provider
        assertThat(o.getRetailerOrderId()).isEqualTo("OD123456789012345");
        verify(tracking).track("FMPC1234567890", "Ekart");
    }

    @Test
    void alreadyTrackedNumberIsNotDuplicated() {
        Order existing = new Order();
        existing.setTrackingNumber("FMP4471203");
        when(orders.findFirstByUserIdAndTrackingNumberOrderByLastUpdatedDesc(USER, "FMP4471203"))
                .thenReturn(Optional.of(existing));

        var result = service.importFromExtension(USER, Platform.FLIPKART, "FMP4471203", null, null);

        assertThat(result.outcome()).isEqualTo(ImportOutcome.ALREADY_TRACKED);
        assertThat(result.order()).isSameAs(existing);
        verify(orders, never()).save(any());
        verify(tracking, never()).track(anyString(), anyString());
    }

    @Test
    void mergesIntoPendingConfirmationOfSamePlatform() {
        Order pending = new Order();
        pending.setUserId(USER);
        pending.setPlatform(Platform.MYNTRA);
        pending.setTrackingNumber("PENDING-1");
        pending.setTrackingMode(TrackingMode.EMAIL_PENDING);
        pending.setStatus(OrderStatus.ORDERED);
        when(orders.findFirstByUserIdAndTrackingModeOrderByLastUpdatedDesc(USER, TrackingMode.EMAIL_PENDING))
                .thenReturn(Optional.of(pending));

        var result = service.importFromExtension(USER, Platform.MYNTRA, "11234567891", "Delhivery", null);

        assertThat(result.outcome()).isEqualTo(ImportOutcome.UPDATED);
        assertThat(result.order()).isSameAs(pending);
        assertThat(pending.getTrackingNumber()).isEqualTo("11234567891");
        assertThat(pending.getTrackingMode()).isEqualTo(TrackingMode.API);
    }

    @Test
    void pendingOrderOfAnotherPlatformIsLeftAlone() {
        Order pending = new Order();
        pending.setPlatform(Platform.NYKAA);
        pending.setTrackingMode(TrackingMode.EMAIL_PENDING);
        when(orders.findFirstByUserIdAndTrackingModeOrderByLastUpdatedDesc(USER, TrackingMode.EMAIL_PENDING))
                .thenReturn(Optional.of(pending));

        var result = service.importFromExtension(USER, Platform.MYNTRA, "11234567891", null, null);

        assertThat(result.outcome()).isEqualTo(ImportOutcome.CREATED);
        assertThat(result.order()).isNotSameAs(pending);
    }

    @Test
    void amazonIdsStayEmailOnlyAndNeverHitTrackingApis() {
        var result = service.importFromExtension(USER, Platform.AMAZON, "TBA309812456000", null, "402-5684713-9912543");

        assertThat(result.order().getTrackingMode()).isEqualTo(TrackingMode.EMAIL_ONLY);
        assertThat(result.order().getStatus()).isEqualTo(OrderStatus.SHIPPED);
        verify(tracking, never()).track(anyString(), anyString());
    }

    @Test
    void pageCourierHintBreaksDigitLengthTies() {
        // 10 digits defaults to Blue Dart; the page saying Delhivery wins the tie.
        var result = service.importFromExtension(USER, Platform.NYKAA, "5551234567", "Delhivery", null);
        assertThat(result.order().getCourier()).isEqualTo("Delhivery");
    }

    @Test
    void hintCannotOverrideADistinctiveFormat() {
        var result = service.importFromExtension(USER, Platform.FLIPKART, "FMP4471203", "Blue Dart", null);
        assertThat(result.order().getCourier()).isEqualTo("Ekart");
    }

    @Test
    void unsupportedNumberIsRejected() {
        assertThatThrownBy(() -> service.importFromExtension(USER, Platform.AJIO, "HELLO123", null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("tracking number");
        verify(orders, never()).save(any());
    }
}
