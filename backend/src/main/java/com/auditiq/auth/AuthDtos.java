package com.auditiq.auth;

import java.util.Set;

import com.auditiq.user.RoleName;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request/response bodies for {@code /api/auth}. */
public final class AuthDtos {

    /** At least 12 characters with upper, lower, digit and symbol. */
    public static final String PASSWORD_REGEX = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{12,128}$";
    public static final String PASSWORD_MESSAGE =
            "Password must be 12-128 characters and include upper-case, lower-case, a digit and a symbol";
    public static final String USERNAME_REGEX = "^[a-zA-Z0-9._-]{3,50}$";

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank @Size(max = 50) String username,
            @NotBlank @Size(max = 128) String password) {
    }

    public record RegisterRequest(
            @NotBlank @Pattern(regexp = USERNAME_REGEX, message = "Username must be 3-50 letters, digits, . _ or -")
            String username,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MESSAGE) String password) {
    }

    public record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {
    }

    public record UserSummary(Long id, String username, String fullName, String email, Set<RoleName> roles) {
    }

    public record AuthResponse(String accessToken, String refreshToken, String tokenType, long expiresIn,
                               UserSummary user) {
    }
}
