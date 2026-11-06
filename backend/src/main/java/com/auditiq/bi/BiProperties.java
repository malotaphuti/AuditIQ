package com.auditiq.bi;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Read-only reporting login for Power BI. A blank password means "do not create". */
@ConfigurationProperties(prefix = "auditiq.bi")
public record BiProperties(String readerUsername, String readerPassword) {
}
