package com.example.intelligent_cv_matching_ai_agent.service.shared.consultation;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RatingAppealServiceTests {

    private final SqlStore db = mock(SqlStore.class);

    private final CurrentUser user = mock(CurrentUser.class);

    private final RatingAppealService service = new RatingAppealService(db, user);

    @Test
    void evidenceRequiresRealGoogleDriveHttpsHost() {
        assertTrue(RatingAppealService.validEvidenceUrl("https://drive.google.com/file/d/abc/view"));
        for (String value : new String[]{
            "http://drive.google.com/file/d/x",
            "https://drive.google.com.evil.test/x",
            "https://drive.google.com@evil.test/x",
            "javascript:alert(1)",
            "https://drive.google.com"}) {
            assertFalse(RatingAppealService.validEvidenceUrl(value), value);
        }
    }

    @Test
    void acceptedAppealRemovesRatingInsteadOfAddingArtificialStars() {
        UUID id = UUID.randomUUID();
        UUID rating = UUID.randomUUID();
        UUID reviewer = UUID.randomUUID();
        when(user.id()).thenReturn(reviewer);
        when(db.one("SELECT * FROM consultation_rating_appeals WHERE id=? FOR UPDATE", id))
            .thenReturn(Map.of("status", "PENDING", "rating_id", rating, "consultant_user_id", UUID.randomUUID()));
        service.decide(id, "ACCEPTED", "Video confirms that the review is inaccurate");
        verify(user).requireOperationsRole();
        verify(db).update("UPDATE consultation_ratings SET is_removed=true WHERE id=?", rating);
    }

    @Test
    void rejectedAppealDoesNotRemoveRating() {
        UUID id = UUID.randomUUID();
        when(user.id()).thenReturn(UUID.randomUUID());
        when(db.one("SELECT * FROM consultation_rating_appeals WHERE id=? FOR UPDATE", id))
            .thenReturn(Map.of("status", "PENDING", "consultant_user_id", UUID.randomUUID()));
        service.decide(id, "REJECTED", "Evidence does not support the appeal");
        verify(db, never()).update(eq("UPDATE consultation_ratings SET is_removed=true WHERE id=?"), any());
    }

    @Test
    void resolvedAppealCannotBeReviewedTwice() {
        UUID id = UUID.randomUUID();
        when(db.one("SELECT * FROM consultation_rating_appeals WHERE id=? FOR UPDATE", id))
            .thenReturn(Map.of("status", "ACCEPTED"));
        assertThrows(ApiException.class, () -> service.decide(id, "REJECTED", "Second decision"));
        verify(db, never()).update(anyString(), any());
    }
}
