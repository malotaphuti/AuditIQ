package com.auditiq.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Location of the internal Python analytics / AI service. */
@ConfigurationProperties(prefix = "auditiq.analytics-service")
public record AnalyticsServiceProperties(String baseUrl, String serviceKey) {
}
