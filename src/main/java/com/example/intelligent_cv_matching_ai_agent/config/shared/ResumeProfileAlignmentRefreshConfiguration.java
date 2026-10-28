package com.example.intelligent_cv_matching_ai_agent.config.shared;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class ResumeProfileAlignmentRefreshConfiguration {

    @Bean("taskScheduler")
    @ConditionalOnMissingBean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("app-scheduler-");
        return scheduler;
    }

    @Bean("resumeProfileAlignmentScheduler")
    public ThreadPoolTaskScheduler resumeProfileAlignmentScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("cv-profile-refresh-");
        return scheduler;
    }
}
