package com.ekraah.controller;

import com.ekraah.dto.AuthDtos.AuthResponse;
import com.ekraah.dto.AuthDtos.LoginRequest;
import com.ekraah.dto.AuthDtos.RegisterRequest;
import com.ekraah.config.AppProperties;
import com.ekraah.dto.AuthDtos.RequestOtpRequest;
import com.ekraah.dto.AuthDtos.ResetPasswordRequest;
import com.ekraah.dto.AuthDtos.UserView;
import com.ekraah.service.AuthService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import com.ekraah.model.Session;
import com.ekraah.security.SessionService;
import com.ekraah.dto.AuthDtos.RefreshRequest;
import java.time.Duration;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
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
                "demoMode", fixed && !real,    // demo profile pins app.otp.fixed, prod leaves it blank
                "otpMode", real ? "sms" : props.getOtp().getSender());
    }

    @PostMapping("/request-otp")
    @Operation(summary = "Send an OTP to the phone number (signup / forgot password)")
    public ResponseEntity<Map<String, Object>> requestOtp(@Valid @RequestBody RequestOtpRequest body) {
        auth.requestOtp(body.phone());
        return ResponseEntity.ok(Map.of("sent", true));
    }

    @PostMapping("/login")
    @Operation(summary = "Log in with phone + password: 15-min access token + 30-day revocable session")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        // getRemoteAddr is the direct peer; behind a reverse proxy set
        // server.forward-headers-strategy=native so it reflects X-Forwarded-For instead.
        var result = auth.login(body.phone(), body.password(), request.getRemoteAddr(), kind(body.client()));
        return respond(result, kind(body.client()), request);
    }

    @PostMapping("/register")
    @Operation(summary = "Create an account (or set its first password) with phone + OTP, and log in")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
        var result = auth.register(body.phone(), body.otp(), body.password(), kind(body.client()));
        return respond(result, kind(body.client()), request);
    }

    @PostMapping("/refresh")
    @Operation(summary = "New access token from the session's refresh token (cookie, or body for the extension); rotates it")
    public ResponseEntity<AuthResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String cookie,
                                                @RequestBody(required = false) RefreshRequest body,
                                                HttpServletRequest request) {
        boolean fromBody = body != null && body.refreshToken() != null;
        var result = auth.refresh(fromBody ? body.refreshToken() : cookie);
        return respond(result, fromBody ? Session.Kind.EXTENSION : Session.Kind.WEB, request);
    }

    @PostMapping("/logout")
    @Operation(summary = "End this session now (its access tokens stop working immediately)")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String cookie,
                                       @RequestBody(required = false) RefreshRequest body,
                                       HttpServletRequest request) {
        auth.logout(body != null && body.refreshToken() != null ? body.refreshToken() : cookie);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO, request).toString()).build();
    }

    // ---- refresh-token transport ---------------------------------------------------

    static final String REFRESH_COOKIE = "ekraah_refresh";

    private static Session.Kind kind(String client) {
        return "extension".equals(client) ? Session.Kind.EXTENSION : Session.Kind.WEB;
    }

    /**
     * Web: the refresh token goes into an HttpOnly cookie (page scripts can't read it, so XSS can't
     * steal a 30-day session), SameSite=Strict and scoped to /api/auth (not sent cross-site or to
     * other endpoints). The access token stays in page memory only. Extension: in the body, since
     * it can't rely on first-party cookies; it is kept in chrome.storage.
     */
    private static ResponseEntity<AuthResponse> respond(AuthService.AuthResult r, Session.Kind kind, HttpServletRequest request) {
        UserView user = UserView.of(r.user());
        if (kind == Session.Kind.EXTENSION) {
            return ResponseEntity.ok(new AuthResponse(r.token(), r.expiresIn(), user, r.refreshToken()));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(r.refreshToken(), SessionService.LIFETIME, request).toString())
                .body(new AuthResponse(r.token(), r.expiresIn(), user, null));
    }

    private static ResponseCookie refreshCookie(String value, Duration maxAge, HttpServletRequest request) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(request.isSecure())   // https in production; plain http on localhost
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(maxAge)
                .build();
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Replace a forgotten password using phone + OTP")
    public ResponseEntity<Map<String, Object>> resetPassword(@Valid @RequestBody ResetPasswordRequest body) {
        auth.resetPassword(body.phone(), body.otp(), body.password());
        return ResponseEntity.ok(Map.of("reset", true));
    }
}
