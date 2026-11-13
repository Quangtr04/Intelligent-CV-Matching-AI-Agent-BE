package com.example.intelligent_cv_matching_ai_agent.service.shared.jobs;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;

/** Opt-in maintenance at startup; each batch is committed through the proxied transactional service. */
@Component
@ConditionalOnProperty(name = "jobs.requirements.reprocess.enabled", havingValue = "true")
public class JobRequirementReprocessingRunner implements ApplicationRunner {

    private final JobRequirementReprocessor reprocessor;

    public JobRequirementReprocessingRunner(
        JobRequirementReprocessor reprocessor) {
        this.reprocessor = reprocessor;
    }

    @Override
    public void run(
        ApplicationArguments arguments) {
        int processed;
        int total = 0;
        do {
            processed = reprocessor.reprocessBatch(200);
            total += processed;
        } while (processed > 0);
        LoggerFactory.getLogger(getClass()).info("Reprocessed {} job requirement snapshots", total);
    }
}
