package com.parcelpilot.controller;

import com.parcelpilot.model.User;
import com.parcelpilot.service.SetupService;
import com.parcelpilot.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/setup")
public class SetupController {

    private final SetupService setup;
    private final UserService users;

    public SetupController(SetupService setup, UserService users) {
        this.setup = setup;
        this.users = users;
    }

    @GetMapping("/info")
    @Operation(summary = "Forwarding address, verification code and status")
    public Map<String, Object> info(@AuthenticationPrincipal String userId) {
        User user = users.get(userId);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("alias", user.getInboundAlias());
        json.put("forwardingAddress", setup.forwardingAddressFor(user));
        json.put("forwardTo", setup.forwardToAddress());
        json.put("forwardingVerified", user.isForwardingVerified());
        json.put("confirmationCode", setup.forwardingConfirmationCode());
        json.put("allowlist", setup.allowlist());
        json.put("demoMode", setup.isDemo());
        return json;
    }

    @GetMapping(value = "/gmail-filter.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @Operation(summary = "Download a Gmail filter file pre-filled with allowlisted senders")
    public ResponseEntity<String> gmailFilter(@AuthenticationPrincipal String userId) {
        User user = users.get(userId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=parcelpilot-gmail-filter.xml")
                .body(setup.gmailFilterXml(user));
    }
}
