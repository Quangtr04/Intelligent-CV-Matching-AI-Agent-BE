package com.example.intelligent_cv_matching_ai_agent.util.shared.resume;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Explicit technical-skill mentions only; does not infer proficiency or years of experience. */
public final class ResumeSkillMatcher {

    private static final int MAX_TEXT_LENGTH = 200_000;

    private static final int MAX_MENTIONS_PER_ALIAS = 64;

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern CLAUSE_BOUNDARY =
        Pattern.compile("[\\r\\n;!?]+|[.]\\s+|\\b(?:but|however|nhung|tuy nhien)\\b", FLAGS);

    private static final String UNSUPPORTED_MENTION =
        "\\b(?:no\\s+(?:professional\\s+)?experience(?:\\s+(?:with|in|using))?"
            + "|not\\s+(?:experienced|familiar|proficient)(?:\\s+with)?"
            + "|never\\s+(?:used|worked\\s+with)|without\\s+experience(?:\\s+in)?"
            + "|chua\\s+(?:biet|su\\s+dung|co\\s+kinh\\s+nghiem|thanh\\s+thao)"
            + "|khong\\s+(?:biet|co\\s+kinh\\s+nghiem|thanh\\s+thao|su\\s+dung)"
            + "|want(?:ing)?\\s+to\\s+(?:learn|study|explore|use)"
            + "|(?:currently\\s+)?(?:learning|studying)|planning\\s+to\\s+learn"
            + "|interested\\s+in\\s+(?:learning|studying)|eager\\s+to\\s+learn"
            + "|would\\s+like\\s+to\\s+learn|dang\\s+hoc|muon\\s+(?:hoc|tim\\s+hieu)"
            + "|du\\s+dinh\\s+hoc|mong\\s+muon\\s+hoc)\\b";

    private static final Pattern UNSUPPORTED = Pattern.compile(UNSUPPORTED_MENTION, FLAGS);

    private static final Pattern UNSUPPORTED_SUFFIX =
        Pattern.compile("^\\s*(?:[:\\-–—]\\s*)?" + UNSUPPORTED_MENTION, FLAGS);

    // Adapted from the job parser vocabulary. CV aliases and evidence rules are deliberately separate:
    // mentioning a desired skill in a job posting does not mean a candidate possesses it.
    private static final List<Alias> ALIASES = List.of(
        alias("Java", "java"),
        alias("JavaScript", "javascript", "java script", "js"),
        alias("TypeScript", "typescript", "type script", "ts"),
        alias("Python", "python"),
        alias("C#", "c#", "csharp", "c sharp"),
        alias("C++", "c++"),
        alias("Go", "golang", "go programming"),
        alias("PHP", "php"),
        alias("Kotlin", "kotlin"),
        alias("Swift", "swift"),
        alias("Dart", "dart"),
        alias("SQL", "sql"),
        alias("MySQL", "mysql"),
        alias("PostgreSQL", "postgresql", "postgres"),
        alias("SQL Server", "sql server", "mssql"),
        alias("SQLite", "sqlite"),
        alias("Oracle", "oracle"),
        alias("Spring Boot", "spring boot", "springboot"),
        alias("Spring", "spring", "springmvc", "spring mvc"),
        alias("React Native", "react native"),
        alias("React", "react", "reactjs", "react.js", "react js"),
        alias("Next.js", "next.js", "nextjs", "next js"),
        alias("Angular", "angular", "angularjs"),
        alias("Vue.js", "vue.js", "vuejs", "vue js"),
        alias("Nuxt.js", "nuxt.js", "nuxtjs", "nuxt js"),
        alias("Node.js", "node.js", "nodejs", "node js"),
        alias("Express.js", "express.js", "expressjs", "express js"),
        alias("NestJS", "nestjs", "nest.js", "nest js"),
        alias(".NET", ".net", "dotnet"),
        alias("ASP.NET", "asp.net", "aspnet"),
        alias("Laravel", "laravel"),
        alias("Django", "django"),
        alias("Flask", "flask"),
        alias("FastAPI", "fastapi", "fast api"),
        alias("Flutter", "flutter"),
        alias("Android", "android"),
        alias("iOS", "ios"),
        alias("Git", "git"),
        alias("Docker", "docker"),
        alias("Kubernetes", "kubernetes", "k8s"),
        alias("AWS", "aws", "amazon web services"),
        alias("Azure", "azure"),
        alias("GCP", "gcp", "google cloud platform"),
        alias("Linux", "linux"),
        alias("Redis", "redis"),
        alias("Kafka", "kafka"),
        alias("RabbitMQ", "rabbitmq", "rabbit mq"),
        alias("MongoDB", "mongodb", "mongo db"),
        alias("Elasticsearch", "elasticsearch", "elastic search"),
        alias("HTML", "html", "html5"),
        alias("CSS", "css", "css3"),
        alias("Tailwind CSS", "tailwind css", "tailwindcss", "tailwind"),
        alias("Bootstrap", "bootstrap"),
        alias("Sass", "sass", "scss"),
        alias("Jenkins", "jenkins"),
        alias("Terraform", "terraform"),
        alias("JUnit", "junit"),
        alias("Mockito", "mockito"),
        alias("Hibernate", "hibernate"),
        alias("Maven", "maven"),
        alias("Gradle", "gradle"),
        alias("GraphQL", "graphql", "graph ql"),
        alias("REST API", "rest api", "restful api", "restful apis"),
        alias("Postman", "postman"),
        alias("Selenium", "selenium"),
        alias("Cypress", "cypress"),
        alias("Playwright", "playwright"),
        alias("Figma", "figma"),
        alias("Power BI", "power bi", "powerbi"),
        alias("Excel", "excel", "microsoft excel")
    );

    private ResumeSkillMatcher() {
    }

    public static List<String> find(
        String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return List.of();
        }
        String bounded = plainText.substring(0, Math.min(plainText.length(), MAX_TEXT_LENGTH));
        String folded = Normalizer.normalize(bounded, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").replace('đ', 'd')
            .replace('Đ', 'D').toLowerCase(Locale.ROOT);
        LinkedHashSet<String> found = new LinkedHashSet<>();
        for (String clause : CLAUSE_BOUNDARY.split(folded)) {
            collect(clause, found);
            if (found.size() >= 100) {
                break;
            }
        }
        return List.copyOf(found);
    }

    private static void collect(
        String clause,
        LinkedHashSet<String> found) {
        if (clause.isBlank()) {
            return;
        }
        var uncertain = UNSUPPORTED.matcher(clause);
        int unsupportedFrom = uncertain.find() ? uncertain.start() : clause.length();
        List<Mention> mentions = new ArrayList<>();
        for (Alias alias : ALIASES) {
            var matcher = alias.pattern().matcher(clause);
            int occurrences = 0;
            while (matcher.find() && occurrences++ < MAX_MENTIONS_PER_ALIAS) {
                if (matcher.start() >= unsupportedFrom) {
                    break;
                }
                String suffix = clause.substring(matcher.end(), Math.min(clause.length(), matcher.end() + 100));
                if (!UNSUPPORTED_SUFFIX.matcher(suffix).find()) {
                    mentions.add(new Mention(alias.name(), matcher.start(), matcher.end()));
                }
            }
        }
        mentions.sort(
            Comparator.comparingInt(Mention::start).thenComparing(Comparator.comparingInt(Mention::end).reversed())
        );
        int acceptedThrough = -1;
        for (Mention mention : mentions) {
            if (mention.start() >= acceptedThrough) {
                found.add(mention.name());
                acceptedThrough = mention.end();
            }
        }
    }

    private static Alias alias(
        String name,
        String... aliases) {
        String alternatives = String.join(
            "|",
            java.util.Arrays.stream(aliases).sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote).toList()
        );
        return new Alias(
            name,
            Pattern.compile("(?<![\\p{L}\\p{N}_+#])(?:" + alternatives + ")(?![\\p{L}\\p{N}_+#])", FLAGS)
        );
    }

    private record Alias(
        String name,
        Pattern pattern
    ) {
    }

    private record Mention(
        String name,
        int start,
        int end
    ) {
    }
}
