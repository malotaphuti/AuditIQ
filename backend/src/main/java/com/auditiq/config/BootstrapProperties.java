package com.auditiq.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Passwords for the demo users created on first start-up. Blank values mean "do not create". */
@ConfigurationProperties(prefix = "auditiq.bootstrap")
public record BootstrapProperties(String adminPassword, String auditorPassword, String managerPassword) {
}
