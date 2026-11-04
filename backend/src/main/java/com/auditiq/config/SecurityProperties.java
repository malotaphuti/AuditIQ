package com.auditiq.config;

import java.time.Duration;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Security configuration bound from {@code auditiq.security.*}. Start-up fails fast if the JWT secret is missing or weak.
 */
@Validated
@ConfigurationProperties(prefix = "auditiq.security")
public record SecurityProperties(
        @NotBlank(message = "JWT_SECRET must be set")
        @Size(min = 32, message = "JWT_SECRET must be at least 32 characters (256 bits)")
        String jwtSecret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        boolean selfRegistrationEnabled,
        int maxFailedLogins,
        Duration lockoutDuration,
        List<String> corsAllowedOrigins) {
}
