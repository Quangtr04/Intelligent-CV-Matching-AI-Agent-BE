package com.example.intelligent_cv_matching_ai_agent.ai;

import com.example.intelligent_cv_matching_ai_agent.repository.admin.rag.RagDocumentRepository;

import com.example.intelligent_cv_matching_ai_agent.service.admin.rag.RagDocumentService;

import com.example.intelligent_cv_matching_ai_agent.service.shared.ai.AiService;

import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagDocumentServiceTests {

    private final SqlStore db = mock(SqlStore.class);

    private final CurrentUser user = mock(CurrentUser.class);

    private final AiService ai = mock(AiService.class);

    private final RagDocumentService service = new RagDocumentService(new RagDocumentRepository(db), user, ai);

    @Test
    void chunksPreserveEveryCharacterAndOverlapWithoutInventingContent() {
        String text = "Java PostgreSQL ".repeat(1000);
        List<String> chunks = RagDocumentService.split(text);
        assertEquals(text.substring(0, 4000), chunks.getFirst());
        StringBuilder rebuilt = new StringBuilder(chunks.getFirst());
        for (int i = 1; i < chunks.size(); i++) {
            assertEquals(
                chunks.get(i - 1).substring(chunks.get(i - 1).length() - 300),
                chunks.get(i).substring(0, 300)
            );
            rebuilt.append(chunks.get(i).substring(300));
        }
        assertEquals(text, rebuilt.toString());
        assertTrue(chunks.stream().allMatch(c -> c.length() <= 4000));
    }

    @Test
    void disabledProviderCannotClaimAnIndexedDocument() {
        UUID id = UUID.randomUUID();
        when(db.one("SELECT * FROM rag_documents WHERE id=?", id)).thenReturn(Map.of("id", id, "metadata", Map.of()));
        when(ai.embeddingsEnabled()).thenReturn(false);
        assertThrows(ApiException.class, () -> service.index(id));
        verify(db, never()).update(anyString(), any());
    }

    @Test
    void archivedDocumentCannotBeRevivedByIndexing() {
        UUID id = UUID.randomUUID();
        when(db.one("SELECT * FROM rag_documents WHERE id=?", id))
            .thenReturn(Map.of("id", id, "metadata", Map.of("disabled", true)));
        assertThrows(ApiException.class, () -> service.index(id));
        verifyNoInteractions(ai);
    }

    @Test
    void importRejectsMissingOrOversizedContentBeforeAnyWrites() {
        assertThrows(ApiException.class, () -> service.create(Map.of("title", "Document", "content", " ")));
        assertThrows(
            ApiException.class,
            () -> service.create(Map.of("title", "Document", "content", "x".repeat(100001)))
        );
        verify(db, never()).insert(anyString(), anyMap());
    }

    @Test
    void providerFailureRemainsVisibleAndRetryResumesOnlyUnindexedChunks() {
        UUID id = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID(), job = UUID.randomUUID();
        when(user.id()).thenReturn(UUID.randomUUID());
        when(db.json(any())).thenReturn(new SqlStore.JsonValue("{}"));
        when(db.one("SELECT * FROM rag_documents WHERE id=?", id)).thenReturn(Map.of("id", id, "metadata", Map.of()));
        when(db.one("SELECT metadata FROM rag_documents WHERE id=?", id)).thenReturn(Map.of("metadata", Map.of()));
        when(ai.embeddingsEnabled()).thenReturn(true);
        when(ai.provider()).thenReturn("openai");
        when(ai.embeddingModel()).thenReturn("embedding-v1");
        when(db.update(startsWith("UPDATE rag_documents SET embedding_status='PROCESSING'"), eq(id))).thenReturn(1);
        when(db.update(startsWith("UPDATE rag_documents SET embedding_status='COMPLETED'"), eq(2), eq(id)))
            .thenReturn(1);
        when(db.list("SELECT * FROM rag_chunks WHERE document_id=? ORDER BY chunk_index", id))
            .thenReturn(List.of(Map.of("id", first, "content", "cached"), Map.of("id", second, "content", "Java")));
        when(
            db.optional(
                "SELECT id FROM rag_chunk_embeddings WHERE chunk_id=? AND provider=? AND model=? "
                    + "AND embedding IS NOT NULL",
                first,
                "openai",
                "embedding-v1"
            )
        ).thenReturn(Optional.of(Map.of("id", UUID.randomUUID())));
        when(db.insert(eq("vector_indexing_jobs"), anyMap())).thenReturn(job);
        when(
            db.scalar(
                "SELECT count(*) FROM rag_chunk_embeddings WHERE provider=? AND model=? AND embedding IS NOT NULL",
                "openai",
                "embedding-v1"
            )
        ).thenReturn(2L);
        when(ai.embed("Java")).thenThrow(new ApiException(502, "Provider failed")).thenReturn(new float[1024]);
        assertThrows(ApiException.class, () -> service.index(id));
        verify(db).update("UPDATE rag_documents SET embedding_status='FAILED' WHERE id=?", id);
        verify(db).update(
            ("UPDATE vector_indexing_jobs SET status='FAILED',error_message=?," + "completed_at=now() WHERE id=?"),
            "Provider failed",
            job
        );
        assertEquals(id, service.index(id).get("id"));
        verify(ai, never()).embed("cached");
        verify(ai, times(2)).embed("Java");
        verify(db).update("UPDATE vector_indexing_jobs SET status='COMPLETED',completed_at=now() WHERE id=?", job);
    }

    @Test
    void chunkBoundariesDoNotSplitSurrogatePairs() {
        List<String> chunks = RagDocumentService.split("a".repeat(3999) + "😀" + "b".repeat(8000));
        for (String chunk : chunks) {
            assertFalse(Character.isLowSurrogate(chunk.charAt(0)));
            assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)));
            assertTrue(chunk.length() <= 4000);
        }
    }
}
