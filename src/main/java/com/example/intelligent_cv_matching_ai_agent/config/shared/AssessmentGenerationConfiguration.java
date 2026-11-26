package com.example.intelligent_cv_matching_ai_agent.config.shared;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class AssessmentGenerationConfiguration {

    @Bean("assessmentGenerationScheduler")
    public ThreadPoolTaskScheduler assessmentGenerationScheduler() {
        return generationScheduler("assessment-generation-");
    }

    @Bean("roadmapGenerationScheduler")
    public ThreadPoolTaskScheduler roadmapGenerationScheduler() {
        return generationScheduler("roadmap-generation-");
    }

    private ThreadPoolTaskScheduler generationScheduler(
        String threadPrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadPrefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
