package com.example.intelligent_cv_matching_ai_agent.service.shared.announcement;

import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.announcement.AnnouncementRequest;
import com.example.intelligent_cv_matching_ai_agent.dto.request.admin.announcement.AnnouncementTargetRequest;
import com.example.intelligent_cv_matching_ai_agent.service.admin.monitoring.AuditService;

import org.springframework.stereotype.Service;
import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;
import com.example.intelligent_cv_matching_ai_agent.security.shared.CurrentUser;
import com.example.intelligent_cv_matching_ai_agent.repository.shared.announcement.AnnouncementRepository;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AnnouncementService {

    private final AnnouncementRepository db;

    private final CurrentUser user;

    private final AuditService audit;

    public AnnouncementService(
        AnnouncementRepository db,
        CurrentUser user,
        AuditService audit) {
        this.db = db;
        this.user = user;
        this.audit = audit;
    }

    public List<Map<String, Object>> visible() {
        return db.listAnnouncementsByStatus(user.id());
    }

    public List<Map<String, Object>> all() {
        user.requireOperationsRole();

        return db.listAnnouncements();
    }

    @Transactional
    public Map<String, Object> create(
        AnnouncementRequest body) {
        user.requireOperationsRole();

        OffsetDateTime now = OffsetDateTime.now();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("created_by_user_id", user.id());
        fields.put("title", body.title());
        fields.put("content", body.content());
        fields.put("status", "DRAFT");
        fields.put("expires_at", body.expiresAt());
        fields.put("created_at", now);
        fields.put("updated_at", now);

        UUID id = db.insertAnnouncements(fields);
        targets(id, body.targets());
        Map<String, Object> after = detail(id);

        audit.record("CREATE_ANNOUNCEMENT", "ANNOUNCEMENT", id, null, after);

        return after;
    }

    @Transactional
    public Map<String, Object> edit(
        UUID id,
        AnnouncementRequest body) {
        user.requireOperationsRole();

        Map<String, Object> before = db.getAnnouncementsByIdForUpdate(id);
        if (!"DRAFT".equals(before.get("status")))
            throw ApiException.conflict("Only draft announcements can be edited");

        db.updateAnnouncementsById(body.title(), body.content(), body.expiresAt(), id);
        db.deleteAnnouncementTargetsByAnnouncementId(id);
        targets(id, body.targets());
        Map<String, Object> after = detail(id);

        audit.record("UPDATE_ANNOUNCEMENT", "ANNOUNCEMENT", id, before, after);

        return after;
    }

    private void targets(
        UUID id,
        List<AnnouncementTargetRequest> targets) {
        for (AnnouncementTargetRequest target : targets) {
            if ("ROLE".equals(target.type()) && target.role() == null
                || "USER".equals(target.type()) && target.userId() == null)
                throw ApiException.badRequest("Announcement target fields do not match target type");

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("announcement_id", id);
            fields.put("target_type", target.type());

            if ("ROLE".equals(target.type()))
                fields.put("target_role", target.role());

            if ("USER".equals(target.type())) {
                db.getUsersById(target.userId());
                fields.put("target_user_id", target.userId());
            }

            db.insertAnnouncementTargets(fields);
        }
    }

    private Map<String, Object> detail(
        UUID id) {
        Map<String, Object> result = new LinkedHashMap<>(db.getAnnouncementsById(id));
        result.put("targets", db.listAnnouncementTargetsByAnnouncementId(id));

        return result;
    }

    @Transactional
    public Map<String, Object> publish(
        UUID id) {
        user.requireOperationsRole();

        Map<String, Object> before = db.getAnnouncementsByIdForUpdate2(id);
        if (!"DRAFT".equals(before.get("status")))
            throw ApiException.conflict("Announcement already published or archived");
        if (Boolean.TRUE.equals(before.get("is_expired")))
            throw ApiException.badRequest("Update the expiry before publishing this announcement");
        if (((Number) db.countAnnouncementTargetsByAnnouncementId(id)).intValue() == 0)
            throw ApiException.badRequest("Select an audience first");

        db.updateAnnouncementsById2(id);
        db.updateNotificationsById(id);

        Map<String, Object> after = detail(id);

        audit.record("PUBLISH_ANNOUNCEMENT", "ANNOUNCEMENT", id, before, after);

        return after;
    }

    @Transactional
    public Map<String, Object> archive(
        UUID id) {
        user.requireOperationsRole();

        Map<String, Object> before = db.getAnnouncementsById(id);

        db.updateAnnouncementsById3(id);

        audit.record("ARCHIVE_ANNOUNCEMENT", "ANNOUNCEMENT", id, before, Map.of("status", "ARCHIVED"));

        return Map.of("message", "Announcement archived");
    }

    public List<Map<String, Object>> notifications(
        boolean unreadOnly) {
        return db.listNotificationsByUserId(user.id(), unreadOnly);
    }

    public Map<String, Object> read(
        UUID id) {
        db.getNotificationsById(id, user.id());

        db.updateNotificationsById2(id, user.id());

        return Map.of("message", "Notification read");
    }

    public Map<String, Object> readAll() {
        db.updateNotificationsByUserId(user.id());

        return Map.of("message", "Notifications read");
    }
}
