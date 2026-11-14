package com.example.intelligent_cv_matching_ai_agent.service.admin.rag;

import com.example.intelligent_cv_matching_ai_agent.repository.admin.rag.RagDocumentRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class RagIndexingWorkerTests {

    @Test
    void processesPendingDocumentsWithStoredActorAndContinuesAfterFailure() {
        var db = mock(RagDocumentRepository.class);
        var documents = mock(RagDocumentService.class);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        when(db.pendingDocuments())
            .thenReturn(List.of(Map.of("id", first, "uploaded_by", actor), Map.of("id", second, "uploaded_by", actor)));
        when(documents.indexAs(first, actor)).thenThrow(new IllegalStateException("Provider failed"));

        new RagIndexingWorker(db, documents).indexPending();

        verify(documents).indexAs(first, actor);
        verify(documents).indexAs(second, actor);
    }
}
