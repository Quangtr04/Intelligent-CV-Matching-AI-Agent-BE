package com.example.intelligent_cv_matching_ai_agent.util.shared.ai;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeProfileAlignment;
import com.example.intelligent_cv_matching_ai_agent.util.candidate.resume.ResumeWritingQuality;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Defines and validates the structured responses accepted from AI providers. */
public final class AiResponseData {

    private AiResponseData() {
    }

    public static String schema(
        String feature) {
        return switch (feature) {
            case "CV_BUILD",
                "CV_PARSE" -> ("{full_name:string,summary:string,experience:[{company_name,job_title,"
                    + "start_date:ISO_date_or_null,end_date:ISO_date_or_null,"
                    + "is_current:boolean,description}],education:[{school_name,degree,major,"
                    + "start_date,end_date,description}],projects:[{project_name,role,"
                    + "description,technologies:[string],project_url,start_date,end_date}],"
                    + "certifications:[{name,issuer,issue_date,expiry_date,credential_url}],"
                    + "skills:[{name,proficiency_level,years_experience}]}");
            case "CV_ANALYZE" -> ("{content_score:number_0_to_100,ats_score:number_0_to_100,"
                + "summary:string,strengths:[string],issues:[string],"
                + "suggestions:[{category,severity,original_content,suggested_content,"
                + "explanation}],content_quality:{rubric_version:cv-content-v1,"
                + "criteria:[{code:SKILL_RELEVANCE_or_EXPERIENCE_DEPTH_or_PROJECT_COMPLEX"
                + "ITY_or_ACHIEVEMENT_IMPACT_or_EDUCATION_RELEVANCE_or_CONTENT_COMPLETENE"
                + "SS,status:ASSESSED_or_NOT_APPLICABLE_or_INSUFFICIENT_EVIDENCE,"
                + "score:number_0_to_100_or_null,feedback:string,evidence:[string]}]},"
                + "writing_quality:{rubric_version:cv-writing-v1,"
                + "criteria:[{code:BULLET_STRUCTURE_or_PUNCTUATION_CONSISTENCY_or_PROFESS"
                + "IONAL_WORDING_or_CONCISENESS_or_ACHIEVEMENT_CLARITY,"
                + "status:ASSESSED_or_NOT_APPLICABLE_or_INSUFFICIENT_EVIDENCE,"
                + "score:number_0_to_100_or_null,feedback:string,evidence:[string]}]," + "limitations:[string]}}");
            case "CV_PROFILE_ALIGNMENT" -> ("{rubric_version:cv-profile-alignment-v1,"
                + "criteria:[{code:TARGET_ROLE_or_CAREER_GOAL_or_SKILLS_or_EXPERIENCE_or_"
                + "LOCATION_or_WORKPLACE_or_EMPLOYMENT_TYPE,"
                + "status:ASSESSED_or_MISSING_CV_EVIDENCE_or_INSUFFICIENT_EVIDENCE_or_NOT"
                + "_APPLICABLE,score:number_0_to_100_or_null,feedback:string,"
                + "profile_evidence:[string],cv_evidence:[string],suggestions:[string]}]}");
            case "JOB_MATCH_RERANK" -> ("{results:[{job_id:uuid,score:number_0_to_1,reason:string,"
                + "strengths:[string],skill_gaps:[string]}]}");
            case "JOB_REQUIREMENT_EVIDENCE" -> ("{results:[{requirement_id:string,"
                + "status:MET_or_PARTIAL_or_NOT_MET_or_MISSING_EVIDENCE_or_UNVERIFIED,"
                + "reason:string,evidence:[{chunk_id:string,quote:string}]}]}");
            case "JD_SKILL_PROFICIENCY" -> ("{results:[{skill_id:string,required_level:integer_0_to_4_or_null,"
                + "evidence:string,reason:string,confidence:number_0_to_1}]}");
            case "JOB_ATS" -> ("{ats_score:number_0_to_100,strengths:[string],missing_skills:[string],"
                + "improvement_suggestions:[string]}");
            case "ASSESSMENT_GENERATE" -> ("{questions:[{part:1_or_2,question_text:string,options:{A:string,"
                + "B:string,C:string,D:string},correct_option:A_B_C_or_D,"
                + "related_skill_name:string,jd_evidence:string,cv_evidence:string," + "explanation:string}]}");
            case "ROADMAP_GENERATE" -> ("{title:string,estimated_total_hours:integer,summary:string,"
                + "nodes:[{skill_name:string,description:string,estimated_hours:integer,"
                + "prerequisites:[integer_sequence_order]}]}");
            case "APPLICATION_EMAIL" -> "{subject:string,email:string,cover_letter:string}";
            case "CAREER_CHAT" -> "{answer:string}";
            case "CV_AI_SUPPORT" -> "{answer:string,suggestions:[{original_content,suggested_content,explanation}]}";
            case "SALARY_ESTIMATE" -> "{estimated_min:number,estimated_max:number,currency:string,reasoning:string}";
            default -> throw ApiException.badRequest("Unsupported AI feature");
        };
    }

    public static String requestedLanguage(
        Map<String, Object> input) {
        Object value = input == null ? null : input.get("language");
        if (value == null)
            return "vi";

        String language = Objects.toString(value, "").toLowerCase(Locale.ROOT);

        return Set.of("vi", "en").contains(language) ? language : "vi";
    }

    public static Object outputFormat(
        String feature) {
        return outputFormat(feature, Map.of());
    }

    public static Object outputFormat(
        String feature,
        Map<String, Object> input) {
        return switch (feature) {
            case "CV_ANALYZE" -> CvWritingResponseSchema.schema(input);
            case "CV_AI_SUPPORT" -> CvSupportResponseSchema.schema(input);
            case "CV_PROFILE_ALIGNMENT" -> CvProfileAlignmentResponseSchema.schema(input);
            case "ASSESSMENT_GENERATE", "ROADMAP_GENERATE" -> LearningResponseSchema.schema(feature, input);
            case "JOB_MATCH_RERANK" -> JobMatchResponseSchema.schema(input);
            case "JOB_REQUIREMENT_EVIDENCE" -> RequirementEvidenceResponseSchema.schema(input);
            case "APPLICATION_EMAIL" -> ApplicationWritingResponseSchema.schema();
            case "CAREER_CHAT" -> Map.of(
                "type",
                "object",
                "properties",
                Map.of("answer", Map.of("type", "string", "minLength", 1)),
                "required",
                List.of("answer"),
                "additionalProperties",
                false
            );
            default -> "json";
        };
    }

    public static void validate(
        String feature,
        Map<String, Object> result) {
        if (result == null || result.isEmpty())
            throw new IllegalArgumentException("Empty AI response");

        switch (feature) {
            case "CV_BUILD", "CV_PARSE" -> {
                for (String k : List.of("experience", "education", "projects", "certifications", "skills"))
                    array(result, k);
            }
            case "CV_ANALYZE" -> {
                ResumeWritingQuality.validateModelResponse(result);
                score(result, "ats_score", 100);
                string(result, "summary");
                array(result, "strengths");
                array(result, "issues");
                array(result, "suggestions");
            }
            case "CV_AI_SUPPORT" -> CvSupportResponseSchema.validate(result);
            case "CV_PROFILE_ALIGNMENT" -> ResumeProfileAlignment.validateModelResponse(result);
            case "JOB_MATCH_RERANK", "JOB_REQUIREMENT_EVIDENCE", "JD_SKILL_PROFICIENCY" -> array(result, "results");
            case "JOB_ATS" -> {
                score(result, "ats_score", 100);
                array(result, "strengths");
                array(result, "missing_skills");
                array(result, "improvement_suggestions");
            }
            case "ASSESSMENT_GENERATE" -> array(result, "questions");
            case "ROADMAP_GENERATE" -> {
                string(result, "title");
                array(result, "nodes");
            }
            case "APPLICATION_EMAIL" -> {
                string(result, "subject");
                string(result, "email");
                string(result, "cover_letter");
            }
            case "CAREER_CHAT" -> string(result, "answer");
            case "SALARY_ESTIMATE" -> {
                score(result, "estimated_min", 1e15);
                score(result, "estimated_max", 1e15);
                string(result, "currency");
            }
            default -> throw new IllegalArgumentException("Unsupported feature");
        }
    }

    private static void array(
        Map<String, Object> result,
        String key) {
        if (!(result.get(key) instanceof List<?> list) || list.size() > 200)
            throw new IllegalArgumentException("Invalid " + key);
    }

    private static void string(
        Map<String, Object> result,
        String key) {
        if (!(result.get(key) instanceof String s) || s.isBlank())
            throw new IllegalArgumentException("Invalid " + key);
    }

    private static void score(
        Map<String, Object> result,
        String key,
        double max) {
        if (!(result.get(key) instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() < 0
            || n.doubleValue() > max)
            throw new IllegalArgumentException("Invalid " + key);
    }
}
