package com.example.intelligent_cv_matching_ai_agent;

import org.springframework.boot.SpringApplication;

public class TestIntelligentCvMatchingAiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.from(IntelligentCvMatchingAiAgentApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
