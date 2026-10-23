package uk.gov.hmcts.cp.integration;

import jakarta.annotation.Resource;
import lombok.SneakyThrows;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.cp.integration.config.PostgresInitialise;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Integration tests run against a real local Postgres (constitution Principle VII). Every cached test context has a
 * cp-task-manager executor on the same {@code jobs} table; it is effectively parked here (one run at start-up), so jobs
 * are only picked up by the embedded-broker context, which sets its own poll interval and has the broker and profiles
 * the notification flow needs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = PostgresInitialise.class)
@TestPropertySource(properties = "job.executor.poll-interval=86400000")
public abstract class IntegrationTestBase {

    @Resource
    protected MockMvc mockMvc;

    @SneakyThrows
    protected String readResourceContents(final String resourceName) {
        final URL resource = getClass().getClassLoader().getResource(resourceName);
        return Files.readString(Path.of(resource.toURI()));
    }
}
