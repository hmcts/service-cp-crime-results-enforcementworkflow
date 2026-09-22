package uk.gov.hmcts.cp.enforcementworkflowsimulator.catalogue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the loaded, validated catalogue as a singleton Spring bean.
 *
 * <p>Also provides a plain {@code com.fasterxml.jackson.databind.ObjectMapper} bean.
 * This app's Spring Boot 4 auto-configuration only registers the newer
 * {@code tools.jackson.databind.json.JsonMapper}, so without this bean any
 * {@code @Component} that asks Spring to inject the classic {@code ObjectMapper} — such as
 * {@code NowsDataItemsAssembler} — fails application context startup.
 */
@Configuration
public class CatalogueConfiguration {

    @Bean
    public Catalogue catalogue(final CatalogueLoader loader) {
        return loader.load();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
