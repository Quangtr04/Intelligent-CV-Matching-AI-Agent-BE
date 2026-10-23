package com.example.intelligent_cv_matching_ai_agent.service.shared.identity;

import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.profile.CareerProfileRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.candidate.profile.CareerSkillRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.PasswordChangeRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.shared.identity.PersonalProfileRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.response.shared.identity.PersonalProfileResponse;
import com.example.intelligent_cv_matching_ai_agent.service.candidate.resume.ResumeProfileAlignmentRefreshService;

import org.springframework.stereotype.Service;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.identity.ProfileRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProfileService {

    private final ProfileRepository db;

    private final CurrentUser user;

    private final TokenService tokens;

    private final PasswordEncoder encoder;

    private final ResumeProfileAlignmentRefreshService alignmentRefresh;

    public ProfileService(
        ProfileRepository db,
        CurrentUser user,
        TokenService tokens,
        PasswordEncoder encoder,
        ResumeProfileAlignmentRefreshService alignmentRefresh) {
        this.db = db;
        this.user = user;
        this.tokens = tokens;
        this.encoder = encoder;
        this.alignmentRefresh = alignmentRefresh;
    }

    public PersonalProfileResponse me() {
        return PersonalProfileResponse.from(db.getProfile(user.id()));
    }

    public PersonalProfileResponse update(
        PersonalProfileRequest body) {
        db.updateUsersById(
            body.fullName().trim(),
            body.avatarUrl(),
            body.preferredLanguage(),
            body.githubUrl(),
            user.id()
        );

        return me();
    }

    public Map<String, Object> career() {
        Map<String, Object> result =
            new LinkedHashMap<>(db.findCandidateProfilesByUserId(user.id()).orElse(Map.of("user_id", user.id())));
        result.remove("expected_salary_min");
        result.remove("expected_salary_max");
        result.put("skills", db.listCandidateProfileSkillsByUserId(user.id()));

        return result;
    }

    @Transactional
    public Map<String, Object> careerUpdate(
        CareerProfileRequest body) {
        UUID owner = user.id();
        // Serialize concurrent saves before reading the previous snapshot, including first-profile creation.
        db.lockCandidateProfileOwner(owner);
        String previousProfile = alignmentRefresh.profileFingerprint(owner);

        db.updateCandidateProfiles(owner);
        db.updateCandidateProfilesByUserId(
            body.currentJobTitle(),
            body.currentCompany(),
            body.targetJobTitle(),
            body.careerGoal(),
            body.yearsExperience(),
            body.expectedSalary(),
            body.salaryCurrency(),
            body.preferredLocation(),
            body.workplacePreference(),
            body.employmentType(),
            body.profileSummary(),
            db.json(body.preferences()),
            owner
        );

        if (body.skills() != null) {
            if (body.skills().stream().map(CareerSkillRequest::skillId).distinct().count() != body.skills().size())
                throw ApiException.badRequest("Duplicate skill IDs");

            db.deleteCandidateProfileSkillsByUserId(owner);

            for (CareerSkillRequest skill : body.skills()) {
                db.getSkillsById(skill.skillId());

                db.updateCandidateProfileSkills(
                    owner,
                    skill.skillId(),
                    skill.proficiencyLevel(),
                    skill.yearsExperience()
                );
            }
        }

        db.updateCareerProfileEmbeddingsByUserId(owner);
        db.updateRoadmapsByUserId(owner);
        alignmentRefresh.profileUpdated(owner, previousProfile);

        return career();
    }

    @Transactional
    public Map<String, Object> deleteAccount() {
        // Role updates and deletions share this lock; use the current DB role, not a
        // potentially older bearer context, when protecting the last administrator.
        db.readDatabaseValue();
        Map<String, Object> currentAccount = db.getUsersById2(user.id());

        if ("ADMIN".equals(currentAccount.get("role"))) {
            List<Map<String, Object>> administrators = db.listUsersByRoleForUpdate();
            if (administrators.stream().noneMatch(account -> !user.id().equals(account.get("id"))))
                throw ApiException.conflict("At least one active administrator is required");
        }

        db.updateUsersById2(user.id());
        tokens.revokeAll(user.id());

        return Map.of("message", "Account closed");
    }

    @Transactional
    public Map<String, Object> password(
        PasswordChangeRequest body) {
        if (body.newPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
            || body.currentPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw ApiException.badRequest("Password must fit within 72 UTF-8 bytes");

        Map<String, Object> account = db.getUsersByIdForUpdate(user.id());
        if (!"EMAIL".equals(account.get("auth_provider")) || account.get("password_hash") == null)
            throw ApiException.badRequest("Manage your password through your sign-in provider");
        if (!encoder.matches(body.currentPassword(), account.get("password_hash").toString()))
            throw new ApiException(401, "Current password is incorrect");

        db.updateUsersById3(encoder.encode(body.newPassword()), user.id());
        tokens.revokeAll(user.id());

        return Map.of("message", "Password changed; log in again");
    }

    public List<Map<String, Object>> sessions() {
        return db.listRefreshTokensByUserId(user.id());
    }

    public Map<String, Object> revokeSession(
        UUID id) {
        db.getRefreshTokensById(id, user.id());

        db.updateRefreshTokensById(id, user.id());

        return Map.of("message", "Session revoked");
    }
}
