package com.ekraah.pipeline;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end pipeline test against a real Mongo (skipped automatically when
 * Docker isn't available). Exercises: OTP login -> simulate emails -> dedupe ->
 * allowlist enforcement -> audit log -> stormy risk flip.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
// Force the demo OTP sender so a developer's local .env (fast2sms key, sender=sms)
// cannot make this context send real SMS during signup. app.otp.fixed stays pinned
// by the demo profile, so registration with 123456 still works.
@TestPropertySource(properties = {
        "app.otp.sender=demo",
        // Inbound webhook on, with a test relay whose Authentication-Results are trusted.
        "app.inbound.secret=" + GmailPipelineTest.WEBHOOK_SECRET,
        "app.inbound.domain=in.test",
        "app.inbound.trusted-authserv-id=it-relay"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GmailPipelineTest {

    static final String WEBHOOK_SECRET = "it-webhook-secret";

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7");

    @Autowired
    TestRestTemplate rest;

    // JUnit creates a new instance per test method, so the session is shared statically:
    // the account is registered once (test 1) and later tests reuse its token and alias.
    private static String token;
    private static String inbox;   // demoinbox+<generated alias>@gmail.com

    private HttpHeaders auth() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private void login() {
        if (token != null) return;
        rest.postForEntity("/api/auth/request-otp",
                new HttpEntity<>(Map.of("phone", "9812345670"), json()), Map.class);
        Map<String, Object> res = rest.postForEntity("/api/auth/register",
                new HttpEntity<>(Map.of("phone", "9812345670", "otp", "123456", "password", "pipeline-test-pw"),
                        json()), Map.class).getBody();
        token = (String) res.get("token");
        assertThat(token).isNotBlank();

        // Signup generates the inbound alias; mail must be addressed to it to reach this user.
        Map<String, Object> me = rest.exchange("/api/me", HttpMethod.GET,
                new HttpEntity<>(auth()), Map.class).getBody();
        inbox = "demoinbox+" + me.get("inboundAlias") + "@gmail.com";
    }

    private HttpHeaders json() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> simulate(Map<String, Object> payload) {
        return rest.exchange("/api/dev/simulate-email", HttpMethod.POST,
                new HttpEntity<>(payload, auth()), Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> orders() {
        List<Map<String, Object>> list = rest.exchange("/api/orders", HttpMethod.GET,
                new HttpEntity<>(auth()), List.class).getBody();
        assertThat(list).isNotNull();
        return list;
    }

    @Test
    @Order(1)
    void simulatesEmailAndCreatesOrder() {
        login();
        Map<String, Object> res = simulate(Map.of(
                "messageId", "it-flipkart-001",
                "from", "shipment-tracking@flipkart.com",
                "to", inbox,
                "subject", "Order shipped",
                "body", "Your order shipped via Ekart. Tracking FMP7770001. Expected delivery by Wed, 7 Oct."));
        assertThat(res.get("outcome")).isEqualTo("CREATED");

        List<Map<String, Object>> list = orders().stream()
                .filter(o -> "FMP7770001".equals(o.get("trackingNumber"))).toList();
        assertThat(list).hasSize(1);
    }

    @Test
    @Order(2)
    void repollingSameMessageDoesNotDuplicate() {
        login();
        simulate(Map.of(
                "messageId", "it-flipkart-001",
                "from", "shipment-tracking@flipkart.com",
                "to", inbox,
                "subject", "Order shipped",
                "body", "Your order shipped via Ekart. Tracking FMP7770001."));
        long count = orders().stream()
                .filter(o -> "FMP7770001".equals(o.get("trackingNumber"))).count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    @Order(3)
    void statusEmailUpdatesSameOrderByAwb() {
        login();
        Map<String, Object> res = simulate(Map.of(
                "messageId", "it-flipkart-002",
                "from", "shipment-tracking@flipkart.com",
                "to", inbox,
                "subject", "Out for delivery",
                "body", "Your order is out for delivery. AWB FMP7770001."));
        assertThat(res.get("outcome")).isEqualTo("UPDATED");

        List<Map<String, Object>> list = orders().stream()
                .filter(o -> "FMP7770001".equals(o.get("trackingNumber"))).toList();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("status")).isEqualTo("OUT_FOR_DELIVERY");
    }

    @Test
    @Order(4)
    void nonAllowlistedSenderIsNeverStored() {
        login();
        Map<String, Object> res = simulate(Map.of(
                "messageId", "it-unknown-001",
                "from", "orders@randombazaar.in",
                "to", inbox,
                "subject", "shipped",
                "body", "Tracking 771234567890"));
        assertThat(res.get("outcome")).isEqualTo("IGNORED_SENDER");

        long count = orders().stream()
                .filter(o -> "771234567890".equals(o.get("trackingNumber"))).count();
        assertThat(count).isZero();

        // and the audit log explains why (PII-free, domain only)
        List<Map<String, Object>> audit = rest.exchange("/api/privacy/audit", HttpMethod.GET,
                new HttpEntity<>(auth()), List.class).getBody();
        assertThat(audit).isNotNull();
        assertThat(audit).anyMatch(a -> "IGNORED_NOT_ALLOWED_SENDER".equals(a.get("action"))
                && "randombazaar.in".equals(a.get("senderDomain")));
    }

    @Test
    @Order(5)
    void promoMailWithoutTrackingIsIgnored() {
        login();
        Map<String, Object> res = simulate(Map.of(
                "messageId", "it-promo-001",
                "from", "no-reply@flipkart.com",
                "to", inbox,
                "subject", "90% off sale",
                "body", "Big sale today!"));
        assertThat(res.get("outcome")).isEqualTo("IGNORED_NO_TRACKING");
    }

    @Test
    @Order(6)
    void stormyWeatherFlipsRiskToHigh() {
        login();
        rest.postForEntity("/api/dev/set-weather?mode=stormy", new HttpEntity<>(auth()), Map.class);

        // Manual order: the mock tracker populates a city, so risk is computable
        Map<String, Object> created = rest.exchange("/api/orders", HttpMethod.POST,
                new HttpEntity<>(Map.of("trackingNumber", "FMP9998887"), auth()), Map.class).getBody();
        assertThat(created.get("currentCity")).isNotNull();

        // Pin the status (mock tracker can randomly say DELIVERED, where risk is hidden by design)
        Map<String, Object> order = rest.exchange(
                "/api/dev/orders/" + created.get("id") + "/status?status=IN_TRANSIT", HttpMethod.POST,
                new HttpEntity<>(auth()), Map.class).getBody();

        Map<String, Object> risk = (Map<String, Object>) order.get("risk");
        assertThat(risk).isNotNull();
        assertThat(risk.get("level")).isEqualTo("HIGH"); // stormy demo weather
        assertThat((List<String>) risk.get("reasons")).isNotEmpty();
        assertThat((String) risk.get("message")).contains("Thunderstorm");
    }

    @Test
    @Order(7)
    @SuppressWarnings("unchecked")
    void replayingAStatusUpdateEmailIsADuplicate() {
        login();
        // it-flipkart-002 (test 3) updated an existing order rather than creating one; its
        // Message-ID must still be remembered, or a replay re-applies it and grows the timeline.
        int eventsBefore = ((List<Object>) orders().stream()
                .filter(o -> "FMP7770001".equals(o.get("trackingNumber"))).findFirst().orElseThrow().get("events")).size();
        Map<String, Object> res = simulate(Map.of(
                "messageId", "it-flipkart-002",
                "from", "shipment-tracking@flipkart.com",
                "to", inbox,
                "subject", "Out for delivery",
                "body", "Your order is out for delivery. AWB FMP7770001."));
        assertThat(res.get("outcome")).isEqualTo("DUPLICATE");
        int eventsAfter = ((List<Object>) orders().stream()
                .filter(o -> "FMP7770001".equals(o.get("trackingNumber"))).findFirst().orElseThrow().get("events")).size();
        assertThat(eventsAfter).isEqualTo(eventsBefore);
    }

    @Test
    @Order(8)
    @SuppressWarnings("unchecked")
    void authModeReportsDemoInDemoProfile() {
        // The login page shows "Demo OTP: 123456" and the dashboard mounts the Demo Control
        // Panel only when this is true; it used to be inverted and always reported false.
        Map<String, Object> mode = rest.getForObject("/api/auth/mode", Map.class);
        assertThat(mode.get("demoMode")).isEqualTo(true);
    }

    // ---- inbound webhook + sender verification (security fixes) ----------------

    @Autowired
    com.ekraah.mail.GmailMessageProcessor processor;

    private static String alias() {
        return inbox.substring(inbox.indexOf('+') + 1, inbox.indexOf('@'));
    }

    @SuppressWarnings("unchecked")
    private org.springframework.http.ResponseEntity<Map> postInbound(Map<String, Object> mail, long timestamp, String secret)
            throws Exception {
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(mail);
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = java.util.HexFormat.of().formatHex(
                mac.doFinal((timestamp + "." + body).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        HttpHeaders headers = json();
        headers.set("X-EkRaah-Timestamp", Long.toString(timestamp));
        headers.set("X-EkRaah-Signature", sig);
        return rest.exchange("/api/inbound/email", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private static Map<String, Object> inboundMail(String id, String to, String from, String authResults, String text) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("messageId", id); m.put("to", to); m.put("from", from); m.put("subject", "Shipped");
        m.put("text", text); m.put("authenticationResults", authResults);
        return m;
    }

    private static long now() { return java.time.Instant.now().getEpochSecond(); }

    @Test
    @Order(9)
    void signedWebhookWithVerifiedSenderCreatesOrder() throws Exception {
        login();
        var res = postInbound(inboundMail("wh-001", alias() + "@in.test", "shipment-tracking@flipkart.com",
                "it-relay; dkim=pass header.d=flipkart.com", "Shipped via Ekart. Tracking FMP7770009."), now(), WEBHOOK_SECRET);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().get("outcome")).isEqualTo("CREATED");
        assertThat(orders()).anyMatch(o -> "FMP7770009".equals(o.get("trackingNumber")));
    }

    @Test
    @Order(10)
    void webhookRejectsBadSignatureAndStaleTimestamp() throws Exception {
        login();
        Map<String, Object> mail = inboundMail("wh-002", alias() + "@in.test", "shipment-tracking@flipkart.com",
                "it-relay; dkim=pass header.d=flipkart.com", "Tracking FMP7770010.");
        assertThat(postInbound(mail, now(), "wrong-secret").getStatusCode().value()).isEqualTo(401);
        assertThat(postInbound(mail, now() - 3600, WEBHOOK_SECRET).getStatusCode().value()).isEqualTo(401);
        assertThat(orders()).noneMatch(o -> "FMP7770010".equals(o.get("trackingNumber")));
    }

    @Test
    @Order(11)
    @SuppressWarnings("unchecked")
    void forgedRetailerSenderIsIgnoredOverWebhook() throws Exception {
        login();
        // Signed by the attacker's own domain while claiming From: flipkart.com.
        var res = postInbound(inboundMail("wh-003", alias() + "@in.test", "shipment-tracking@flipkart.com",
                "it-relay; dkim=pass header.d=evil.example; dmarc=fail header.from=flipkart.com",
                "Tracking FMP7770011."), now(), WEBHOOK_SECRET);
        assertThat(res.getBody().get("outcome")).isEqualTo("UNVERIFIED_SENDER");
        assertThat(orders()).noneMatch(o -> "FMP7770011".equals(o.get("trackingNumber")));
        List<Map<String, Object>> audit = rest.exchange("/api/privacy/audit", HttpMethod.GET,
                new HttpEntity<>(auth()), List.class).getBody();
        assertThat(audit).anyMatch(a -> "IGNORED_UNVERIFIED_SENDER".equals(a.get("action")));
    }

    @Test
    @Order(12)
    void forgedSenderIsIgnoredOverImapToo() {
        login();
        // As polled from Gmail: the topmost Authentication-Results is Gmail's own verdict.
        var forged = new com.ekraah.dto.RawEmail("imap-forged-1", "shipment-tracking@flipkart.com", inbox, inbox,
                null, null, "Shipped", "Tracking FMP7770012.",
                "mx.google.com; dkim=none; spf=fail smtp.mailfrom=flipkart.com; dmarc=fail header.from=flipkart.com",
                com.ekraah.dto.RawEmail.Channel.IMAP);
        assertThat(processor.process(forged)).isEqualTo("UNVERIFIED_SENDER");

        var genuine = new com.ekraah.dto.RawEmail("imap-genuine-1", "shipment-tracking@flipkart.com", inbox, inbox,
                null, null, "Shipped", "Shipped via Ekart. Tracking FMP7770013.",
                "mx.google.com; dkim=pass header.i=@flipkart.com header.s=s1; dmarc=pass header.from=flipkart.com",
                com.ekraah.dto.RawEmail.Channel.IMAP);
        assertThat(processor.process(genuine)).isEqualTo("CREATED");
    }

    @Test
    @Order(13)
    @SuppressWarnings("unchecked")
    void regeneratedAddressReplacesTheOldOne() throws Exception {
        login();
        String oldAlias = alias();
        Map<String, Object> me = rest.exchange("/api/me/alias", HttpMethod.POST, new HttpEntity<>(auth()), Map.class).getBody();
        String newAlias = (String) me.get("inboundAlias");
        assertThat(newAlias).isNotEqualTo(oldAlias).matches("[a-z2-9]{12}");

        Map<String, Object> info = rest.exchange("/api/setup/info", HttpMethod.GET, new HttpEntity<>(auth()), Map.class).getBody();
        assertThat(info.get("forwardingAddress")).isEqualTo(newAlias + "@in.test");   // per-user domain, no shared inbox

        var viaOld = postInbound(inboundMail("wh-004", oldAlias + "@in.test", "shipment-tracking@flipkart.com",
                "it-relay; dkim=pass header.d=flipkart.com", "Tracking FMP7770014."), now(), WEBHOOK_SECRET);
        assertThat(viaOld.getBody().get("outcome")).isEqualTo("UNKNOWN_ALIAS");
        var viaNew = postInbound(inboundMail("wh-005", newAlias + "@in.test", "shipment-tracking@flipkart.com",
                "it-relay; dkim=pass header.d=flipkart.com", "Shipped via Ekart. Tracking FMP7770015."), now(), WEBHOOK_SECRET);
        assertThat(viaNew.getBody().get("outcome")).isEqualTo("CREATED");
        inbox = "demoinbox+" + newAlias + "@gmail.com";
    }

    @Test
    @Order(14)
    @SuppressWarnings("unchecked")
    void repeatedWrongPasswordsLockTheAccount() {
        for (int i = 0; i < 5; i++) {
            var r = rest.postForEntity("/api/auth/login",
                    new HttpEntity<>(Map.of("phone", "9812345670", "password", "wrong-" + i), json()), Map.class);
            assertThat(r.getStatusCode().value()).isEqualTo(401);
        }
        var locked = rest.postForEntity("/api/auth/login",
                new HttpEntity<>(Map.of("phone", "9812345670", "password", "pipeline-test-pw"), json()), Map.class);
        assertThat(locked.getStatusCode().value()).isEqualTo(429);
    }

    @Test
    @Order(15)
    @SuppressWarnings("unchecked")
    void highRiskOrderProducedAnAlertThatCanBeMarkedRead() {
        // Test 6 pushed FMP9998887 to HIGH risk under stormy weather; AlertService ran with it.
        // Log in fresh: test 14 locked the password, so reuse the session token.
        Map<String, Object> res = rest.exchange("/api/alerts", HttpMethod.GET, new HttpEntity<>(auth()), Map.class).getBody();
        List<Map<String, Object>> list = (List<Map<String, Object>>) res.get("alerts");
        assertThat(list).anyMatch(a -> "HIGH_RISK".equals(a.get("kind")) && "****8887".equals(a.get("tracking"))
                && ((String) a.get("message")).contains("Possible delay"));
        assertThat(((Number) res.get("unread")).intValue()).isPositive();

        rest.exchange("/api/alerts/read", HttpMethod.POST, new HttpEntity<>(auth()), Void.class);
        Map<String, Object> after = rest.exchange("/api/alerts", HttpMethod.GET, new HttpEntity<>(auth()), Map.class).getBody();
        assertThat(((Number) after.get("unread")).intValue()).isZero();
    }
}
