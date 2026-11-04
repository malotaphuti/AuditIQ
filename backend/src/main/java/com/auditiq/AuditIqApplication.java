package com.auditiq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AuditIqApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditIqApplication.class, args);
    }
}
