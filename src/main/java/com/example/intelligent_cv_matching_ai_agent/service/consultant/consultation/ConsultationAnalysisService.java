package com.example.intelligent_cv_matching_ai_agent.service.consultant.consultation;

import com.example.intelligent_cv_matching_ai_agent.dto.request.consultant.consultation.ConsultationAnalysisRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.response.consultant.consultation.ConsultationAnalysesResponse;
import com.example.intelligent_cv_matching_ai_agent.repository.consultant.consultation.ConsultationAnalysisRepository;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation.ConsultationRepository;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeWritingQuality;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConsultationAnalysisService {

    private final ConsultationAnalysisRepository repository;

    private final ConsultationRepository consultations;

    private final CurrentUser currentUser;

    public ConsultationAnalysisService(
        ConsultationAnalysisRepository repository,
        ConsultationRepository consultations,
        CurrentUser currentUser) {
        this.repository = repository;
        this.consultations = consultations;
        this.currentUser = currentUser;
    }

    @Transactional
    public ConsultationAnalysesResponse candidateAnalyses(
        UUID consultationId,
        ConsultationAnalysisRequest request) {
        UUID candidateId = repository.candidateIdForThread(consultationId, currentUser.id());
        UUID resumeId = request.resumeId();
        UUID jobId = request.jobId();
        int limit = request.limit();
        int offset = request.offset();

        if (resumeId != null) {
            consultations.getResumesById(resumeId, candidateId);
        }

        return new ConsultationAnalysesResponse(
            candidateId,
            resumeId,
            jobId,
            limit,
            offset,
            repository.listResumeAnalyses(candidateId, resumeId, limit, offset).stream()
                .map(ResumeWritingQuality::expose).toList(),
            repository.listAtsAnalyses(candidateId, resumeId, jobId, limit, offset),
            repository.listMatchingResults(candidateId, resumeId, jobId, limit, offset),
            repository.listSalaryEstimations(candidateId, jobId, limit, offset),
            repository.listCompletedAssessments(candidateId, jobId, limit, offset),
            repository.listGapAnalyses(candidateId, resumeId, jobId, limit, offset),
            repository.listRoadmaps(candidateId, resumeId, jobId, limit, offset)
        );
    }
}
