package com.auditiq.admin;

import java.util.List;
import java.util.Map;

import com.auditiq.support.AbstractIntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminUserIntegrationTest extends AbstractIntegrationTest {

    private Map<String, Object> newUser(String username) {
        return Map.of("username", username, "email", username + "@auditiq.local", "fullName", "Test " + username,
                "password", "Strong#Passw0rd1", "roles", List.of("AUDITOR"));
    }

    @Test
    void auditorAndManagerCannotManageUsers() throws Exception {
        mvc.perform(get("/api/admin/users").header("Authorization", auditorToken())).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").header("Authorization", managerToken())).andExpect(status().isForbidden());
    }

    @Test
    void adminCanListAndCreateUsers() throws Exception {
        String admin = adminToken();
        mvc.perform(post("/api/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(newUser("created.user"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("created.user"))
                .andExpect(jsonPath("$.roles[0]").value("AUDITOR"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        mvc.perform(get("/api/admin/users").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", greaterThanOrEqualTo(4)));

        // Creation is captured in the audit trail.
        mvc.perform(get("/api/admin/audit-logs").param("action", "USER_CREATED").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].details.username").exists());
    }

    @Test
    void duplicateUsernameIsConflict() throws Exception {
        String admin = adminToken();
        mvc.perform(post("/api/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(newUser("dup.user")))).andExpect(status().isCreated());
        mvc.perform(post("/api/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(newUser("dup.user"))))
                .andExpect(status().isConflict());
    }

    @Test
    void weakPasswordAndBadEmailAreRejected() throws Exception {
        mvc.perform(post("/api/admin/users").header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "weak", "email", "not-an-email",
                                "fullName", "Weak", "password", "password", "roles", List.of("AUDITOR")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").exists())
                .andExpect(jsonPath("$.fieldErrors.email").exists());
    }

    @Test
    void unknownRoleIsRejected() throws Exception {
        mvc.perform(post("/api/admin/users").header("Authorization", adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "x.role", "email", "x@x.io",
                                "fullName", "X", "password", "Strong#Passw0rd1", "roles", List.of("SUPERUSER")))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminCannotDisableSelf() throws Exception {
        String admin = adminToken();
        long adminId = login("admin", ADMIN_PASSWORD).get("user").get("id").asLong();
        mvc.perform(patch("/api/admin/users/{id}/status", adminId).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void disabledUserCannotLogIn() throws Exception {
        String admin = adminToken();
        long id = json.readTree(mvc.perform(post("/api/admin/users").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(newUser("to.disable"))))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        mvc.perform(patch("/api/admin/users/{id}/status", id).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "to.disable", "password", "Strong#Passw0rd1"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void auditTrailVisibleToManagerButNotAuditor() throws Exception {
        mvc.perform(get("/api/admin/audit-logs").header("Authorization", managerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
        mvc.perform(get("/api/admin/audit-logs").header("Authorization", auditorToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditLogPaginationIsBounded() throws Exception {
        mvc.perform(get("/api/admin/audit-logs").param("size", "5000").header("Authorization", adminToken()))
                .andExpect(status().isBadRequest());
    }
}
