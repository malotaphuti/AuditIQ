package com.auditiq.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import com.auditiq.audit.AuditAction;
import com.auditiq.audit.AuditLog.Outcome;
import com.auditiq.audit.AuditLogService;
import com.auditiq.auth.AuthDtos.AuthResponse;
import com.auditiq.auth.AuthDtos.LoginRequest;
import com.auditiq.auth.AuthDtos.RegisterRequest;
import com.auditiq.auth.AuthDtos.UserSummary;
import com.auditiq.common.Exceptions.FeatureDisabledException;
import com.auditiq.config.SecurityProperties;
import com.auditiq.security.AuthenticatedUser;
import com.auditiq.security.JwtService;
import com.auditiq.user.RoleName;
import com.auditiq.user.User;
import com.auditiq.user.UserRepository;
import com.auditiq.user.UserService;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    /** Deliberately generic so responses do not reveal whether a username exists. */
    static final String INVALID_CREDENTIALS = "Invalid username or password, or the account is locked or disabled";
    static final String INVALID_REFRESH = "Refresh token is invalid or expired";

    /** Pre-computed hash used to keep timing constant when the username does not exist. */
    private final String dummyHash;

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditLogService audit;
    private final SecurityProperties props;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, UserService userService,
                       PasswordEncoder passwordEncoder, JwtService jwtService, AuditLogService audit,
                       SecurityProperties props, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("timing-equaliser-not-a-real-password");
    }

    @Transactional(noRollbackFor = BadCredentialsException.class)
    public AuthResponse login(LoginRequest request) {
        Instant now = clock.instant();
        User user = users.findByUsernameIgnoreCase(request.username().trim()).orElse(null);

        if (user == null) {
            passwordEncoder.matches(request.password(), dummyHash);
            fail(null, request.username(), "UNKNOWN_USER");
        }
        if (!user.isEnabled()) {
            fail(user.getId(), user.getUsername(), "ACCOUNT_DISABLED");
        }
        if (user.isLocked(now)) {
            fail(user.getId(), user.getUsername(), "ACCOUNT_LOCKED");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            user.registerFailedLogin(props.maxFailedLogins(), now.plus(props.lockoutDuration()));
            users.save(user);
            fail(user.getId(), user.getUsername(), user.isLocked(now) ? "BAD_PASSWORD_LOCKED" : "BAD_PASSWORD");
        }

        user.registerSuccessfulLogin(now);
        users.save(user);
        audit.recordFor(user.getId(), user.getUsername(), AuditAction.LOGIN_SUCCESS, Outcome.SUCCESS, Map.of());
        return issueTokens(user, now);
    }

    @Transactional(noRollbackFor = BadCredentialsException.class)
    public AuthResponse refresh(String presentedToken) {
        Instant now = clock.instant();
        RefreshToken token = refreshTokens.findByTokenHash(sha256(presentedToken))
                .orElseThrow(() -> new BadCredentialsException(INVALID_REFRESH));
        User user = token.getUser();

        if (token.isRevoked()) {
            // A revoked token being replayed suggests theft: revoke the whole token family.
            refreshTokens.revokeAllForUser(user.getId());
            audit.recordFor(user.getId(), user.getUsername(), AuditAction.TOKEN_REUSE_DETECTED, Outcome.FAILURE,
                    Map.of("action", "all refresh tokens revoked"));
            throw new BadCredentialsException(INVALID_REFRESH);
        }
        if (!token.isUsable(now) || !user.isEnabled() || user.isLocked(now)) {
            throw new BadCredentialsException(INVALID_REFRESH);
        }

        token.revoke();
        audit.recordFor(user.getId(), user.getUsername(), AuditAction.TOKEN_REFRESH, Outcome.SUCCESS, Map.of());
        return issueTokens(user, now);
    }

    @Transactional
    public void logout(String presentedToken) {
        refreshTokens.findByTokenHash(sha256(presentedToken)).ifPresent(token -> {
            token.revoke();
            User user = token.getUser();
            audit.recordFor(user.getId(), user.getUsername(), AuditAction.LOGOUT, Outcome.SUCCESS, Map.of());
        });
    }

    /** Self-service registration – disabled by default; always creates an AUDITOR with no elevated rights. */
    @Transactional
    public UserSummary register(RegisterRequest request) {
        if (!props.selfRegistrationEnabled()) {
            throw new FeatureDisabledException("Self-registration is disabled. Ask an administrator for an account.");
        }
        User user = userService.createUser(request.username(), request.email(), request.fullName(),
                request.password(), java.util.Set.of(RoleName.AUDITOR));
        audit.recordFor(user.getId(), user.getUsername(), AuditAction.USER_REGISTERED, Outcome.SUCCESS,
                Map.of("roles", "AUDITOR"));
        return toSummary(user);
    }

    private AuthResponse issueTokens(User user, Instant now) {
        var principal = new AuthenticatedUser(user.getId(), user.getUsername(), user.roleNames());
        String accessToken = jwtService.issueAccessToken(principal);

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.save(new RefreshToken(user, sha256(refreshToken), now, now.plus(props.refreshTokenTtl())));

        return new AuthResponse(accessToken, refreshToken, "Bearer", jwtService.accessTokenTtlSeconds(),
                toSummary(user));
    }

    private void fail(Long userId, String username, String reason) {
        audit.recordFor(userId, username, AuditAction.LOGIN_FAILURE, Outcome.FAILURE, Map.of("reason", reason));
        throw new BadCredentialsException(INVALID_CREDENTIALS);
    }

    static UserSummary toSummary(User user) {
        return new UserSummary(user.getId(), user.getUsername(), user.getFullName(), user.getEmail(),
                user.roleNames());
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
