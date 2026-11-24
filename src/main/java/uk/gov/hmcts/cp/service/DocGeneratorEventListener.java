package uk.gov.hmcts.cp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

/**
 * Handles {@code public.systemdocgenerator.events.document-available}/{@code generation-failed} (feature 005,
 * research.md R2, plan.md D8), delegated here by {@link uk.gov.hmcts.cp.messaging.PublicEventLoggingListener}'s
 * single, broadened-selector subscription on the CP {@code public.event} topic (not a second durable subscription
 * of its own - two containers each needing the service-wide {@code spring.jms.client-id} fails with
 * {@code InvalidClientIDException}). Matches {@code sourceCorrelationId} against this feature's own row id and
 * {@code originatingSource} against this service's own identifier; anything else is ignored (another service's
 * correlation id could coincidentally share the topic).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocGeneratorEventListener {

    /* default */ static final String ORIGINATING_SOURCE = "service-cp-crime-results-enforcementworkflow";
    private static final String CPPNAME_DOCUMENT_AVAILABLE = "public.systemdocgenerator.events.document-available";
    private static final String CPPNAME_GENERATION_FAILED = "public.systemdocgenerator.events.generation-failed";

    private final ResultNotificationStore store;
    private final ResultNotificationTasks tasks;
    private final ObjectMapper objectMapper;

    public void handle(final String cppname, final String body) {
        final DocGeneratorEvent event = objectMapper.readValue(body, DocGeneratorEvent.class);
        if (!ORIGINATING_SOURCE.equals(event.originatingSource()) || event.sourceCorrelationId() == null) {
            return;
        }
        final Optional<UUID> id = Optional.of(UUID.fromString(event.sourceCorrelationId()))
                .filter(rowId -> store.find(rowId).isPresent());
        id.ifPresent(rowId -> {
            if (CPPNAME_DOCUMENT_AVAILABLE.equals(cppname)) {
                store.markDocReady(rowId);
                tasks.queueDispatchEmail(rowId);
            } else if (CPPNAME_GENERATION_FAILED.equals(cppname)) {
                store.markDocFailed(rowId, event.reason());
            } else {
                log.warn("Unrecognised systemdocgenerator event {} for {}", cppname, rowId);
            }
        });
    }

    /* default */ record DocGeneratorEvent(String sourceCorrelationId, String originatingSource, String destinationFileUri,
                                           String reason) {
    }
}
