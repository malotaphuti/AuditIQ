package com.auditiq.security;

import java.util.Collection;
import java.util.Set;

import com.auditiq.user.RoleName;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Principal placed in the security context after JWT validation. */
public record AuthenticatedUser(Long id, String username, Set<RoleName> roles) {

    public Collection<? extends GrantedAuthority> authorities() {
        return roles.stream().map(r -> new SimpleGrantedAuthority(r.authority())).toList();
    }

    public boolean hasRole(RoleName role) {
        return roles.contains(role);
    }
}
