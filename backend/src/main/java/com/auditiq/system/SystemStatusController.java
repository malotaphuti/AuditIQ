package com.auditiq.system;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.auditiq.config.AnalyticsServiceProperties;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/** Dependency status for the UI header (separate from actuator so a down AI service does not fail liveness). */
@RestController
@RequestMapping("/api/system")
@Tag(name = "System")
public class SystemStatusController {

    private static final Logger log = LoggerFactory.getLogger(SystemStatusController.class);

    private final JdbcTemplate jdbc;
    private final RestClient analyticsClient;

    public SystemStatusController(JdbcTemplate jdbc, AnalyticsServiceProperties analytics) {
        this.jdbc = jdbc;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.analyticsClient = RestClient.builder().baseUrl(analytics.baseUrl()).requestFactory(factory).build();
    }

    public record SystemStatus(Instant checkedAt, String database, String analyticsService, String version) {
    }

    @GetMapping("/status")
    @Operation(summary = "Status of the database and the analytics/AI service")
    public SystemStatus status() {
        return new SystemStatus(Instant.now(), databaseStatus(), analyticsStatus(), "0.1.0");
    }

    private String databaseStatus() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return "UP";
        } catch (RuntimeException ex) {
            log.warn("Database status check failed: {}", ex.getMessage());
            return "DOWN";
        }
    }

    private String analyticsStatus() {
        try {
            Map<?, ?> body = analyticsClient.get().uri("/health").retrieve().body(Map.class);
            return body != null && "UP".equals(body.get("status")) ? "UP" : "DEGRADED";
        } catch (RuntimeException ex) {
            log.debug("Analytics service status check failed: {}", ex.getMessage());
            return "DOWN";
        }
    }
}
