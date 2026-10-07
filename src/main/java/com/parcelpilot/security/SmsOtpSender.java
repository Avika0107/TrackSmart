package com.parcelpilot.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parcelpilot.config.AppProperties;
import com.parcelpilot.util.Mask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends OTPs via Fast2SMS (https://www.fast2sms.com/dev/bulkV2).
 *
 * Two routes, selected by config:
 *  - DLT route (recommended, ₹0.25/SMS at the ₹100+ recharge slab): set
 *    FAST2SMS_SENDER_ID and FAST2SMS_TEMPLATE_ID from your approved DLT header
 *    and OTP template — the code goes into the template's {#var#}.
 *  - Quick SMS route (no DLT needed, but ₹5 per SMS unit — avoid for real traffic):
 *    leave sender/template empty and a short free-text message is sent instead.
 *
 * The API key comes from FAST2SMS_API_KEY. The OTP itself is never logged.
 */
@Component
@ConditionalOnProperty(name = "app.otp.sender", havingValue = "sms")
public class SmsOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(SmsOtpSender.class);
    private static final String API_URL = "https://www.fast2sms.com/dev/bulkV2";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;
    private final String senderId;
    private final String templateId;

    public SmsOtpSender(AppProperties props) {
        this.apiKey = orBlank(props.getOtp().getFast2smsKey());
        this.senderId = orBlank(props.getOtp().getFast2smsSenderId());
        this.templateId = orBlank(props.getOtp().getFast2smsTemplateId());
    }

    @Override
    public void send(String phone, String otp) {
        if (apiKey.isBlank()) {
            log.warn("FAST2SMS_API_KEY is not set; OTP for {} was NOT delivered", Mask.phone(phone));
            return;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            if (senderId.isBlank() || templateId.isBlank()) {
                log.warn("No DLT sender/template configured — using Quick SMS route (₹5 per SMS unit)");
                body.put("route", "q");
                body.put("message", "Your ParcelPilot verification code is " + otp);
            } else {
                body.put("route", "dlt");
                body.put("sender_id", senderId);
                body.put("message", templateId);
            }
            body.put("variables_values", otp);
            body.put("numbers", phone);
            body.put("flash", "0");

            HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("authorization", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode res = mapper.readTree(response.body());
            if (response.statusCode() / 100 != 2 || !res.path("return").asBoolean(false)) {
                log.error("Fast2SMS rejected OTP send to {}: HTTP {} {}",
                        Mask.phone(phone), response.statusCode(), res.path("message").asText("unknown error"));
            } else {
                log.info("OTP sent to {} (request_id {})", Mask.phone(phone), res.path("request_id").asText("-"));
            }
        } catch (Exception e) {
            log.error("Fast2SMS send failed for {}: {}", Mask.phone(phone), e.getMessage());
        }
    }

    private static String orBlank(String s) { return s == null ? "" : s.trim(); }
}
