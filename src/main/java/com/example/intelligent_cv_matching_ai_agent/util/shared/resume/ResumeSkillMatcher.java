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
        ("\\b(?:no\\s+(?:professional\\s+)?experience(?:\\s+(?:with|in|using))?|"
            + "not\\s+(?:experienced|familiar|proficient)(?:\\s+with)?|never\\s+(?:us"
            + "ed|worked\\s+with)|without\\s+experience(?:\\s+in)?|chua\\s+(?:biet|su"
            + "\\s+dung|co\\s+kinh\\s+nghiem|thanh\\s+thao)|khong\\s+(?:biet|co\\s+ki"
            + "nh\\s+nghiem|thanh\\s+thao|su\\s+dung)|want(?:ing)?\\s+to\\s+(?:learn|"
            + "study|explore|use)|(?:currently\\s+)?(?:learning|studying)|planning\\s"
            + "+to\\s+learn|interested\\s+in\\s+(?:learning|studying)|eager\\s+to\\s+"
            + "learn|would\\s+like\\s+to\\s+learn|dang\\s+hoc|muon\\s+(?:hoc|tim\\s+h"
            + "ieu)|du\\s+dinh\\s+hoc|mong\\s+muon\\s+hoc)\\b");

    private static final Pattern UNSUPPORTED = Pattern.compile(UNSUPPORTED_MENTION, FLAGS);

    private static final Pattern UNSUPPORTED_SUFFIX =
        Pattern.compile("^\\s*(?:[:\\-–—]\\s*)?" + UNSUPPORTED_MENTION, FLAGS);

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
        for (var mention : com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.JobRequirementExtractor
            .mentions(clause)) {
            if (mention.start() >= unsupportedFrom)
                continue;
            String suffix = clause.substring(mention.end(), Math.min(clause.length(), mention.end() + 100));
            if (!UNSUPPORTED_SUFFIX.matcher(suffix).find()) {
                mentions.add(new Mention(mention.name(), mention.start(), mention.end()));
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
        for (var mention : com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillCatalog.get()
            .contextualMentions(clause)) {
            if (mention.end() <= unsupportedFrom) {
                String suffix = clause.substring(mention.end(), Math.min(clause.length(), mention.end() + 100));
                if (!UNSUPPORTED_SUFFIX.matcher(suffix).find())
                    found.add(mention.skill().name());
            }
        }
    }

    private record Mention(
        String name,
        int start,
        int end
    ) {
    }
}
