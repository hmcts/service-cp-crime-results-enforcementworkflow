package uk.gov.hmcts.cp.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code cp.reference-data.*} and {@code cp.enforcement-gateway.*}: the other two outbound HTTP targets, typed and read
 * in one place (as service-cp-crime-informant-register does), next to {@link ResultsProperties} for the Results query.
 * Each hop gives up before its caller (timeout budget, research.md R20).
 */
@Getter
@Setter
@Validated
@ConfigurationProperties("cp")
public class ClientProperties {

    private final ReferenceData referenceData = new ReferenceData();
    private final EnforcementGateway enforcementGateway = new EnforcementGateway();
    private final EnforcementArea enforcementArea = new EnforcementArea();
    private final DocGenerator docGenerator = new DocGenerator();
    private final Progression progression = new Progression();

    @Getter
    @Setter
    public static class ReferenceData {
        /** Reference data host only; the client appends {@code /referencedata-query-api/...}. */
        @NotBlank
        private String baseUrl = "http://localhost:8081";
        /** A System Users member. Required: checked at startup by {@link StartupPropertiesValidator}. */
        private String cjscppuid;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(10);
    }

    @Getter
    @Setter
    public static class EnforcementGateway {
        @NotBlank
        private String baseUrl = "http://localhost:8082";
        private Duration connectTimeout = Duration.ofSeconds(5);
        /** Must exceed the gateway's own total towards APIM (connect 5s + read 40s). */
        private Duration readTimeout = Duration.ofSeconds(50);
    }

    /** Feature 005: {@code enforcement_area} reference-data lookup (contracts/enforcement-area.md, plan.md D1). */
    @Getter
    @Setter
    public static class EnforcementArea {
        @NotBlank
        private String baseUrl = "http://localhost:8081";
        private String cjscppuid;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(10);
    }

    /** Feature 005: System Doc Generator BYO-FS {@code generate-document} command (contracts/system-doc-generator-byo-fs.md). */
    @Getter
    @Setter
    public static class DocGenerator {
        @NotBlank
        private String baseUrl = "http://localhost:8089";
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(10);
    }

    /** Feature 005 FR-020: {@code cpp-context-progression}'s {@code progression-command-api} (contracts/progression-court-document.md). */
    @Getter
    @Setter
    public static class Progression {
        @NotBlank
        private String baseUrl = "http://localhost:8090";
        /** This service's own registered system user (plan.md D13 - must be registered/allow-listed on Progression's side). */
        private String cjscppuid;
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(10);
    }
}
