package com.auditiq.user;

import java.time.Instant;
import java.util.Set;

import com.auditiq.auth.AuthDtos;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class UserDtos {

    private UserDtos() {
    }

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = AuthDtos.USERNAME_REGEX, message = "Username must be 3-50 letters, digits, . _ or -")
            String username,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Pattern(regexp = AuthDtos.PASSWORD_REGEX, message = AuthDtos.PASSWORD_MESSAGE) String password,
            @NotEmpty Set<RoleName> roles) {
    }

    public record UpdateStatusRequest(@NotNull Boolean enabled) {
    }

    public record UpdateRolesRequest(@NotEmpty Set<RoleName> roles) {
    }

    public record UserResponse(Long id, String username, String email, String fullName, boolean enabled,
                               boolean locked, Set<RoleName> roles, Instant lastLoginAt, Instant createdAt) {

        public static UserResponse from(User u) {
            return new UserResponse(u.getId(), u.getUsername(), u.getEmail(), u.getFullName(), u.isEnabled(),
                    u.isLocked(Instant.now()), u.roleNames(), u.getLastLoginAt(), u.getCreatedAt());
        }
    }
}
