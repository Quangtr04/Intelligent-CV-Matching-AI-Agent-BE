
package com.example.intelligent_cv_matching_ai_agent;

import com.example.intelligent_cv_matching_ai_agent.config.shared.StartupStatusListener;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SpringBootApplication
public class IntelligentCvMatchingAiAgentApplication {

    private static final Logger log = LoggerFactory.getLogger(IntelligentCvMatchingAiAgentApplication.class);

    public static void main(
        String[] args) {
        SpringApplication app = new SpringApplication(IntelligentCvMatchingAiAgentApplication.class);
        app.addListeners(new StartupStatusListener());
        app.run(args);
    }
}
