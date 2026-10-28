package com.example.intelligent_cv_matching_ai_agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IntelligentCvMatchingAiAgentApplication {

    public static void main(
        String[] args) {
        SpringApplication.run(IntelligentCvMatchingAiAgentApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        System.out.println("\n==================================");
        System.out.println("SPRING BOOT STARTED SUCCESSFULLY");
        System.out.println("Swagger UI: http://localhost:8080/swagger-ui/index.html");
        System.out.println("==================================\n");
    }

}
