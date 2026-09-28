package uk.gov.hmcts.cp.integration.config;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

// Real, manually-started Postgres, not Testcontainers — matches service-cp-crime-results-pcr's PostgresInitialise.
public class PostgresInitialise implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final String URL = "jdbc:postgresql://localhost:5432/enforcementworkflowdb";
    private static final String USER = "postgres";
    private static final String PASSWORD = "postgres";

    @Override
    public void initialize(final ConfigurableApplicationContext ctx) {
        assertPostgresReachable(URL, USER, PASSWORD);
        TestPropertyValues.of(
                "spring.datasource.url=" + URL,
                "spring.datasource.username=" + USER,
                "spring.datasource.password=" + PASSWORD,
                // Cached test contexts each keep their own Hikari pool; default size exhausts max_connections.
                "spring.datasource.hikari.maximum-pool-size=4"
        ).applyTo(ctx.getEnvironment());
    }

    static void assertPostgresReachable(final String url, final String user, final String password) {
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            conn.isValid(1);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "\n\n*** Integration tests require PostgreSQL on localhost:5432 (database: enforcementworkflowdb) ***\n"
                    + "Start it:\n"
                    + "  docker compose up -d postgres\n\n",
                    e);
        }
    }
}
