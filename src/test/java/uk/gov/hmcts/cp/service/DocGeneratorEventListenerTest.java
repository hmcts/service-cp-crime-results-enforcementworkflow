package uk.gov.hmcts.cp.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.entity.EnforcementResultNotificationEntity;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Feature 005 (research.md R2, plan.md D8): matches sourceCorrelationId/originatingSource against this feature's own
 * row; anything else is ignored (another service's correlation id could coincidentally share the topic).
 */
class DocGeneratorEventListenerTest {

    private static final UUID ID = UUID.randomUUID();

    private final ResultNotificationStore store = mock(ResultNotificationStore.class);
    private final ResultNotificationTasks tasks = mock(ResultNotificationTasks.class);
    private final DocGeneratorEventListener listener = new DocGeneratorEventListener(store, tasks, JsonMapper.builder().build());

    @Test
    void document_available_should_mark_ready_and_queue_the_email_dispatch() {
        when(store.find(ID)).thenReturn(Optional.of(new EnforcementResultNotificationEntity()));

        listener.handle("public.systemdocgenerator.events.document-available", body(ID));

        verify(store).markDocReady(ID);
        verify(tasks).queueDispatchEmail(ID);
    }

    // FR-020: queued alongside, never instead of, the email dispatch
    @Test
    void document_available_should_also_queue_the_case_material_upload() {
        when(store.find(ID)).thenReturn(Optional.of(new EnforcementResultNotificationEntity()));

        listener.handle("public.systemdocgenerator.events.document-available", body(ID));

        verify(tasks).queueUploadCaseMaterial(ID);
    }

    @Test
    void generation_failed_should_mark_doc_failed_with_the_reason() {
        when(store.find(ID)).thenReturn(Optional.of(new EnforcementResultNotificationEntity()));

        listener.handle("public.systemdocgenerator.events.generation-failed",
                "{\"sourceCorrelationId\":\"" + ID + "\",\"originatingSource\":\"" + DocGeneratorEventListener.ORIGINATING_SOURCE
                        + "\",\"reason\":\"TEMPLATE_NOT_FOUND\"}");

        verify(store).markDocFailed(ID, "TEMPLATE_NOT_FOUND");
    }

    @Test
    void mismatched_originating_source_should_be_ignored() {
        listener.handle("public.systemdocgenerator.events.document-available",
                "{\"sourceCorrelationId\":\"" + ID + "\",\"originatingSource\":\"some-other-service\"}");

        verifyNoInteractions(store, tasks);
    }

    @Test
    void missing_source_correlation_id_should_be_ignored() {
        listener.handle("public.systemdocgenerator.events.document-available",
                "{\"originatingSource\":\"" + DocGeneratorEventListener.ORIGINATING_SOURCE + "\"}");

        verifyNoInteractions(store, tasks);
    }

    @Test
    void unknown_row_id_should_be_ignored() {
        when(store.find(ID)).thenReturn(Optional.empty());

        listener.handle("public.systemdocgenerator.events.document-available", body(ID));

        verify(store, never()).markDocReady(any());
        verify(tasks, never()).queueDispatchEmail(any());
        verify(tasks, never()).queueUploadCaseMaterial(any());
    }

    @Test
    void unrecognised_cppname_should_be_ignored() {
        when(store.find(ID)).thenReturn(Optional.of(new EnforcementResultNotificationEntity()));

        listener.handle("public.systemdocgenerator.events.something-else", body(ID));

        verify(store, never()).markDocReady(any());
        verify(store, never()).markDocFailed(any(), any());
    }

    private static String body(final UUID id) {
        return "{\"sourceCorrelationId\":\"" + id + "\",\"originatingSource\":\"" + DocGeneratorEventListener.ORIGINATING_SOURCE + "\"}";
    }
}
