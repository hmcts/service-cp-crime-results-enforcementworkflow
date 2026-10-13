package uk.gov.hmcts.cp.integration.config;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

// Real, manually-started Postgres, not Testcontainers — matches service-cp-crime-results-pcr's PostgresInitialise.
public class PostgresInitialise implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final String DATABASE = "enforcementworkflowdb";
    private static final String SERVER_URL = "jdbc:postgresql://localhost:5432/postgres";
    private static final String URL = "jdbc:postgresql://localhost:5432/" + DATABASE;
    private static final String USER = "postgres";
    private static final String PASSWORD = "postgres";

    @Override
    public void initialize(final ConfigurableApplicationContext ctx) {
        ensureDatabaseExists();
        TestPropertyValues.of(
                "spring.datasource.url=" + URL,
                "spring.datasource.username=" + USER,
                "spring.datasource.password=" + PASSWORD,
                // Cached test contexts each keep their own Hikari pool; default size exhausts max_connections.
                "spring.datasource.hikari.maximum-pool-size=4"
        ).applyTo(ctx.getEnvironment());
    }

    // The Postgres on 5432 may be a shared one (e.g. cpp-developers-docker, recreated by other contexts'
    // integration-test scripts) that lacks this database, so create it when missing.
    static void ensureDatabaseExists() {
        try (Connection conn = DriverManager.getConnection(SERVER_URL, USER, PASSWORD);
             PreparedStatement exists = conn.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
            exists.setString(1, DATABASE);
            try (ResultSet rs = exists.executeQuery()) {
                if (!rs.next()) {
                    try (Statement create = conn.createStatement()) {
                        create.execute("CREATE DATABASE " + DATABASE);
                    }
                }
            }
        } catch (SQLException e) {
            // A concurrent JVM may have created it between the check and the create.
            if (!"42P04".equals(e.getSQLState())) {
                throw new IllegalStateException(
                        "\n\n*** Integration tests require PostgreSQL on localhost:5432 (user/password: postgres) ***\n"
                        + "Start it:\n"
                        + "  docker compose up -d postgres\n\n",
                        e);
            }
        }
    }
}
