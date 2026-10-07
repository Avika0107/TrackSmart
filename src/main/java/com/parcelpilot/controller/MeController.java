package com.parcelpilot.controller;

import com.parcelpilot.dto.AuthDtos.UserView;
import com.parcelpilot.dto.OrderDtos.SettingsRequest;
import com.parcelpilot.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The userId always comes from the JWT principal — never from a request body. */
@RestController
@RequestMapping("/api")
public class MeController {

    private final UserService users;

    public MeController(UserService users) {
        this.users = users;
    }

    @GetMapping("/me")
    @Operation(summary = "Current user profile")
    public UserView me(@AuthenticationPrincipal String userId) {
        return UserView.of(users.get(userId));
    }

    @PatchMapping("/me/settings")
    @Operation(summary = "Update theme / notification settings")
    public UserView settings(@AuthenticationPrincipal String userId, @Valid @RequestBody SettingsRequest body) {
        return UserView.of(users.updateSettings(userId, body.theme(), body.notificationsOn()));
    }

    @DeleteMapping("/me")
    @Operation(summary = "Delete account and all data")
    public ResponseEntity<Void> deleteMe(@AuthenticationPrincipal String userId) {
        users.deleteAccount(userId);
        return ResponseEntity.noContent().build();
    }
}
