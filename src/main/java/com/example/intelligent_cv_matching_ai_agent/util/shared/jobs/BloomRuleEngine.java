package com.example.intelligent_cv_matching_ai_agent.util.shared.jobs;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic Vietnamese/English Bloom classifier. No AI, no external calls.
 * Classifies evidenced actions, never infers cognitive level from title or years.
 * Unknown or purely nominal skill/experience statements remain UNDETERMINED.
 */
public final class BloomRuleEngine {

    public record Prediction(
        String level,
        String evidence,
        String justification
    ) {
    }

    private record Rule(
        String level,
        Pattern action,
        Pattern object,
        String reason,
        int priority
    ) {
    }

    private record Hit(
        int start,
        int end,
        Rule rule
    ) {
    }

    private static final String OBJECT_TECH =
        "api|apis|endpoint|service|microservice|application|app|web|mobile|website|"
            + "software|system|code|feature|function|interface|component|database|sql|"
            + "pipeline|workflow|test|testing|test case|automation|postman|java|python|"
            + "spring boot|git|jenkins|ci/cd|deployment|production|"
            + "he thong|ung dung|phan mem|ma nguon|giao dien|chuc nang|"
            + "dich vu|quy trinh|kiem thu|kiem tra|truong hop kiem thu|"
            + "co so du lieu|du lieu|truy van|kiem thu api|bao cao";

    private static Pattern words(
        String alternatives) {
        return Pattern.compile("(?<![\\p{L}\\p{N}_])(?:" + alternatives + ")(?![\\p{L}\\p{N}_])");
    }

    private static Rule rule(
        String level,
        String actions,
        String objects,
        String reason,
        int priority) {
        return new Rule(level, words(actions), objects == null ? null : words(objects), reason, priority);
    }

    // More specific cognitive actions take precedence over generic operational verbs.
    // An object must occur shortly AFTER the action. Multi-action clauses are split beforehand.
    private static final List<Rule> RULES = List.of(
        rule(
            "Create",
            "design|architect|devise|invent|formulate|"
                + "thiet ke|sang tao|phat minh|xay dung kien truc|de xuat kien truc",
            "architecture|architectures|algorithm|solution|model|framework|strategy|"
                + "system|platform|workflow|test strategy|test plan|"
                + "kien truc|thuat toan|giai phap|mo hinh|chien luoc|"
                + "ke hoach kiem thu|he thong|nen tang|quy trinh|giao dien",
            "Designing or formulating an artifact/solution",
            60
        ),
        rule(
            "Create",
            "create|develop|build|xay dung|phat trien|tao ra",
            "new|novel|original|from scratch|moi|tu dau|sang tao",
            "Creating an explicitly new artifact",
            58
        ),
        rule(
            "Evaluate",
            "evaluate|assess|compare|justify|prioritize|"
                + "danh gia|so sanh|th am dinh|tham dinh|lua chon|uu tien|de xuat",
            "criteria|standard|alternative|option|quality|security|performance|cost|risk|"
                + "evidence|trade off|tieu chi|tieu chuan|phuong an|chat luong|bao mat|"
                + "hieu nang|chi phi|rui ro|bang chung|muc do|giai phap|ket qua",
            "Judging alternatives or outcomes against criteria",
            50
        ),
        rule(
            "Analyze",
            "analyze|analyse|diagnose|debug|investigate|troubleshoot|"
                + "phan tich|chan doan|dieu tra|tim nguyen nhan|xac dinh nguyen nhan|" + "phan ra|boc tach|phan ra cac",
            "data|issue|error|problem|failure|causes?|bottleneck|dependency|"
                + "relationship|log|performance|requirement|risk|root causes?|"
                + "du lieu|loi|su co|van de|nguyen nhan|nut that|phu thuoc|"
                + "moi quan he|nhat ky|hieu nang|yeu cau|rui ro|he thong|quy trinh",
            "Investigating parts, causes or relationships",
            40
        ),
        rule(
            "Understand",
            "explain|describe|interpret|summarize|paraphrase|" + "giai thich|mo ta|dien giai|tom tat|trinh bay|lam ro",
            "concepts?|principles?|mechanisms?|lifecycle|architecture|behavior|" + "meaning|result|process|how|why|"
                + "khai niem|nguyen ly|co che|vong doi|kien truc|hanh vi|"
                + "y nghia|ket qua|quy trinh|cach hoat dong|su khac biet",
            "Explaining or interpreting a concept/process",
            20
        ),
        rule(
            "Remember",
            "recall|list|name|define|enumerate|" + "liet ke|ghi nho|dinh nghia|ke ten|nhac lai",
            "syntax|terms?|definitions?|keyword|fact|command|type|concept|"
                + "cu phap|thuat ngu|dinh nghia|tu khoa|lenh|kieu du lieu|khai niem|" + "buoc|quy tac|thanh phan",
            "Recalling or listing factual knowledge",
            10
        ),
        rule(
            "Apply",
            "use|utilize|apply|implement|configure|deploy|integrate|"
                + "execute|perform|test|verify|fix|code|run|operate|"
                + "su dung|ap dung|trien khai|cau hinh|tich hop|thuc hien|"
                + "kiem thu|kiem tra|xac minh|sua loi|khac phuc|lap trinh|van hanh|"
                + "xay dung|phat trien|bao tri|tu dong hoa",
            OBJECT_TECH,
            "Applying a procedure/tool to a concrete technical task",
            30
        )
    );

    // These are attributes, titles, or metadata, NOT evidence of Bloom performance.
    private static final Pattern NOMINAL_ONLY = words(
        "experience|experienced|familiar|knowledge|proficient|expertise|"
            + "years|year|senior|middle|junior|bachelor|degree|"
            + "kinh nghiem|co kinh nghiem|thanh thao|hieu biet|quen thuoc|" + "nam|cu nhan|bang cap|tot nghiep"
    );

    private static final Pattern META = words(
        "working hours|location|direct supervisor|salary|benefits|"
            + "gio lam viec|dia diem|nguoi quan ly|muc luong|phuc loi"
    );

    private static final Pattern CONNECTOR = words("and|va|dong thoi|sau do|then");

    private static final Pattern CLAUSE_BREAK = Pattern.compile("[;\\r\\n]+|(?<=[.!?])\\s+(?=[\\p{Lu}])");

    private BloomRuleEngine() {
    }

    public static String fold(
        String input) {
        if (input == null)
            return "";
        String canonical = input.replace('đ', 'd').replace('Đ', 'D');
        String decomposed = Normalizer.normalize(canonical, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static List<String> clauses(
        String original) {
        List<String> output = new ArrayList<>();
        for (String part : CLAUSE_BREAK.split(original)) {
            if (part.isBlank())
                continue;
            // Unicode-aware boundaries: ASCII \b alone is unreliable with Vietnamese diacritics.
            Matcher raw =
                Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])(?:and|và|va|đồng thời|sau đó|then)(?![\\p{L}\\p{N}_])")
                    .matcher(part);
            int from = 0;
            while (raw.find()) {
                if (!startsWithAction(fold(part.substring(raw.end()))))
                    continue;
                String left = part.substring(from, raw.start()).trim();
                if (!left.isEmpty())
                    output.add(left);
                from = raw.end();
            }
            String remainder = part.substring(from).trim();
            if (!remainder.isEmpty())
                output.add(remainder);
        }
        return output;
    }

    private static boolean startsWithAction(
        String s) {
        String rest = s.replaceFirst("^(?:can|able to|co the|phai|must|should|need to)\\s+", "");
        return RULES.stream().anyMatch(r -> {
            Matcher m = r.action().matcher(rest);
            return m.find() && m.start() == 0;
        });
    }

    private static Hit classify(
        String normalized) {
        List<Hit> hits = new ArrayList<>();
        for (Rule rule : RULES) {
            Matcher verbs = rule.action().matcher(normalized);
            while (verbs.find()) {
                int end = verbs.end();
                // A verb alone cannot prove a cognitive action: require its object/context.
                if (rule.object() != null) {
                    Matcher object = rule.object().matcher(normalized);
                    object.region(end, Math.min(normalized.length(), end + 95));
                    if (!object.find())
                        continue;
                    // Generic experience-in-testing is a nominal requirement, not performed action.
                    String before = normalized.substring(Math.max(0, verbs.start() - 32), verbs.start());
                    if (NOMINAL_ONLY.matcher(before).find()
                        && !before.matches(".*(?:can|able to|co the|kha nang)\\s*$")) {
                        continue;
                    }
                }
                hits.add(new Hit(verbs.start(), verbs.end(), rule));
            }
        }
        if (hits.isEmpty())
            return null;
        // Multiple rules on the SAME action span: prefer the more specific one.
        // For truly separate actions, caller receives one result per independently split clause.
        return hits.stream().max(
            Comparator.comparingInt(
                (
                    Hit h) -> h.rule().priority()
            ).thenComparingInt(h -> -h.start())
        ).orElse(null);
    }

    public static List<Prediction> classifyRequirement(
        String original) {
        if (original == null || original.isBlank()) {
            return List.of(new Prediction("UNDETERMINED", "", "Empty requirement"));
        }
        Set<String> seen = new LinkedHashSet<>();
        List<Prediction> out = new ArrayList<>();
        for (String clause : clauses(original)) {
            String normalized = fold(clause);
            Hit hit = META.matcher(normalized).find() ? null : classify(normalized);
            String level = hit == null ? "UNDETERMINED" : hit.rule().level();
            String reason =
                hit == null ? "Insufficient explicit cognitive action and object evidence" : hit.rule().reason();
            String key = level + "|" + normalized;
            if (seen.add(key))
                out.add(new Prediction(level, clause, reason));
        }
        return out.isEmpty()
            ? List.of(new Prediction("UNDETERMINED", original, "No classifiable requirement"))
            : List.copyOf(out);
    }
}
