package com.example.intelligent_cv_matching_ai_agent.ai;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.rag.CourseLinkRequest;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.admin.rag.RagCourseRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.service.admin.rag.RagCourseService;
import com.example.intelligent_cv_matching_ai_agent.service.admin.rag.RagDocumentService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagCourseServiceTests {

    private final RagCourseRepository db = mock(RagCourseRepository.class);

    private final CurrentUser user = mock(CurrentUser.class);

    private final RagDocumentService rag = mock(RagDocumentService.class);

    private final RagCourseService service = new RagCourseService(db, user, rag);

    private final UUID courseId = UUID.randomUUID();

    private final UUID actorId = UUID.randomUUID();

    @Test
    @SuppressWarnings("unchecked")
    void submissionCreatesOnlyPendingRecordWithCanonicalLink() {
        when(user.id()).thenReturn(actorId);
        when(db.create(anyMap())).thenReturn(Optional.of(courseId));
        when(db.get(courseId)).thenReturn(Map.of("id", courseId, "status", "PENDING_REVIEW"));
        Map<String, Object> created = service.create(request());
        assertEquals("PENDING_REVIEW", created.get("status"));
        ArgumentCaptor<Map<String, Object>> fields = ArgumentCaptor.forClass(Map.class);
        verify(db).create(fields.capture());
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", fields.getValue().get("url"));
        assertEquals(List.of("Java", "Spring Boot"), fields.getValue().get("skills"));
        assertEquals(actorId, fields.getValue().get("created_by_user_id"));
        assertFalse(fields.getValue().containsKey("status"));
        verify(db, never()).createLearningResource(anyMap());
        verifyNoInteractions(rag);
    }

    @Test
    void duplicateCanonicalLinkReturnsConflictWithoutPublishing() {
        when(user.id()).thenReturn(actorId);
        when(db.create(anyMap())).thenReturn(Optional.empty());
        assertEquals(409, assertThrows(ApiException.class, () -> service.create(request())).status());
        verify(db, never()).createLearningResource(anyMap());
        verifyNoInteractions(rag);
    }

    @Test
    @SuppressWarnings("unchecked")
    void approvalPublishesReviewedResourceAndIncludesUrlInRagContent() {
        UUID resourceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(user.id()).thenReturn(actorId);
        when(db.lock(courseId)).thenReturn(course("PENDING_REVIEW"));
        when(db.createLearningResource(anyMap())).thenReturn(Optional.of(resourceId));
        when(rag.create(anyMap())).thenReturn(Map.of("id", documentId));
        when(db.get(courseId)).thenReturn(Map.of("id", courseId, "status", "APPROVED"));

        assertEquals("APPROVED", service.approve(courseId).get("status"));
        ArgumentCaptor<Map<String, Object>> resource = ArgumentCaptor.forClass(Map.class);
        verify(db).createLearningResource(resource.capture());
        assertEquals("VIDEO", resource.getValue().get("resource_type"));
        Map<String, Object> metadata = (Map<String, Object>) resource.getValue().get("metadata_json");
        assertEquals(true, metadata.get("reviewed"));
        assertEquals(List.of("java", "spring-boot"), metadata.get("skill_slugs"));
        ArgumentCaptor<Map<String, Object>> document = ArgumentCaptor.forClass(Map.class);
        verify(rag).create(document.capture());
        assertTrue(document.getValue().get("content").toString().contains("https://www.youtube.com/watch?v="));
        assertTrue(document.getValue().get("content").toString().contains("Spring Boot"));
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            ((Map<String, Object>) document.getValue().get("metadata")).get("source_url")
        );
        var order = inOrder(db, rag);
        order.verify(db).lock(courseId);
        order.verify(db).createLearningResource(anyMap());
        order.verify(rag).create(anyMap());
        order.verify(db).approve(courseId, resourceId, documentId, actorId);
    }

    @Test
    void catalogCollisionDoesNotOverwriteExistingResourceOrCreateRagDocument() {
        when(db.lock(courseId)).thenReturn(course("PENDING_REVIEW"));
        when(db.createLearningResource(anyMap())).thenReturn(Optional.empty());
        assertEquals(409, assertThrows(ApiException.class, () -> service.approve(courseId)).status());
        verifyNoInteractions(rag);
        verify(db, never()).approve(any(), any(), any(), any());
    }

    @Test
    void approvedAndArchivedActionsAreIdempotentAndArchivedCannotBeApproved() {
        when(db.lock(courseId)).thenReturn(course("APPROVED"));
        service.approve(courseId);
        verify(db, never()).createLearningResource(anyMap());

        when(db.lock(courseId)).thenReturn(course("ARCHIVED"));
        service.archive(courseId);
        assertEquals(409, assertThrows(ApiException.class, () -> service.approve(courseId)).status());
        verify(db, never()).archive(courseId);
        verifyNoInteractions(rag);
    }

    @Test
    void archiveDisablesBothRecommendationChannelsUnderCourseLock() {
        UUID resourceId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        Map<String, Object> row = course("APPROVED");
        row.put("learning_resource_id", resourceId);
        row.put("rag_document_id", documentId);
        when(db.lock(courseId)).thenReturn(row);
        service.archive(courseId);
        var order = inOrder(db, rag);
        order.verify(db).lock(courseId);
        order.verify(db).deactivateResource(resourceId);
        order.verify(rag).archive(documentId);
        order.verify(db).archive(courseId);
    }

    @Test
    void pendingAndArchivedLinksCannotBeIndexed() {
        when(db.get(courseId)).thenReturn(course("PENDING_REVIEW"));
        assertEquals(409, assertThrows(ApiException.class, () -> service.index(courseId)).status());
        when(db.get(courseId)).thenReturn(course("ARCHIVED"));
        assertEquals(409, assertThrows(ApiException.class, () -> service.index(courseId)).status());
        verifyNoInteractions(rag);
    }

    @Test
    void indexingUsesApprovedDocumentAndPropagatesRetryableProviderError() {
        UUID documentId = UUID.randomUUID();
        Map<String, Object> row = course("APPROVED");
        row.put("rag_document_id", documentId);
        when(db.get(courseId)).thenReturn(row);
        when(rag.index(documentId)).thenThrow(new ApiException(503, "Provider disabled"));
        assertEquals(503, assertThrows(ApiException.class, () -> service.index(courseId)).status());
        verify(db, never()).archive(any());
        verify(db, never()).approve(any(), any(), any(), any());
    }

    @Test
    void validationRejectsUnsupportedLinksOrEmptySkillTagsBeforeWrites() {
        CourseLinkRequest valid = request();
        assertEquals(
            400,
            assertThrows(
                ApiException.class,
                () -> service.create(
                    new CourseLinkRequest(
                        valid.title(),
                        "https://evil.example/course",
                        valid.description(),
                        valid.skills(),
                        "en",
                        true
                    )
                )
            ).status()
        );
        assertEquals(
            400,
            assertThrows(
                ApiException.class,
                () -> service.create(
                    new CourseLinkRequest(valid.title(), valid.url(), valid.description(), List.of("---"), "en", true)
                )
            ).status()
        );
        verifyNoInteractions(db, rag);
    }

    @Test
    void nonOperationsUserCannotSubmitOrReviewLinks() {
        doThrow(ApiException.forbidden("Role required: ADMIN")).when(user).requireOperationsRole();
        assertEquals(403, assertThrows(ApiException.class, () -> service.create(request())).status());
        assertEquals(403, assertThrows(ApiException.class, () -> service.approve(courseId)).status());
        assertEquals(403, assertThrows(ApiException.class, () -> service.archive(courseId)).status());
        assertEquals(403, assertThrows(ApiException.class, () -> service.index(courseId)).status());
        assertEquals(403, assertThrows(ApiException.class, () -> service.list()).status());
        assertEquals(403, assertThrows(ApiException.class, () -> service.get(courseId)).status());
        verifyNoInteractions(db, rag);
    }

    private CourseLinkRequest request() {
        return new CourseLinkRequest(
            "Java course",
            "https://youtu.be/dQw4w9WgXcQ?si=tracking",
            "Practical Java and Spring course",
            List.of("Java", "Spring Boot", " java "),
            "en",
            true
        );
    }

    private Map<String, Object> course(
        String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", courseId);
        row.put("status", status);
        row.put("title", "Java course");
        row.put("url", "https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        row.put("provider", "YouTube");
        row.put("description", "Practical Java and Spring course");
        row.put("skills", List.of("Java", "Spring Boot"));
        row.put("language", "en");
        row.put("is_free", true);
        return row;
    }
}
