package com.auditiq.overview;

import com.auditiq.support.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OverviewIntegrationTest extends AbstractIntegrationTest {

    @Test
    void overviewReflectsEmptyDatabaseWithRealZeros() throws Exception {
        mvc.perform(get("/api/overview").header("Authorization", auditorToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions.total").value(0))
                .andExpect(jsonPath("$.exceptions.total").value(0))
                .andExpect(jsonPath("$.exceptions.exceptionRatePercent").value(0))
                .andExpect(jsonPath("$.investigationsByStatus.NEW").value(0))
                .andExpect(jsonPath("$.pipeline.totalRuns").value(0))
                .andExpect(jsonPath("$.transactionTrend").isEmpty())
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
