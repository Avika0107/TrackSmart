package com.parcelpilot.controller;

import com.parcelpilot.dto.AuthDtos.AuthResponse;
import com.parcelpilot.dto.AuthDtos.LoginRequest;
import com.parcelpilot.dto.AuthDtos.RegisterRequest;
import com.parcelpilot.config.AppProperties;
import com.parcelpilot.dto.AuthDtos.RequestOtpRequest;
import com.parcelpilot.dto.AuthDtos.ResetPasswordRequest;
import com.parcelpilot.dto.AuthDtos.UserView;
import com.parcelpilot.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final AppProperties props;

    public AuthController(AuthService auth, AppProperties props) {
        this.auth = auth;
        this.props = props;
    }

    @GetMapping("/mode")
    @Operation(summary = "Which OTP delivery mode is active (demo shows the fixed code, sms does not)")
    public Map<String, Object> mode() {
        boolean fixed = props.getOtp().getFixed() != null && !props.getOtp().getFixed().isBlank();
        boolean real = "sms".equals(props.getOtp().getSender())
                && props.getOtp().getFast2smsKey() != null && !props.getOtp().getFast2smsKey().isBlank();
        return Map.of(
                "demoMode", !fixed && !real,   // demo profile pins app.otp.fixed, prod leaves it blank
                "otpMode", real ? "sms" : props.getOtp().getSender());
    }

    @PostMapping("/request-otp")
    @Operation(summary = "Send an OTP to the phone number (signup / forgot password)")
    public ResponseEntity<Map<String, Object>> requestOtp(@Valid @RequestBody RequestOtpRequest body) {
        auth.requestOtp(body.phone());
        return ResponseEntity.ok(Map.of("sent", true));
    }

    @PostMapping("/login")
    @Operation(summary = "Log in with phone + password and get a JWT")
    public AuthResponse login(@Valid @RequestBody LoginRequest body) {
        var result = auth.login(body.phone(), body.password());
        return new AuthResponse(result.token(), result.expiresIn(), UserView.of(result.user()));
    }

    @PostMapping("/register")
    @Operation(summary = "Create an account (or set its first password) with phone + OTP, and get a JWT")
    public AuthResponse register(@Valid @RequestBody RegisterRequest body) {
        var result = auth.register(body.phone(), body.otp(), body.password());
        return new AuthResponse(result.token(), result.expiresIn(), UserView.of(result.user()));
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Replace a forgotten password using phone + OTP")
    public ResponseEntity<Map<String, Object>> resetPassword(@Valid @RequestBody ResetPasswordRequest body) {
        auth.resetPassword(body.phone(), body.otp(), body.password());
        return ResponseEntity.ok(Map.of("reset", true));
    }
}
