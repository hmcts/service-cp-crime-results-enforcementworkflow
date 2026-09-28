package uk.gov.hmcts.cp.integration;

import jakarta.annotation.Resource;
import lombok.SneakyThrows;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.cp.integration.config.PostgresInitialise;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/** Integration tests run against a real local Postgres (constitution Principle VII). */
@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = PostgresInitialise.class)
public abstract class IntegrationTestBase {

    @Resource
    protected MockMvc mockMvc;

    @SneakyThrows
    protected String readResourceContents(final String resourceName) {
        final URL resource = getClass().getClassLoader().getResource(resourceName);
        return Files.readString(Path.of(resource.toURI()));
    }
}
