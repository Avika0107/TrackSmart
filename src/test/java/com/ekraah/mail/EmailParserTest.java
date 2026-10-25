package com.ekraah.mail;

import com.ekraah.model.OrderStatus;
import com.ekraah.model.Platform;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Month;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EmailParserTest {

    private final EmailParser parser = new EmailParser();

    private ParsedEmail parse(String from, String subject, String body) {
        return parser.parse(from, subject, body).orElse(null);
    }

    @Test
    void parsesFlipkartEkartAwb() {
        ParsedEmail p = parse("shipment-tracking@flipkart.com", "Order shipped",
                "Your order has shipped via Ekart Logistics. Tracking number: FMP4471203. Expected delivery by Wed, 7 Oct.");
        assertThat(p).isNotNull();
        assertThat(p.platform()).isEqualTo(Platform.FLIPKART);
        assertThat(p.trackingNumber()).isEqualTo("FMP4471203");
        assertThat(p.courier()).isEqualTo("Ekart");
        assertThat(p.status()).isEqualTo(OrderStatus.IN_TRANSIT); // "shipped"
        assertThat(p.estimatedDelivery()).isEqualTo(LocalDate.of(LocalDate.now().getYear(), Month.OCTOBER, 7));
    }

    @Test
    void parsesDelhiveryElevenDigits() {
        ParsedEmail p = parse("noreply@myntra.com", "Your order is on the way",
                "Dispatched and in transit. Courier: Delhivery. Tracking ID: 11234567891. Estimated delivery: 8 Oct 2026.");
        assertThat(p).isNotNull();
        assertThat(p.platform()).isEqualTo(Platform.MYNTRA);
        assertThat(p.trackingNumber()).isEqualTo("11234567891");
        assertThat(p.courier()).isEqualTo("Delhivery");
        assertThat(p.estimatedDelivery()).isEqualTo(LocalDate.of(2026, Month.OCTOBER, 8));
    }

    @Test
    void parsesBlueDartTenDigits() {
        ParsedEmail p = parse("orders@nykaa.com", "Nykaa order shipped",
                "Blue Dart airway bill number: 5551234567. Delivery expected by Thu, 8 Oct.");
        assertThat(p).isNotNull();
        assertThat(p.platform()).isEqualTo(Platform.NYKAA);
        assertThat(p.courier()).isEqualTo("Blue Dart");
        assertThat(p.trackingNumber()).isEqualTo("5551234567");
    }

    @Test
    void parsesXpressbeesWithDomainHint() {
        ParsedEmail p = parse("no-reply@ajio.com", "Your AJIO order",
                "Xpressbees tracking number: 990011223344. Delivered on 28 Sep 2026.");
        assertThat(p).isNotNull();
        assertThat(p.platform()).isEqualTo(Platform.AJIO);
        assertThat(p.courier()).isEqualTo("Xpressbees");
        assertThat(p.status()).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    void parsesIndiaPost() {
        ParsedEmail p = parse("noreply@indiapost.gov.in", "Article booked",
                "Your article RM123456789IN has been booked. Status: shipped.");
        assertThat(p).isNotNull();
        assertThat(p.trackingNumber()).isEqualTo("RM123456789IN");
        assertThat(p.courier()).isEqualTo("India Post");
    }

    @Test
    void parsesShadowfax() {
        ParsedEmail p = parse("updates@shadowfax.in", "Update",
                "Out for delivery with Shadowfax, AWB SF1234567890123.");
        assertThat(p).isNotNull();
        assertThat(p.trackingNumber()).isEqualTo("SF1234567890123");
        assertThat(p.courier()).isEqualTo("Shadowfax");
        assertThat(p.status()).isEqualTo(OrderStatus.OUT_FOR_DELIVERY);
    }

    @Test
    void parsesAmazonOrderIdAsEmailOnlyNumber() {
        ParsedEmail p = parse("shipment-tracking@amazon.in", "Your order has shipped",
                "Your order #402-5684713-9912543 has shipped. Tracking ID: TBA309812456000. Arriving: Sat, 10 Oct.");
        assertThat(p).isNotNull();
        assertThat(p.platform()).isEqualTo(Platform.AMAZON);
        // either the TBA carrier number or the order id — both are EMAIL_ONLY and never sent to APIs
        assertThat(p.trackingNumber()).matches("(TBA\\d{9,15}|\\d{3}-\\d{7}-\\d{7})");
    }

    @Test
    void parsesOrderConfirmationWithoutTrackingAsPending() {
        ParsedEmail p = parse("no-reply@amazon.in", "Your order is confirmed",
                "Thanks for your order! We've received your order and will email tracking details when it ships. Arriving by 12 Oct 2026.");
        assertThat(p).isNotNull();
        assertThat(p.trackingNumber()).isNull();
        assertThat(p.hasTracking()).isFalse();
        assertThat(p.status()).isEqualTo(OrderStatus.ORDERED);
        assertThat(p.platform()).isEqualTo(Platform.AMAZON);
        assertThat(p.estimatedDelivery()).isNotNull();
    }

    @Test
    void shippingMailWithoutAwbIsNotMisclassifiedAsConfirmation() {
        ParsedEmail p = parse("shipment-tracking@flipkart.com", "Track your shipment",
                "Thanks for shopping with us. Your package is on the way and you can track it on the Ekart website.");
        assertThat(p).isNull(); // shipment wording without AWB: not a confirmation, not ignorable noise
    }

    @Test
    void parsesCancellationWithTrackingNumber() {
        ParsedEmail p = parse("shipment-tracking@flipkart.com", "Your order has been cancelled",
                "Your shipment FMP4471203 has been cancelled as requested. Refund will be credited in 5-7 days.");
        assertThat(p).isNotNull();
        assertThat(p.cancelled()).isTrue();
        assertThat(p.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(p.trackingNumber()).isEqualTo("FMP4471203");
    }

    @Test
    void parsesCancellationWithoutTrackingNumber() {
        ParsedEmail p = parse("no-reply@amazon.in", "Your order has been cancelled",
                "Your order has been cancelled as per your request. You have not been charged. We're sorry for the inconvenience.");
        assertThat(p).isNotNull();
        assertThat(p.cancelled()).isTrue();
        assertThat(p.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(p.trackingNumber()).isNull();
    }

    @Test
    void cancelYourOrderHelpTextIsNotACancellation() {
        ParsedEmail p = parse("shipment-tracking@flipkart.com", "Your order is on the way",
                "Your package FMP9988112 is in transit. You can cancel your order from the app until it ships.");
        assertThat(p).isNotNull();
        assertThat(p.cancelled()).isFalse();
        assertThat(p.status()).isNotEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void ignoresPromoMailWithoutTracking() {
        ParsedEmail p = parse("no-reply@flipkart.com", "Big Billion Days: up to 90% off",
                "The biggest sale of the year is here! Shop now!");
        assertThat(p).isNull();
    }

    @Test
    void flagsDelayedMail() {
        ParsedEmail p = parse("shipment-tracking@flipkart.com", "Delay update",
                "Your shipment FMP9988776 has been delayed. New estimated delivery: 12 Oct.");
        assertThat(p).isNotNull();
        assertThat(p.delayed()).isTrue();
    }

    @Test
    void detectsPlatformFromDomain() {
        assertThat(EmailParser.platformFrom("x@amazon.in")).isEqualTo(Platform.AMAZON);
        assertThat(EmailParser.platformFrom("x@ekartlogistics.com")).isEqualTo(Platform.FLIPKART);
        assertThat(EmailParser.platformFrom("x@unknownshop.in")).isEqualTo(Platform.OTHER);
    }

    @Test
    void parserReturnsFactsOnly_noBodyRetention() {
        ParsedEmail p = parse("orders@nykaa.com", "s", "AWB 5551234567 SECRET-ADDRESS-ITEM-PRICE");
        assertThat(p).isNotNull();
        // The record carries only tracking facts — no field can hold the body.
        assertThat(ParsedEmail.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("platform", "courier", "trackingNumber", "status", "delayed", "cancelled", "estimatedDelivery");
    }

    @Test
    void etaHandlesIsoDate() {
        Optional<LocalDate> eta = EmailParser.extractEta("expected arrival 2026-11-02 per carrier");
        assertThat(eta).contains(LocalDate.of(2026, Month.NOVEMBER, 2));
    }
}
