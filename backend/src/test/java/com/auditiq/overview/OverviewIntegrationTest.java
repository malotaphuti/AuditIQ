package com.auditiq.overview;

import com.auditiq.support.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OverviewIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void overviewFiguresMatchTheDatabase() throws Exception {
        long transactions = jdbc.queryForObject("SELECT count(*) FROM transactions", Long.class);
        long runs = jdbc.queryForObject("SELECT count(*) FROM pipeline_runs", Long.class);
        long exceptions = jdbc.queryForObject("SELECT count(*) FROM audit_exceptions", Long.class);
        mvc.perform(get("/api/overview").header("Authorization", auditorToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions.total").value(transactions))
                .andExpect(jsonPath("$.exceptions.total").value(exceptions))
                .andExpect(jsonPath("$.investigationsByStatus.NEW").exists())
                .andExpect(jsonPath("$.pipeline.totalRuns").value(runs))
                // The auditor's own login is part of their recent activity.
                .andExpect(jsonPath("$.recentActivity[0].username").value("auditor"));
    }

    @Test
    void overviewRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/overview")).andExpect(status().isUnauthorized());
    }

    @Test
    void systemStatusReportsDependencies() throws Exception {
        mvc.perform(get("/api/system/status").header("Authorization", managerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("UP"))
                .andExpect(jsonPath("$.analyticsService").value("DOWN"));
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void openApiDocumentIsPublished() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("AuditIQ API"));
    }
}
