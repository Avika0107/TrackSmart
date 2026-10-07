package com.parcelpilot.risk;

import com.parcelpilot.model.RiskLevel;
import com.parcelpilot.weather.WeatherSnapshot;
import org.springframework.stereotype.Component;

@Component
public class TemplateMessageBuilder implements MessageBuilder {

    @Override
    public String build(RiskAssessment a, WeatherSnapshot w) {
        String city = a.city() == null || a.city().isBlank() ? "your area" : a.city();
        if (a.level() == RiskLevel.HIGH) {
            if (w != null && w.wmoCode() >= 95) {
                return "Thunderstorms are expected near " + city + " over the next 24 hours, so your parcel may arrive about a day late.";
            }
            return "Heavy rain (" + fmt(w == null ? 0 : w.precipitationMm24h()) + " mm expected) around " + city
                    + " may delay your parcel by about a day.";
        }
        if (a.level() == RiskLevel.MEDIUM) {
            return "There's some weather disturbance near " + city
                    + " — your parcel could arrive a little late, but it's usually minor.";
        }
        return "Weather looks clear near " + city + ". Your parcel is on track.";
    }

    private static String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
