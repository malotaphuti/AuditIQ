package com.auditiq.overview;

import com.auditiq.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/overview")
@Tag(name = "Overview")
public class OverviewController {

    private final OverviewService service;

    public OverviewController(OverviewService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Headline KPIs, pipeline health, trends and recent activity")
    public OverviewResponse overview() {
        return service.build(CurrentUser.require());
    }
}
