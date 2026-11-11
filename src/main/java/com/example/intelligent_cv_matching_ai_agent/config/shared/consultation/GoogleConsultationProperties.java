package com.example.intelligent_cv_matching_ai_agent.config.shared.consultation;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** One organizer authorizes both Meet spaces and Calendar invitations. Never include credentials in toString. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.consultation.google")
public class GoogleConsultationProperties {

    private boolean enabled;

    private String clientId = "";

    private String clientSecret = "";

    private String refreshToken = "";

    private String calendarId = "primary";

    private String timeZone = "Asia/Ho_Chi_Minh";

    private String meetingAccessType = "OPEN";

    private Duration requestTimeout = Duration.ofSeconds(20);
}
