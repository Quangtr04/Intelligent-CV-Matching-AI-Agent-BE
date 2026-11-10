package com.example.intelligent_cv_matching_ai_agent.config.shared.resume;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.storage")
public class ResumeStorageProperties {

    private String provider = "local";

    private String path = "./storage/resumes";

    private Firebase firebase = new Firebase();

    @Getter
    @Setter
    public static class Firebase {

        private boolean enabled;

        private String bucket = "";

        private String credentialsPath = "";

        private String credentialsJson = "";

        private String prefix = "resumes";

        @Override
        public String toString() {
            return "Firebase[enabled=" + enabled + ", bucket=" + bucket + ", prefix=" + prefix + "]";
        }
    }
}
