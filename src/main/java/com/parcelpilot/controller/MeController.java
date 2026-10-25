package com.parcelpilot.controller;

import com.parcelpilot.dto.AuthDtos.ProfileRequest;
import com.parcelpilot.dto.AuthDtos.UserView;
import com.parcelpilot.dto.OrderDtos.SettingsRequest;
import com.parcelpilot.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

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

    @PatchMapping("/me")
    @Operation(summary = "Update profile details (display name, 2-40 characters)")
    public UserView updateProfile(@AuthenticationPrincipal String userId,
                                  @Valid @RequestBody ProfileRequest body) {
        return UserView.of(users.updateDisplayName(userId, body.displayName()));
    }

    @PatchMapping("/me/settings")
    @Operation(summary = "Update theme / notification settings / default city")
    public UserView settings(@AuthenticationPrincipal String userId, @Valid @RequestBody SettingsRequest body) {
        return UserView.of(users.updateSettings(userId, body.theme(), body.notificationsOn(), body.defaultCity()));
    }

    @GetMapping(value = "/me/export")
    @Operation(summary = "Download my data as JSON (profile + orders, internal fields excluded)")
    public ResponseEntity<Map<String, Object>> export(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=ekraah-export.json")
                .body(users.exportData(userId));
    }

    @DeleteMapping("/me")
    @Operation(summary = "Delete account and all data")
    public ResponseEntity<Void> deleteMe(@AuthenticationPrincipal String userId) {
        users.deleteAccount(userId);
        return ResponseEntity.noContent().build();
    }
}
