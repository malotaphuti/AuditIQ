package com.auditiq.auth;

import java.util.Map;
import java.util.Set;

import com.auditiq.audit.AuditLogRepository;
import com.auditiq.support.AbstractIntegrationTest;
import com.auditiq.user.RoleName;
import com.auditiq.user.UserService;
import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    UserService userService;

    @Autowired
    AuditLogRepository auditLogs;

    @Test
    void loginReturnsTokenPairAndRoles() throws Exception {
        JsonNode body = login("auditor", AUDITOR_PASSWORD);
        assertThat(body.get("accessToken").asText()).isNotBlank();
        assertThat(body.get("refreshToken").asText()).isNotBlank();
        assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.get("user").get("roles").get(0).asText()).isEqualTo("AUDITOR");
    }

    @Test
    void usernameIsCaseInsensitive() throws Exception {
        assertThat(login("AUDITOR", AUDITOR_PASSWORD).get("user").get("username").asText()).isEqualTo("auditor");
    }

    @Test
    void wrongPasswordIsRejectedWithGenericMessageAndAudited() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "manager", "password", "nope-nope"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(AuthService.INVALID_CREDENTIALS));

        assertThat(auditLogs.findByActionOrderByOccurredAtDesc("LOGIN_FAILURE"))
                .anySatisfy(l -> assertThat(l.getUsername()).isEqualTo("manager"));
    }

    @Test
    void unknownUserGetsSameResponseAsWrongPassword() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "ghost", "password", "whatever"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(AuthService.INVALID_CREDENTIALS));
    }

    @Test
    void accountLocksAfterRepeatedFailures() throws Exception {
        userService.createUser("lockme", "lockme@auditiq.local", "Lock Me", "Lock#Me#Passw0rd", Set.of(RoleName.AUDITOR));
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("username", "lockme", "password", "bad-" + i))))
                    .andExpect(status().isUnauthorized());
        }
        // Correct password is now refused because the account is locked.
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "lockme", "password", "Lock#Me#Passw0rd"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void blankCredentialsFailValidation() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "", "password", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.username").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void malformedJsonIsBadRequest() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    void refreshRotatesTokenAndDetectsReuse() throws Exception {
        String original = login("manager", MANAGER_PASSWORD).get("refreshToken").asText();

        String rotated = json.readTree(mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("refreshToken", original))))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .get("refreshToken").asText();
        assertThat(rotated).isNotEqualTo(original);

        // Replaying the old token is refused and revokes the rotated one too.
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", original))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", rotated))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        String refresh = login("auditor", AUDITOR_PASSWORD).get("refreshToken").asText();
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(get("/api/auth/me").header("Authorization", auditorToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("auditor"))
                .andExpect(jsonPath("$.roles", hasItem("AUDITOR")));
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String token = auditorToken();
        mvc.perform(get("/api/auth/me").header("Authorization", token.substring(0, token.length() - 3) + "abc"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void selfRegistrationIsDisabledByDefault() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "newbie", "email", "n@x.io",
                                "fullName", "New Bie", "password", "Valid#Passw0rd!"))))
                .andExpect(status().isForbidden());
    }
}
