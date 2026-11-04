package com.auditiq.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.auditiq.config.SecurityProperties;
import com.auditiq.user.RoleName;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Issues and validates short-lived HS256 access tokens. */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String ISSUER = "auditiq";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_UID = "uid";

    private final SecretKey key;
    private final SecurityProperties props;
    private final Clock clock;

    public JwtService(SecurityProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(props.jwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String issueAccessToken(AuthenticatedUser user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(user.username())
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_UID, user.id())
                .claim(CLAIM_ROLES, user.roles().stream().map(Enum::name).sorted().toList())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.accessTokenTtl())))
                .signWith(key)
                .compact();
    }

    public long accessTokenTtlSeconds() {
        return props.accessTokenTtl().toSeconds();
    }

    /** Returns the principal if the token is authentic and unexpired; empty otherwise. */
    public Optional<AuthenticatedUser> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long uid = claims.get(CLAIM_UID, Number.class).longValue();
            List<?> rawRoles = claims.get(CLAIM_ROLES, List.class);
            Set<RoleName> roles = EnumSet.noneOf(RoleName.class);
            for (Object r : rawRoles) {
                roles.add(RoleName.valueOf(r.toString()));
            }
            return Optional.of(new AuthenticatedUser(uid, claims.getSubject(), roles));
        } catch (JwtException | IllegalArgumentException | NullPointerException ex) {
            log.debug("Rejected JWT: {}", ex.getMessage());
            return Optional.empty();
        }
    }
}
