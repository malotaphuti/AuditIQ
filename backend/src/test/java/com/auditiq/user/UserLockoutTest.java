package com.auditiq.user;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserLockoutTest {

    private final Instant now = Instant.parse("2026-03-01T08:00:00Z");

    @Test
    void locksAfterMaxAttemptsAndUnlocksAfterExpiry() {
        User user = new User("u", "u@x.io", "U", "hash", Set.of());
        for (int i = 0; i < 4; i++) {
            user.registerFailedLogin(5, now.plus(Duration.ofMinutes(15)));
        }
        assertThat(user.isLocked(now)).isFalse();
        user.registerFailedLogin(5, now.plus(Duration.ofMinutes(15)));
        assertThat(user.isLocked(now)).isTrue();
        assertThat(user.isLocked(now.plus(Duration.ofMinutes(16)))).isFalse();
    }

    @Test
    void successfulLoginResetsCounter() {
        User user = new User("u", "u@x.io", "U", "hash", Set.of());
        user.registerFailedLogin(5, now);
        user.registerFailedLogin(5, now);
        user.registerSuccessfulLogin(now);
        assertThat(user.getFailedLoginAttempts()).isZero();
        assertThat(user.getLastLoginAt()).isEqualTo(now);
    }
}
