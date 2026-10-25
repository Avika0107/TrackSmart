package com.ekraah.dto;

import com.ekraah.model.User;
import com.ekraah.model.UserSettings;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class AuthDtos {

    private AuthDtos() {}

    public record RequestOtpRequest(@NotBlank String phone) {}

    public record LoginRequest(@NotBlank String phone, @NotBlank String password) {}

    /** Signup (or first-time password setup for an OTP-created account). */
    public record RegisterRequest(@NotBlank String phone,
                                  @NotBlank @Size(min = 4, max = 8) String otp,
                                  @NotBlank @Size(min = 8, max = 72) String password) {}

    public record ResetPasswordRequest(@NotBlank String phone,
                                       @NotBlank @Size(min = 4, max = 8) String otp,
                                       @NotBlank @Size(min = 8, max = 72) String password) {}

    /** Profile edit — the phone number is never editable (identity anchor). */
    public record ProfileRequest(@NotBlank @Size(min = 2, max = 40) String displayName) {}

    public record AuthResponse(String token, long expiresIn, UserView user) {}

    public record UserView(String id, String phone, String displayName, String inboundAlias,
                           boolean forwardingVerified, UserSettings settings, Instant createdAt) {
        public static UserView of(User u) {
            return new UserView(u.getId(), u.getPhone(), u.getDisplayName(), u.getInboundAlias(),
                    u.isForwardingVerified(), u.getSettings(), u.getCreatedAt());
        }
    }
}
