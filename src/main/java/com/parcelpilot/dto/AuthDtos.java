package com.parcelpilot.dto;

import com.parcelpilot.model.User;
import com.parcelpilot.model.UserSettings;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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

    public record AuthResponse(String token, long expiresIn, UserView user) {}

    public record UserView(String id, String phone, String displayName, String inboundAlias,
                           boolean forwardingVerified, UserSettings settings) {
        public static UserView of(User u) {
            return new UserView(u.getId(), u.getPhone(), u.getDisplayName(), u.getInboundAlias(),
                    u.isForwardingVerified(), u.getSettings());
        }
    }
}
