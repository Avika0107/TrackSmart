package com.parcelpilot.risk;

import com.parcelpilot.config.AppProperties;
import com.parcelpilot.model.OrderStatus;
import com.parcelpilot.model.RiskLevel;
import com.parcelpilot.weather.WeatherSnapshot;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Transparent rule-based delay risk (thresholds configurable in application.yml).
 * score 0-2 LOW, 3-4 MEDIUM, 5+ HIGH. OUT_FOR_DELIVERY halves the score (floor);
 * DELIVERED never shows risk. No ML, no accuracy claims.
 */
@Service
public class DelayRiskService {

    private final AppProperties.Risk rules;
    private final MessageBuilder messages;

    public DelayRiskService(AppProperties props, MessageBuilder messages) {
        this.rules = props.getRisk();
        this.messages = messages;
    }

    public RiskAssessment assess(String city, OrderStatus status, WeatherSnapshot w) {
        if (status == OrderStatus.DELIVERED) {
            RiskAssessment delivered = new RiskAssessment(RiskLevel.LOW, 0, List.of(), city, "Delivered — no risk.");
            return new RiskAssessment(delivered.level(), delivered.score(), delivered.reasons(), city,
                    messages.build(delivered, w));
        }

        int score = 0;
        List<String> reasons = new ArrayList<>();

        if (w.precipitationMm24h() > rules.getHeavyRainMm()) {
            score += rules.getHeavyRainPoints();
            reasons.add("Heavy rain: " + w.precipitationMm24h() + " mm expected in 24h (rule: > "
                    + (int) rules.getHeavyRainMm() + " mm, +" + rules.getHeavyRainPoints() + ")");
        }
        if (rules.getThunderstormCodes().contains(w.wmoCode())) {
            score += rules.getThunderstormPoints();
            reasons.add("Thunderstorm forecast (WMO code " + w.wmoCode() + ", +" + rules.getThunderstormPoints() + ")");
        }
        if (w.windKmph() > rules.getStrongWindKmph()) {
            score += rules.getStrongWindPoints();
            reasons.add("Strong wind: " + Math.round(w.windKmph()) + " km/h (rule: > "
                    + (int) rules.getStrongWindKmph() + " km/h, +" + rules.getStrongWindPoints() + ")");
        }
        if (w.visibilityKm() > 0 && w.visibilityKm() < rules.getFogVisibilityKm()) {
            score += rules.getFogPoints();
            reasons.add("Low visibility / fog: " + w.visibilityKm() + " km (rule: < "
                    + rules.getFogVisibilityKm() + " km, +" + rules.getFogPoints() + ")");
        }
        if (w.tempC() > rules.getExtremeHeatC()) {
            score += rules.getExtremeHeatPoints();
            reasons.add("Extreme heat: " + w.tempC() + "°C (rule: > " + (int) rules.getExtremeHeatC()
                    + "°C, +" + rules.getExtremeHeatPoints() + ")");
        }

        if (status == OrderStatus.OUT_FOR_DELIVERY && score > 0) {
            score = score / 2;
            reasons.add("Status OUT_FOR_DELIVERY: score halved (rule: local delivery usually beats bad weather)");
        }

        RiskLevel level = levelFor(score);
        RiskAssessment base = new RiskAssessment(level, score, List.copyOf(reasons), city, "");
        String message = messages.build(base, w);
        return new RiskAssessment(level, score, base.reasons(), city, message);
    }

    public static RiskLevel levelFor(int score) {
        if (score >= 5) return RiskLevel.HIGH;
        if (score >= 3) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }
}
