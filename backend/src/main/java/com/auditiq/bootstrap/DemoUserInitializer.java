package com.auditiq.bootstrap;

import java.util.Set;

import com.auditiq.config.BootstrapProperties;
import com.auditiq.user.RoleName;
import com.auditiq.user.UserRepository;
import com.auditiq.user.UserService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates one demo user per role on an empty database. Passwords come from environment variables, never source code;
 * a blank password skips that user.
 */
@Component
public class DemoUserInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoUserInitializer.class);

    private final UserRepository users;
    private final UserService userService;
    private final BootstrapProperties props;

    public DemoUserInitializer(UserRepository users, UserService userService, BootstrapProperties props) {
        this.users = users;
        this.userService = userService;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        create("admin", "admin@auditiq.local", "System Administrator", props.adminPassword(), RoleName.ADMIN);
        create("auditor", "auditor@auditiq.local", "Thandi Auditor", props.auditorPassword(), RoleName.AUDITOR);
        create("manager", "manager@auditiq.local", "Sipho Audit Manager", props.managerPassword(),
                RoleName.AUDIT_MANAGER);
    }

    private void create(String username, String email, String fullName, String password, RoleName role) {
        if (password == null || password.isBlank()) {
            log.info("Bootstrap password for '{}' not set – user not created", username);
            return;
        }
        userService.createUser(username, email, fullName, password, Set.of(role));
        log.info("Created bootstrap user '{}' with role {}", username, role);
    }
}
