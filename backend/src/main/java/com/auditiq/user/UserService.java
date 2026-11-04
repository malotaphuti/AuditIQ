package com.auditiq.user;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.auditiq.audit.AuditAction;
import com.auditiq.audit.AuditLogService;
import com.auditiq.common.Exceptions.BusinessRuleException;
import com.auditiq.common.Exceptions.ConflictException;
import com.auditiq.common.Exceptions.NotFoundException;
import com.auditiq.common.PageResponse;
import com.auditiq.security.CurrentUser;
import com.auditiq.user.UserDtos.UserResponse;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService audit;

    public UserService(UserRepository users, RoleRepository roles, PasswordEncoder passwordEncoder,
                       AuditLogService audit) {
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
    }

    /** Low-level creation used by admin, self-registration and bootstrap. Caller is responsible for auditing. */
    @Transactional
    public User createUser(String username, String email, String fullName, String rawPassword, Set<RoleName> roleNames) {
        String normalisedUsername = username.trim().toLowerCase();
        String normalisedEmail = email.trim().toLowerCase();
        if (users.existsByUsernameIgnoreCase(normalisedUsername)) {
            throw new ConflictException("Username is already in use");
        }
        if (users.existsByEmailIgnoreCase(normalisedEmail)) {
            throw new ConflictException("Email is already in use");
        }
        User user = new User(normalisedUsername, normalisedEmail, fullName.trim(),
                passwordEncoder.encode(rawPassword), resolveRoles(roleNames));
        return users.save(user);
    }

    @Transactional
    public UserResponse adminCreate(UserDtos.CreateUserRequest request) {
        User user = createUser(request.username(), request.email(), request.fullName(), request.password(),
                request.roles());
        audit.record(AuditAction.USER_CREATED, "USER", user.getId(),
                Map.of("username", user.getUsername(), "roles", sorted(user.roleNames())));
        return UserResponse.from(user);
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(String search, int page, int size) {
        String term = search == null || search.isBlank() ? null : search.trim();
        var pageable = PageRequest.of(page, size, Sort.by("username"));
        return PageResponse.from(users.search(term, pageable), UserResponse::from);
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listByRole(RoleName role) {
        return users.findAll(Sort.by("fullName")).stream()
                .filter(u -> u.isEnabled() && u.roleNames().contains(role))
                .map(UserResponse::from)
                .toList();
    }

    @Transactional
    public UserResponse setEnabled(Long userId, boolean enabled) {
        User user = find(userId);
        if (!enabled && userId.equals(CurrentUser.require().id())) {
            throw new BusinessRuleException("Administrators cannot disable their own account");
        }
        user.setEnabled(enabled);
        audit.record(enabled ? AuditAction.USER_ENABLED : AuditAction.USER_DISABLED, "USER", userId,
                Map.of("username", user.getUsername()));
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse unlock(Long userId) {
        User user = find(userId);
        user.unlock();
        audit.record(AuditAction.USER_UNLOCKED, "USER", userId, Map.of("username", user.getUsername()));
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse updateRoles(Long userId, Set<RoleName> roleNames) {
        User user = find(userId);
        if (userId.equals(CurrentUser.require().id()) && !roleNames.contains(RoleName.ADMIN)) {
            throw new BusinessRuleException("Administrators cannot remove their own ADMIN role");
        }
        Set<RoleName> before = user.roleNames();
        user.setRoles(resolveRoles(roleNames));
        audit.record(AuditAction.USER_ROLES_CHANGED, "USER", userId,
                Map.of("username", user.getUsername(), "before", sorted(before), "after", sorted(roleNames)));
        return UserResponse.from(user);
    }

    private User find(Long id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("User " + id + " not found"));
    }

    private Set<Role> resolveRoles(Set<RoleName> names) {
        if (names == null || names.isEmpty()) {
            throw new BusinessRuleException("At least one role is required");
        }
        List<Role> found = roles.findByNameIn(names);
        if (found.size() != EnumSet.copyOf(names).size()) {
            throw new IllegalStateException("Role reference data is incomplete");
        }
        return new HashSet<>(found);
    }

    private static List<String> sorted(Set<RoleName> names) {
        return names.stream().map(Enum::name).sorted().toList();
    }
}
