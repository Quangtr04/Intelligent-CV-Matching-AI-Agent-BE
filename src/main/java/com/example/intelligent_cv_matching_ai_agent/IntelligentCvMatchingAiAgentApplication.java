package com.example.intelligent_cv_matching_ai_agent;

import com.example.intelligent_cv_matching_ai_agent.config.shared.StartupStatusListener;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IntelligentCvMatchingAiAgentApplication {

    public static void main(
        String[] args) {
        SpringApplication app = new SpringApplication(IntelligentCvMatchingAiAgentApplication.class);
        app.addListeners(new StartupStatusListener());
        app.run(args);
    }

}
