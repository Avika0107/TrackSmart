package com.ekraah.dto;

import com.ekraah.model.Platform;
import com.ekraah.weather.WeatherSnapshot;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class OrderDtos {

    private OrderDtos() {}

    /** NOTE: no userId field — the userId always comes from the JWT, never from the body. */
    public record CreateOrderRequest(@NotBlank @Size(max = 40) String trackingNumber, Platform platform) {}

    public record OrderDetailResponse(com.ekraah.model.Order order, WeatherSnapshot weather) {}

    public record SettingsRequest(String theme, Boolean notificationsOn, String defaultCity) {}
}
