package com.example.intelligent_cv_matching_ai_agent.repository.shared.announcement;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.SqlStore;
import java.util.*;
import org.springframework.stereotype.Repository;

/** SQL persistence operations used by AnnouncementService. */
@Repository
public class AnnouncementRepository {

    private final SqlStore db;

    public AnnouncementRepository(
        SqlStore db) {
        this.db = db;
    }

    public Object json(
        Object value) {
        return db.json(value);
    }

    public List<Map<String, Object>> listAnnouncementsByStatus(
        Object... args) {
        return db.list(
            ("SELECT DISTINCT a.* FROM announcements a JOIN announcement_targets t "
                + "ON t.announcement_id=a.id JOIN users u ON u.id=? WHERE "
                + "a.status='PUBLISHED' AND a.publish_at<=now() AND (a.expires_at IS "
                + "NULL OR a.expires_at>now()) AND (t.target_type='ALL' OR "
                + "(t.target_type='ROLE' AND t.target_role=u.role) OR "
                + "(t.target_type='USER' AND t.target_user_id=u.id)) ORDER BY " + "a.publish_at DESC LIMIT 100"),
            args
        );
    }

    public List<Map<String, Object>> listAnnouncements(
        Object... args) {
        return db.list("SELECT * FROM announcements ORDER BY created_at DESC LIMIT 100", args);
    }

    public UUID insertAnnouncements(
        Map<String, Object> fields) {
        return db.insert("announcements", fields);
    }

    public Map<String, Object> getAnnouncementsByIdForUpdate(
        Object... args) {
        return db.one("SELECT * FROM announcements WHERE id=? FOR UPDATE", args);
    }

    public int updateAnnouncementsById(
        Object... args) {
        return db.update("UPDATE announcements SET title=?,content=?,expires_at=?,updated_at=now() WHERE id=?", args);
    }

    public int deleteAnnouncementTargetsByAnnouncementId(
        Object... args) {
        return db.update("DELETE FROM announcement_targets WHERE announcement_id=?", args);
    }

    public Map<String, Object> getUsersById(
        Object... args) {
        return db.one("SELECT id FROM users WHERE id=? AND status='ACTIVE'", args);
    }

    public UUID insertAnnouncementTargets(
        Map<String, Object> fields) {
        return db.insert("announcement_targets", fields);
    }

    public Map<String, Object> getAnnouncementsById(
        Object... args) {
        return db.one("SELECT * FROM announcements WHERE id=?", args);
    }

    public List<Map<String, Object>> listAnnouncementTargetsByAnnouncementId(
        Object... args) {
        return db.list("SELECT * FROM announcement_targets WHERE announcement_id=?", args);
    }

    public Map<String, Object> getAnnouncementsByIdForUpdate2(
        Object... args) {
        return db.one(
            ("SELECT *, (expires_at IS NOT NULL AND expires_at<=now()) AS "
                + "is_expired FROM announcements WHERE id=? FOR UPDATE"),
            args
        );
    }

    public Object countAnnouncementTargetsByAnnouncementId(
        Object... args) {
        return db.scalar("SELECT count(*) FROM announcement_targets WHERE announcement_id=?", args);
    }

    public int updateAnnouncementsById2(
        Object... args) {
        return db.update(
            ("UPDATE announcements SET status='PUBLISHED',publish_at=now()," + "updated_at=now() WHERE id=?"),
            args
        );
    }

    public int updateNotificationsById(
        Object... args) {
        return db.update(
            ("INSERT INTO notifications(user_id,type,title,content,reference_type,"
                + "reference_id,created_at) SELECT DISTINCT u.id,'ANNOUNCEMENT',a.title,"
                + "a.content,'ANNOUNCEMENT',a.id,now() FROM announcements a JOIN "
                + "announcement_targets t ON t.announcement_id=a.id JOIN users u ON "
                + "u.status='ACTIVE' AND (t.target_type='ALL' OR (t.target_type='ROLE' "
                + "AND t.target_role=u.role) OR (t.target_type='USER' AND " + "t.target_user_id=u.id)) WHERE a.id=?"),
            args
        );
    }

    public int updateAnnouncementsById3(
        Object... args) {
        return db.update("UPDATE announcements SET status='ARCHIVED',updated_at=now() WHERE id=?", args);
    }

    public List<Map<String, Object>> listNotificationsByUserId(
        Object... args) {
        return db.list(
            ("SELECT * FROM notifications WHERE user_id=? AND (?=false OR "
                + "is_read=false) ORDER BY created_at DESC LIMIT 100"),
            args
        );
    }

    public Map<String, Object> getNotificationsById(
        Object... args) {
        return db.one("SELECT id FROM notifications WHERE id=? AND user_id=?", args);
    }

    public int updateNotificationsById2(
        Object... args) {
        return db.update(
            ("UPDATE notifications SET is_read=true,read_at=COALESCE(read_at,now()) " + "WHERE id=? AND user_id=?"),
            args
        );
    }

    public int updateNotificationsByUserId(
        Object... args) {
        return db.update(
            ("UPDATE notifications SET is_read=true,read_at=now() WHERE user_id=? " + "AND is_read=false"),
            args
        );
    }

}
