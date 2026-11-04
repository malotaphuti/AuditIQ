package com.auditiq.system;

import java.time.Instant;

import com.auditiq.analytics.AnalyticsClient;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dependency status for the UI header (separate from actuator so a down AI service does not fail liveness). */
@RestController
@RequestMapping("/api/system")
@Tag(name = "System")
public class SystemStatusController {

    private static final Logger log = LoggerFactory.getLogger(SystemStatusController.class);

    private final JdbcTemplate jdbc;
    private final AnalyticsClient analytics;

    public SystemStatusController(JdbcTemplate jdbc, AnalyticsClient analytics) {
        this.jdbc = jdbc;
        this.analytics = analytics;
    }

    public record SystemStatus(Instant checkedAt, String database, String analyticsService, String version) {
    }

    @GetMapping("/status")
    @Operation(summary = "Status of the database and the analytics/AI service")
    public SystemStatus status() {
        return new SystemStatus(Instant.now(), databaseStatus(), analytics.health(), "0.2.0");
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
}
