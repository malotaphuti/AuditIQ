package com.auditiq.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import com.auditiq.config.SecurityProperties;
import com.auditiq.user.RoleName;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-unit-test-secret-0123456789";
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private JwtService service(String secret, Instant now) {
        var props = new SecurityProperties(secret, Duration.ofMinutes(15), Duration.ofDays(7), false, 5,
                Duration.ofMinutes(15), List.of());
        return new JwtService(props, Clock.fixed(now, ZoneOffset.UTC));
    }

    private final AuthenticatedUser user =
            new AuthenticatedUser(42L, "thandi", Set.of(RoleName.AUDITOR, RoleName.AUDIT_MANAGER));

    @Test
    void roundTripPreservesIdentityAndRoles() {
        JwtService jwt = service(SECRET, NOW);
        AuthenticatedUser parsed = jwt.parse(jwt.issueAccessToken(user)).orElseThrow();
        assertThat(parsed).isEqualTo(user);
    }

    @Test
    void expiredTokenIsRejected() {
        String token = service(SECRET, NOW).issueAccessToken(user);
        assertThat(service(SECRET, NOW.plus(Duration.ofMinutes(16))).parse(token)).isEmpty();
    }

    @Test
    void tokenSignedWithDifferentSecretIsRejected() {
        String token = service("another-secret-another-secret-0123456789ab", NOW).issueAccessToken(user);
        assertThat(service(SECRET, NOW).parse(token)).isEmpty();
    }

    @Test
    void garbageIsRejected() {
        assertThat(service(SECRET, NOW).parse("not.a.jwt")).isEmpty();
        assertThat(service(SECRET, NOW).parse("")).isEmpty();
    }
}
