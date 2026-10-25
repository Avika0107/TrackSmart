package com.ekraah.service;

import com.ekraah.dto.OrderDtos.ImportOrderRequest;
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

import java.time.LocalDate;
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
 * merges into a pending confirmation, keeps Amazon ids away from tracking APIs, and follows
 * order-id-only pages (Flipkart) as PAGE_ONLY cards.
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
        when(orders.findFirstByUserIdAndRetailerOrderIdOrderByLastUpdatedDesc(eq(USER), anyString()))
                .thenReturn(Optional.empty());
        // No city in the result -> computeRisk skips weather, keeping this a pure unit test.
        when(tracking.track(anyString(), anyString()))
                .thenReturn(new TrackingResult(OrderStatus.IN_TRANSIT, null, null, null, List.of(), "mock"));
    }

    private static ImportOrderRequest req(Platform platform, String number, String hint, String orderId) {
        return new ImportOrderRequest(platform, number, hint, orderId, null, null, null);
    }

    private static ImportOrderRequest page(String orderId, OrderStatus status, LocalDate eta, LocalDate deliveredOn) {
        return new ImportOrderRequest(Platform.FLIPKART, null, null, orderId, status, eta, deliveredOn);
    }

    @Test
    void createsNewApiTrackedOrder() {
        var result = service.importFromExtension(USER, req(Platform.FLIPKART, " fmpc1234567890 ", "Ekart", "OD123456789012345"));

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

        var result = service.importFromExtension(USER, req(Platform.FLIPKART, "FMP4471203", null, null));

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

        var result = service.importFromExtension(USER, req(Platform.MYNTRA, "11234567891", "Delhivery", null));

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

        var result = service.importFromExtension(USER, req(Platform.MYNTRA, "11234567891", null, null));

        assertThat(result.outcome()).isEqualTo(ImportOutcome.CREATED);
        assertThat(result.order()).isNotSameAs(pending);
    }

    @Test
    void amazonIdsStayEmailOnlyAndNeverHitTrackingApis() {
        var result = service.importFromExtension(USER, req(Platform.AMAZON, "TBA309812456000", null, "402-5684713-9912543"));

        assertThat(result.order().getTrackingMode()).isEqualTo(TrackingMode.EMAIL_ONLY);
        assertThat(result.order().getStatus()).isEqualTo(OrderStatus.SHIPPED);
        verify(tracking, never()).track(anyString(), anyString());
    }

    @Test
    void pageCourierHintBreaksDigitLengthTies() {
        // 10 digits defaults to Blue Dart; the page saying Delhivery wins the tie.
        var result = service.importFromExtension(USER, req(Platform.NYKAA, "5551234567", "Delhivery", null));
        assertThat(result.order().getCourier()).isEqualTo("Delhivery");
    }

    @Test
    void hintCannotOverrideADistinctiveFormat() {
        var result = service.importFromExtension(USER, req(Platform.FLIPKART, "FMP4471203", "Blue Dart", null));
        assertThat(result.order().getCourier()).isEqualTo("Ekart");
    }

    @Test
    void unsupportedNumberIsRejected() {
        assertThatThrownBy(() -> service.importFromExtension(USER, req(Platform.AJIO, "HELLO123", null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("tracking number");
        verify(orders, never()).save(any());
    }

    // ---- order id only (Flipkart) ---------------------------------------------

    @Test
    void orderIdOnlyCreatesPageOnlyCardFromPageFacts() {
        LocalDate eta = LocalDate.now().plusDays(3);
        var result = service.importFromExtension(USER, page("od4321000000000000", OrderStatus.IN_TRANSIT, eta, null));

        assertThat(result.outcome()).isEqualTo(ImportOutcome.CREATED);
        Order o = result.order();
        assertThat(o.getTrackingMode()).isEqualTo(TrackingMode.PAGE_ONLY);
        assertThat(o.getRetailerOrderId()).isEqualTo("OD4321000000000000");
        assertThat(o.getTrackingNumber()).isEqualTo("OD4321000000000000");
        assertThat(o.getStatus()).isEqualTo(OrderStatus.IN_TRANSIT);
        assertThat(o.getEstimatedDelivery()).isNotNull();
        assertThat(o.getSource()).isEqualTo(OrderSource.EXTENSION);
        verify(tracking, never()).track(anyString(), anyString());   // no AWB -> never hits tracking APIs
    }

    @Test
    void resendingSameOrderIdUpdatesStatusInsteadOfDuplicating() {
        Order card = new Order();
        card.setPlatform(Platform.FLIPKART);
        card.setTrackingMode(TrackingMode.PAGE_ONLY);
        card.setRetailerOrderId("OD4321000000000000");
        card.setStatus(OrderStatus.IN_TRANSIT);
        card.setEvents(List.of());   // immutable, like a timeline cleared by the weekly cleanup
        when(orders.findFirstByUserIdAndRetailerOrderIdOrderByLastUpdatedDesc(USER, "OD4321000000000000"))
                .thenReturn(Optional.of(card));

        LocalDate deliveredOn = LocalDate.now().minusDays(1);
        var result = service.importFromExtension(USER, page("OD4321000000000000", OrderStatus.DELIVERED, null, deliveredOn));

        assertThat(result.outcome()).isEqualTo(ImportOutcome.UPDATED);
        assertThat(result.order()).isSameAs(card);
        assertThat(card.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(card.getDeliveredAt()).isNotNull();
        assertThat(card.getEvents()).hasSize(1);
    }

    @Test
    void orderIdOfAnAlreadyTrackedOrderIsNotOverwrittenByThePage() {
        Order live = new Order();
        live.setTrackingMode(TrackingMode.API);
        live.setStatus(OrderStatus.OUT_FOR_DELIVERY);
        when(orders.findFirstByUserIdAndRetailerOrderIdOrderByLastUpdatedDesc(USER, "OD1"))
                .thenReturn(Optional.of(live));

        var result = service.importFromExtension(USER, page("OD1", OrderStatus.IN_TRANSIT, null, null));

        assertThat(result.outcome()).isEqualTo(ImportOutcome.ALREADY_TRACKED);
        assertThat(live.getStatus()).isEqualTo(OrderStatus.OUT_FOR_DELIVERY);
        verify(orders, never()).save(any());
    }

    @Test
    void trackingNumberLaterUpgradesThePageOnlyCard() {
        Order card = new Order();
        card.setPlatform(Platform.MYNTRA);
        card.setTrackingMode(TrackingMode.PAGE_ONLY);
        card.setRetailerOrderId("MY123456789");
        card.setTrackingNumber("MY123456789");
        when(orders.findFirstByUserIdAndRetailerOrderIdOrderByLastUpdatedDesc(USER, "MY123456789"))
                .thenReturn(Optional.of(card));

        var result = service.importFromExtension(USER, req(Platform.MYNTRA, "11234567891", null, "MY123456789"));

        assertThat(result.outcome()).isEqualTo(ImportOutcome.UPDATED);
        assertThat(card.getTrackingMode()).isEqualTo(TrackingMode.API);
        assertThat(card.getTrackingNumber()).isEqualTo("11234567891");
    }

    @Test
    void pendingPlaceholderTiedToAnotherOrderIdIsNotAdopted() {
        Order pending = new Order();
        pending.setPlatform(Platform.FLIPKART);
        pending.setTrackingMode(TrackingMode.EMAIL_PENDING);
        pending.setRetailerOrderId("OD9999");
        when(orders.findFirstByUserIdAndTrackingModeOrderByLastUpdatedDesc(USER, TrackingMode.EMAIL_PENDING))
                .thenReturn(Optional.of(pending));

        var result = service.importFromExtension(USER, page("OD1111", OrderStatus.ORDERED, null, null));

        assertThat(result.outcome()).isEqualTo(ImportOutcome.CREATED);
        assertThat(result.order()).isNotSameAs(pending);
    }

    @Test
    void neitherTrackingNumberNorOrderIdIsRejected() {
        assertThatThrownBy(() -> service.importFromExtension(USER, page(" ", null, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("order id");
    }

    // ---- delivered orders: order id only ------------------------------------

    @Test
    void deliveredOrderUsesOrderIdEvenWhenPageShowsTrackingNumber() {
        var delivered = new ImportOrderRequest(Platform.MYNTRA, "11234567891", "Delhivery", "MY777",
                OrderStatus.DELIVERED, null, LocalDate.now().minusDays(2));

        var result = service.importFromExtension(USER, delivered);

        assertThat(result.outcome()).isEqualTo(ImportOutcome.CREATED);
        assertThat(result.order().getTrackingMode()).isEqualTo(TrackingMode.PAGE_ONLY);
        assertThat(result.order().getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(result.order().getDeliveredAt()).isNotNull();
        verify(tracking, never()).track(anyString(), anyString());   // no quota spent on a finished order
    }

    // ---- attachTracking: user types the courier number into a page-only card ---

    private Order ownedPageOnlyCard(String id, OrderStatus status) {
        Order card = new Order();
        card.setId(id);
        card.setUserId(USER);
        card.setPlatform(Platform.FLIPKART);
        card.setTrackingMode(TrackingMode.PAGE_ONLY);
        card.setTrackingNumber("OD4321000000000000");
        card.setRetailerOrderId("OD4321000000000000");
        card.setStatus(status);
        when(orders.findByIdAndUserId(id, USER)).thenReturn(Optional.of(card));
        return card;
    }

    @Test
    void attachTrackingSwitchesPageOnlyCardToLiveTracking() {
        Order card = ownedPageOnlyCard("c1", OrderStatus.ORDERED);
        when(tracking.trackForce(anyString(), anyString()))
                .thenReturn(new TrackingResult(OrderStatus.IN_TRANSIT, null, null, null, List.of(), "mock"));

        Order saved = service.attachTracking(USER, "c1", " fmpp9876543210 ");

        assertThat(saved.getTrackingMode()).isEqualTo(TrackingMode.API);
        assertThat(saved.getTrackingNumber()).isEqualTo("FMPP9876543210");
        assertThat(saved.getCourier()).isEqualTo("Ekart");
        assertThat(saved.getRetailerOrderId()).isEqualTo("OD4321000000000000");   // store id kept
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.IN_TRANSIT);
    }

    @Test
    void attachTrackingRefusesDeliveredOrders() {
        ownedPageOnlyCard("c2", OrderStatus.DELIVERED);
        assertThatThrownBy(() -> service.attachTracking(USER, "c2", "FMPP9876543210"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("nothing left to track");
        verify(orders, never()).save(any());
    }

    @Test
    void attachTrackingRefusesCardsThatAlreadyHaveOne() {
        Order card = ownedPageOnlyCard("c3", OrderStatus.IN_TRANSIT);
        card.setTrackingMode(TrackingMode.API);
        assertThatThrownBy(() -> service.attachTracking(USER, "c3", "FMPP9876543210"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already has a tracking number");
    }

    @Test
    void attachTrackingRefusesNumberUsedOnAnotherCard() {
        ownedPageOnlyCard("c4", OrderStatus.SHIPPED);
        Order other = new Order();
        other.setId("other");
        when(orders.findFirstByUserIdAndTrackingNumberOrderByLastUpdatedDesc(USER, "FMPP9876543210"))
                .thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.attachTracking(USER, "c4", "FMPP9876543210"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("another card");
    }

    // ---- returned orders --------------------------------------------------------

    @Test
    void returnedOrderIsImportedAsFinishedByOrderId() {
        var returned = new ImportOrderRequest(Platform.FLIPKART, "FMPP9876543210", "Ekart", "OD437000000000000001",
                OrderStatus.RETURNED, null, null);

        var result = service.importFromExtension(USER, returned);

        assertThat(result.order().getStatus()).isEqualTo(OrderStatus.RETURNED);
        assertThat(result.order().getTrackingMode()).isEqualTo(TrackingMode.PAGE_ONLY);
        assertThat(result.order().getRisk()).isNull();
        verify(tracking, never()).track(anyString(), anyString());
    }

    @Test
    void attachTrackingRefusesReturnedOrders() {
        ownedPageOnlyCard("c5", OrderStatus.RETURNED);
        assertThatThrownBy(() -> service.attachTracking(USER, "c5", "FMPP9876543210"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already returned");
    }
}
