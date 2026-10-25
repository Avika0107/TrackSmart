package com.ekraah.controller;

import com.ekraah.config.AppProperties;
import com.ekraah.dto.RawEmail;
import com.ekraah.exception.ApiException;
import com.ekraah.mail.GmailMessageProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * Inbound email webhook: an email relay (e.g. a Cloudflare Email Worker, see inbound/) receives
 * mail for ALIAS@app.inbound.domain and posts it here. Replaces the shared Gmail inbox — no
 * mailbox password exists, and every user has their own address.
 *
 * Authenticated by HMAC, not JWT: X-EkRaah-Signature = hex(HMAC-SHA256(secret, timestamp + "." + body))
 * with X-EkRaah-Timestamp in epoch seconds, rejected outside app.inbound.max-skew-seconds (replays).
 * The mail itself then goes through the normal pipeline, including the sender (DKIM) check.
 */
@RestController
@RequestMapping("/api/inbound")
public class InboundController {

    static final int MAX_BODY_CHARS = 2_000_000;

    private final AppProperties props;
    private final GmailMessageProcessor processor;
    private final ObjectMapper mapper;

    public InboundController(AppProperties props, GmailMessageProcessor processor, ObjectMapper mapper) {
        this.props = props;
        this.processor = processor;
        this.mapper = mapper;
    }

    @PostMapping("/email")
    @Operation(summary = "Inbound email from the signed email relay (HMAC, not JWT)")
    public Map<String, Object> receive(@RequestHeader(value = "X-EkRaah-Timestamp", required = false) String timestamp,
                                       @RequestHeader(value = "X-EkRaah-Signature", required = false) String signature,
                                       @RequestBody String body) throws Exception {
        AppProperties.Inbound cfg = props.getInbound();
        if (!cfg.enabled()) throw new ApiException(HttpStatus.NOT_FOUND, "Not found.");
        if (body.length() > MAX_BODY_CHARS) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Email too large.");
        verifySignature(cfg, timestamp, signature, body);

        JsonNode json = mapper.readTree(body);
        String text = text(json, "text");
        RawEmail email = new RawEmail(
                text(json, "messageId"), text(json, "from"), text(json, "to"),
                text(json, "to"), null, null, text(json, "subject"),
                text != null ? text : text(json, "html"),
                text(json, "authenticationResults"), RawEmail.Channel.WEBHOOK);
        return Map.of("outcome", processor.process(email));
    }

    private static void verifySignature(AppProperties.Inbound cfg, String timestamp, String signature, String body)
            throws Exception {
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Missing or invalid signature.");
        }
        if (Math.abs(Instant.now().getEpochSecond() - ts) > cfg.getMaxSkewSeconds()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Signature expired.");
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(cfg.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        byte[] given;
        try {
            given = HexFormat.of().parseHex(signature == null ? "" : signature.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Missing or invalid signature.");
        }
        if (!MessageDigest.isEqual(expected, given)) {   // constant-time comparison
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Missing or invalid signature.");
        }
    }

    private static String text(JsonNode json, String field) {
        JsonNode v = json.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
