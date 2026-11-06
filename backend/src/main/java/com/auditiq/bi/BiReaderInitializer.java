package com.auditiq.bi;

import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates (or updates the password of) a PostgreSQL login for Power BI that can read only the {@code analytics}
 * schema, via the {@code analytics_reader} role from migration V6. The password comes from the environment; values
 * are quoted by PostgreSQL itself, so nothing user-supplied is spliced into SQL unescaped.
 */
@Component
public class BiReaderInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BiReaderInitializer.class);
    static final Pattern USERNAME = Pattern.compile("^[a-z_][a-z0-9_]{2,62}$");
    static final int MIN_PASSWORD_LENGTH = 16;

    private final JdbcTemplate jdbc;
    private final BiProperties props;

    public BiReaderInitializer(JdbcTemplate jdbc, BiProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        String password = props.readerPassword();
        if (password == null || password.isBlank()) {
            return;
        }
        String username = props.readerUsername() == null ? "auditiq_bi" : props.readerUsername().trim();
        if (!USERNAME.matcher(username).matches()) {
            log.error("BI_READER_USERNAME '{}' is invalid; the Power BI login was not created", username);
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            log.error("BI_READER_PASSWORD must be at least {} characters; the Power BI login was not created",
                    MIN_PASSWORD_LENGTH);
            return;
        }
        try {
            String ident = jdbc.queryForObject("SELECT quote_ident(?)", String.class, username);
            String literal = jdbc.queryForObject("SELECT quote_literal(?)", String.class, password);
            Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = ?)",
                    Boolean.class, username);
            if (Boolean.TRUE.equals(exists)) {
                jdbc.execute("ALTER ROLE " + ident + " WITH LOGIN PASSWORD " + literal);
            } else {
                jdbc.execute("CREATE ROLE " + ident + " WITH LOGIN PASSWORD " + literal
                        + " NOSUPERUSER NOCREATEDB NOCREATEROLE CONNECTION LIMIT 10");
            }
            jdbc.execute("GRANT analytics_reader TO " + ident);
            jdbc.execute("ALTER ROLE " + ident + " SET search_path = analytics");
            jdbc.execute("ALTER ROLE " + ident + " SET default_transaction_read_only = on");
            log.info("Power BI read-only login '{}' is ready (analytics schema only)", username);
        } catch (DataAccessException ex) {
            log.error("Could not create the Power BI login '{}': {}", username, ex.getMostSpecificCause().getMessage());
        }
    }
}
