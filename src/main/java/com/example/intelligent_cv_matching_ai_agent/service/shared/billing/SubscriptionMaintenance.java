package com.example.intelligent_cv_matching_ai_agent.service.shared.billing;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.billing.SubscriptionMaintenanceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Updates local subscription state and creates in-app notices; no external
 * messages are sent.
 */
@Service
public class SubscriptionMaintenance {

    private final SubscriptionMaintenanceRepository db;

    private final boolean enabled;

    private final int batchSize;

    public SubscriptionMaintenance(
        SubscriptionMaintenanceRepository db,
        @Value("${app.billing.maintenance-enabled:true}")
        boolean enabled,
        @Value("${app.billing.maintenance-batch-size:500}")
        int batchSize) {
        this.db = db;
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(batchSize, 1000));
    }

    @Scheduled(
        cron = "${app.billing.maintenance-cron:0 0 8 * * *}",
        zone = "${app.billing.maintenance-zone:Asia/Ho_Chi_Minh}"
    )
    @Transactional
    public void maintain() {
        if (!enabled)
            return;

        List<Map<String, Object>> expired = db.listUserSubscriptionsByStatusForUpdate(batchSize);

        for (Map<String, Object> subscription : expired)
            notifyOnce(
                subscription,
                "EXPIRED",
                "Subscription expired",
                "Your subscription has expired. Your account now uses the free tier."
            );

        List<Map<String, Object>> week = db.listUserSubscriptionsByStatusForUpdate2(batchSize);

        for (Map<String, Object> subscription : week)
            notifyOnce(
                subscription,
                "RENEWAL_7D",
                "Subscription renewal reminder",
                "Your subscription expires within seven days. Choose a plan to renew it."
            );

        List<Map<String, Object>> tomorrow = db.listUserSubscriptionsByStatusForUpdate3(batchSize);

        for (Map<String, Object> subscription : tomorrow)
            notifyOnce(
                subscription,
                "RENEWAL_1D",
                "Subscription expires soon",
                "Your subscription expires within one day. Renew your plan to keep its AI quota."
            );
    }

    boolean notifyOnce(
        Map<String, Object> subscription,
        String type,
        String title,
        String content) {
        int inserted =
            db.updateSubscriptionRenewalNotifications(subscription.get("user_id"), subscription.get("id"), type);
        if (inserted == 0)
            return false;

        db.insertNotifications(
            Map.of(
                "user_id",
                subscription.get("user_id"),
                "type",
                type,
                "title",
                title,
                "content",
                content,
                "reference_type",
                "SUBSCRIPTION",
                "reference_id",
                subscription.get("id"),
                "created_at",
                OffsetDateTime.now()
            )
        );

        return true;
    }
}
