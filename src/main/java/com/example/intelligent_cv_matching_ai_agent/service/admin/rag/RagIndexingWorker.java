package com.example.intelligent_cv_matching_ai_agent.service.admin.rag;

import com.example.intelligent_cv_matching_ai_agent.repository.admin.rag.RagDocumentRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Consumes committed pending documents without depending on an HTTP security context. */
@Component
public class RagIndexingWorker {

    private static final Logger LOG = LoggerFactory.getLogger(RagIndexingWorker.class);

    private final RagDocumentRepository db;

    private final RagDocumentService documents;

    public RagIndexingWorker(
        RagDocumentRepository db,
        RagDocumentService documents) {
        this.db = db;
        this.documents = documents;
    }

    @Scheduled(
        initialDelayString = "${app.rag.indexing.initial-delay-ms:10000}",
        fixedDelayString = "${app.rag.indexing.delay-ms:5000}"
    )
    public void indexPending() {
        for (var document : db.pendingDocuments()) {
            UUID id = (UUID) document.get("id");
            try {
                documents.indexAs(id, (UUID) document.get("uploaded_by"));
            } catch (RuntimeException ex) {
                LOG.warn(
                    "Background RAG indexing failed: document={}, errorType={}",
                    id,
                    ex.getClass().getSimpleName()
                );
            }
        }
    }
}
