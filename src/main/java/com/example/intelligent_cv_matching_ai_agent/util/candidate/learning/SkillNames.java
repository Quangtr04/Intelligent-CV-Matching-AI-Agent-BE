package com.example.intelligent_cv_matching_ai_agent.util.candidate.learning;

import java.text.Normalizer;
import java.util.Locale;

/** Exact skill identity comparison without conflating punctuation such as C, C++ and C#. */
public final class SkillNames {

    private SkillNames() {
    }

    public static String normalize(
        String value) {
        return com.example.intelligent_cv_matching_ai_agent.util.shared.jobs.SkillCatalog.get().comparisonKey(value);
    }
}
