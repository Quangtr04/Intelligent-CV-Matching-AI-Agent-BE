package com.example.intelligent_cv_matching_ai_agent.resume;

import com.example.intelligent_cv_matching_ai_agent.util.shared.resume.ResumeSkillMatcher;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ResumeSkillMatcherTests {

    @Test
    void emptyTextHasNoSkills() {
        assertEquals(List.of(), ResumeSkillMatcher.find(null));
        assertEquals(List.of(), ResumeSkillMatcher.find(" \n\t"));
    }

    @Test
    void canonicalizesAliasesInSourceOrderWithoutDuplicates() {
        assertEquals(
            List.of("TypeScript", "React", "JavaScript", "PostgreSQL", "Kubernetes"),
            ResumeSkillMatcher.find("TS / React.js, js, Postgres, K8s; ReactJS and PostgreSQL")
        );
    }

    @Test
    void usesTokenBoundariesInsteadOfInferringSkillsFromLongerNames() {
        assertEquals(
            List.of("JavaScript", "GitHub", "GitLab"),
            ResumeSkillMatcher.find("JavaScript, GitHub, GitLab and MySQLConnector")
        );
    }

    @Test
    void preservesPunctuationInProgrammingLanguagesAndFrameworks() {
        assertEquals(
            List.of("C++", "C#", ".NET", "Next.js", "Node.js"),
            ResumeSkillMatcher.find("C++, C#; .NET / Next.js / Node.js")
        );
    }

    @Test
    void doesNotAddShorterNamesContainedInSpecificSkills() {
        assertEquals(
            List.of("Spring Boot", "ASP.NET", "React Native", "SQL Server", "Tailwind CSS"),
            ResumeSkillMatcher.find("Spring Boot, ASP.NET Core, React Native, SQL Server, Tailwind CSS")
        );
        assertEquals(List.of("React", "Node.js"), ResumeSkillMatcher.find("React.js / Node.js"));
    }

    @Test
    void keepsAnExplicitSeparateMentionOfAShorterSkill() {
        assertEquals(
            List.of("Spring Boot", "Spring", "ASP.NET", ".NET"),
            ResumeSkillMatcher.find("Spring Boot; Spring; ASP.NET; .NET")
        );
    }

    @Test
    void doesNotTreatEverydaySingleLetterWordsAsProgrammingSkills() {
        assertEquals(List.of("Go"), ResumeSkillMatcher.find("Go to work, R and C. Golang and go programming."));
    }

    @Test
    void excludesNegatedAndAspirationalMentionsInEnglishAndVietnamese() {
        assertEquals(
            List.of("Java", "PostgreSQL"),
            ResumeSkillMatcher.find(
                "No experience with Docker or Kubernetes. Java developer. Want to learn Python.\n"
                    + "Chưa biết React; đang học AWS; thành thạo PostgreSQL."
            )
        );
    }

    @Test
    void acceptsPositiveEvidenceAfterAContrastingClause() {
        assertEquals(
            List.of("RabbitMQ", "Redis", "Git"),
            ResumeSkillMatcher.find("No experience with Kafka but used RabbitMQ and Redis. Python: no experience; Git")
        );
    }

    @Test
    void preservesPositiveMentionsBeforeUncertainSkillsInTheSameSentence() {
        assertEquals(
            List.of("Java", "Docker"),
            ResumeSkillMatcher.find("Used Java, want to learn Python. Docker; muốn học Kubernetes.")
        );
    }
}
