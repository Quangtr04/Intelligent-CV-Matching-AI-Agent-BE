package com.example.intelligent_cv_matching_ai_agent.service.shared.consultation;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.repository.consultant.consultation.ConsultationAnalysisRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation.ConsultationBookingRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeWritingQuality;
import com.example.intelligent_cv_matching_ai_agent.util.consultant.consultation.ConsultationAttachments;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.fields;

/** Read-only preparation never generates reports, charges AI usage or substitutes a different CV. */
@Service
public class ConsultationPreparationService {

    private final ConsultationBookingRepository bookings;

    private final ConsultationAnalysisRepository analyses;

    private final CurrentUser user;

    public ConsultationPreparationService(
        ConsultationBookingRepository bookings,
        ConsultationAnalysisRepository analyses,
        CurrentUser user) {
        this.bookings = bookings;
        this.analyses = analyses;
        this.user = user;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> preparation(
        UUID id) {
        Map<String, Object> booking = bookings.booking(id);
        UUID viewer = user.id();
        if (!user.hasRole("ADMIN") && !viewer.equals(booking.get("candidate_user_id"))
            && !viewer.equals(booking.get("consultant_user_id"))) {
            throw ApiException.notFound("Booking not found");
        }
        UUID candidate = (UUID) booking.get("candidate_user_id");
        ConsultationAttachments.requireReadable(
            fields(
                "request_type",
                "SESSION_BOOKING",
                "candidate_user_id",
                candidate,
                "booking_status",
                booking.get("status"),
                "credit_refunded",
                booking.get("credit_refunded")
            ),
            viewer,
            user.hasRole("ADMIN")
        );
        UUID resumeId = (UUID) booking.get("resume_id");
        bookings.requireLiveCandidate(candidate);
        UUID jobId = (UUID) booking.get("job_id");
        Map<String, Object> resume =
            resumeId == null ? null : bookings.selectedResume(resumeId, candidate).orElse(null);
        Map<String, Object> job = jobId == null ? null : bookings.selectedJob(jobId).orElse(null);
        Map<String, Object> quality = null;
        Map<String, Object> ats = null;
        if (resume != null) {
            var qualityRows = analyses.listResumeAnalyses(candidate, resumeId, 1, 0);
            if (!qualityRows.isEmpty()) {
                quality = ResumeWritingQuality.expose(qualityRows.getFirst());
            }
            if (job != null) {
                var atsRows = analyses.listAtsAnalyses(candidate, resumeId, jobId, 1, 0);
                if (!atsRows.isEmpty()) {
                    ats = atsRows.getFirst();
                }
            }
        }
        return fields("resume", resume, "job", job, "resume_analysis", quality, "ats_analysis", ats);
    }
}
