package com.parcelpilot.controller;

import com.parcelpilot.model.AuditLog;
import com.parcelpilot.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/privacy")
public class PrivacyController {

    private final AuditService audit;

    public PrivacyController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping("/audit")
    @Operation(summary = "Plain-language audit log of what the mail pipeline did")
    public List<AuditLog> audit(@AuthenticationPrincipal String userId) {
        return audit.forUser(userId);
    }
}
