package com.auditiq.support;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the full application against a real PostgreSQL + pgvector container, so Flyway migrations, constraints and
 * native SQL are exercised exactly as in production. One container is shared by all integration tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    public static final String ADMIN_PASSWORD = "Admin#Passw0rd!";
    public static final String AUDITOR_PASSWORD = "Auditor#Passw0rd!";
    public static final String MANAGER_PASSWORD = "Manager#Passw0rd!";

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static {
        POSTGRES.start();
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    protected JsonNode login(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    protected String bearer(String username, String password) throws Exception {
        return "Bearer " + login(username, password).get("accessToken").asText();
    }

    protected String adminToken() throws Exception {
        return bearer("admin", ADMIN_PASSWORD);
    }

    protected String auditorToken() throws Exception {
        return bearer("auditor", AUDITOR_PASSWORD);
    }

    protected String managerToken() throws Exception {
        return bearer("manager", MANAGER_PASSWORD);
    }
}
