package com.parcelpilot.dto;

import com.parcelpilot.controller.DevController;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security invariant: the server derives userId from the JWT, never from a
 * request body — so no request DTO may even have a userId field.
 */
class DtoGuardTest {

    @Test
    void requestDtos_haveNoUserIdField() {
        assertThat(componentsOf(OrderDtos.CreateOrderRequest.class)).doesNotContain("userId");
        assertThat(componentsOf(AuthDtos.RequestOtpRequest.class)).doesNotContain("userId");
        assertThat(componentsOf(AuthDtos.LoginRequest.class)).doesNotContain("userId");
        assertThat(componentsOf(AuthDtos.RegisterRequest.class)).doesNotContain("userId");
        assertThat(componentsOf(AuthDtos.ResetPasswordRequest.class)).doesNotContain("userId");
        assertThat(componentsOf(DevController.SimulateEmailRequest.class)).doesNotContain("userId");
    }

    private static java.util.List<String> componentsOf(Class<?> c) {
        if (!c.isRecord()) return java.util.List.of();
        return Arrays.stream(c.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();
    }
}
