package com.example.intelligent_cv_matching_ai_agent.util.shared.jobs;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;

/** Immutable local vocabulary. Relations are metadata, never evidence that a JD requests another skill. */
public final class SkillCatalog {

    public record Source(
        String id,
        String url,
        String revision,
        String license,
        String permissionEvidence
    ) {
    }

    public record Topic(
        String id,
        String title,
        String type,
        List<String> prerequisites,
        List<String> sourceReferences
    ) {

        public Topic {
            prerequisites = List.copyOf(prerequisites);
            sourceReferences = List.copyOf(sourceReferences);
        }
    }

    public record Skill(
        String id,
        String name,
        String domain,
        String type,
        List<String> aliases,
        List<String> ambiguousAliases,
        String contextPattern,
        List<String> recognitionPatterns,
        List<String> relatedSkills,
        List<String> learningTopics,
        List<String> sourceReferences
    ) {

        public Skill {
            aliases = List.copyOf(aliases);
            ambiguousAliases = List.copyOf(ambiguousAliases);
            recognitionPatterns = List.copyOf(recognitionPatterns);
            relatedSkills = List.copyOf(relatedSkills);
            learningTopics = List.copyOf(learningTopics);
            sourceReferences = List.copyOf(sourceReferences);
        }
    }

    public record Document(
        String catalogVersion,
        List<Source> sources,
        List<Skill> skills,
        List<Topic> topics
    ) {
    }

    public record Mention(
        Skill skill,
        int start,
        int end,
        String layer
    ) {
    }

    private record ContextRule(
        Skill skill,
        Pattern pattern
    ) {
    }

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private static final Pattern SPACE = Pattern.compile("\\s+");

    private static final Set<String> TYPES = Set.of(
        "SKILL",
        "TECHNOLOGY",
        "TOOL",
        "COMPETENCY",
        "KNOWLEDGE",
        "SOFT_SKILL",
        "LANGUAGE",
        "CATEGORY",
        "LEARNING_TOPIC"
    );

    private static final class Trie {

        final Map<Character, Trie> children = new HashMap<>();

        final List<Skill> skills = new ArrayList<>();
    }

    private static final class Holder {

        static final SkillCatalog INSTANCE = loadDefault();
    }

    private final Map<String, Skill> skills = new LinkedHashMap<>();

    private final Map<String, Topic> topics = new LinkedHashMap<>();

    private final Map<String, List<Skill>> aliases = new HashMap<>();

    private final Map<String, Pattern> contexts = new HashMap<>();

    private final List<ContextRule> contextual = new ArrayList<>();

    private final Trie root = new Trie();

    private final String version;

    public static SkillCatalog get() {
        return Holder.INSTANCE;
    }

    public static String fold(
        String value) {
        return MARKS.matcher(Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)).replaceAll("")
            .replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
    }

    private static String key(
        String value) {
        return SPACE.matcher(fold(value).strip()).replaceAll(" ");
    }

    private static SkillCatalog loadDefault() {
        try {
            var mapper = JsonMapper.builder().build();
            List<Document> documents = new ArrayList<>();
            var digest = MessageDigest.getInstance("SHA-256");
            for (String path : List.of("/skills/catalog.json", "/skills/imported-catalog.json")) {
                try (InputStream input = SkillCatalog.class.getResourceAsStream(path)) {
                    if (input == null)
                        throw new IllegalStateException("Missing catalog: " + path);
                    byte[] bytes = input.readAllBytes();
                    digest.update(bytes);
                    documents.add(mapper.readValue(bytes, Document.class));
                }
            }
            return new SkillCatalog(documents, HexFormat.of().formatHex(digest.digest()));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot load local skill catalog", failure);
        }
    }

    public SkillCatalog(
        List<Document> documents,
        String fingerprint) {
        Set<String> sourceIds = new HashSet<>();
        for (Document document : documents) {
            if (document.catalogVersion == null || document.catalogVersion.isBlank())
                throw new IllegalArgumentException("Catalog version is required");
            for (Source source : document.sources) {
                if (!sourceIds.add(source.id) || source.revision == null || source.license == null
                    || source.permissionEvidence == null || source.permissionEvidence.isBlank())
                    throw new IllegalArgumentException("Duplicate or incomplete catalog source");
            }
            for (Topic topic : document.topics) {
                if (topics.putIfAbsent(topic.id, topic) != null)
                    throw new IllegalArgumentException("Duplicate topic ID: " + topic.id);
            }
            Set<String> documentIds = new HashSet<>();
            for (Skill skill : document.skills) {
                if (!skill.id.matches("[a-z0-9][a-z0-9-]*") || skill.name.isBlank() || skill.name.length() > 100
                    || !TYPES.contains(skill.type))
                    throw new IllegalArgumentException("Invalid skill: " + skill.id);
                if (!documentIds.add(skill.id))
                    throw new IllegalArgumentException("Duplicate skill ID: " + skill.id);
                skills.merge(skill.id, skill, SkillCatalog::merge);
            }
        }
        for (Topic topic : topics.values()) {
            requireReferences(topic.sourceReferences, sourceIds, "topic source");
            requireReferences(topic.prerequisites, topics.keySet(), "prerequisite");
        }
        for (Skill skill : skills.values()) {
            requireReferences(skill.relatedSkills, skills.keySet(), "related skill");
            requireReferences(skill.learningTopics, topics.keySet(), "learning topic");
            requireReferences(skill.sourceReferences, sourceIds, "skill source");
            if (Set.of("CATEGORY", "LEARNING_TOPIC").contains(skill.type))
                continue;
            Set<String> terms = new LinkedHashSet<>();
            terms.add(key(skill.name));
            skill.aliases.forEach(alias -> terms.add(key(alias)));
            for (String alias : terms) {
                if (alias.isBlank() || alias.length() > 150)
                    throw new IllegalArgumentException("Invalid alias");
                aliases.computeIfAbsent(alias, ignored -> new ArrayList<>()).add(skill);
            }
            if (skill.contextPattern != null && !skill.contextPattern.isBlank())
                contexts.put(skill.id, Pattern.compile(skill.contextPattern));
            for (String expression : skill.recognitionPatterns)
                contextual.add(new ContextRule(skill, Pattern.compile(expression)));
        }
        for (var entry : aliases.entrySet()) {
            if (entry.getValue().size() > 1 && entry.getValue().stream()
                .anyMatch(s -> s.ambiguousAliases.stream().map(SkillCatalog::key).noneMatch(entry.getKey()::equals)))
                throw new IllegalArgumentException("Conflicting unqualified alias: " + entry.getKey());
            Trie node = root;
            for (char character : entry.getKey().toCharArray())
                node = node.children.computeIfAbsent(character, ignored -> new Trie());
            node.skills.addAll(entry.getValue());
        }
        for (String topic : topics.keySet())
            validateAcyclic(topic, new HashSet<>(), new HashSet<>());
        version = String.join("+", documents.stream().map(Document::catalogVersion).toList()) + ":" + fingerprint;
    }

    private static List<String> union(
        List<String> first,
        List<String> second) {
        Set<String> result = new LinkedHashSet<>(first);
        result.addAll(second);
        return List.copyOf(result);
    }

    private static Skill merge(
        Skill first,
        Skill second) {
        if (!key(first.name).equals(key(second.name)) || !first.type.equals(second.type)
            || !first.contextPattern.equals(second.contextPattern)) {
            throw new IllegalArgumentException("Conflicting canonical identity: " + first.id);
        }
        return new Skill(
            first.id,
            first.name,
            first.domain,
            first.type,
            union(first.aliases, second.aliases),
            union(first.ambiguousAliases, second.ambiguousAliases),
            first.contextPattern,
            union(first.recognitionPatterns, second.recognitionPatterns),
            union(first.relatedSkills, second.relatedSkills),
            union(first.learningTopics, second.learningTopics),
            union(first.sourceReferences, second.sourceReferences)
        );
    }

    private static void requireReferences(
        List<String> values,
        Set<String> known,
        String kind) {
        if (values == null || values.stream().anyMatch(value -> !known.contains(value)))
            throw new IllegalArgumentException("Unknown " + kind + " reference");
    }

    private void validateAcyclic(
        String id,
        Set<String> path,
        Set<String> visited) {
        if (!path.add(id))
            throw new IllegalArgumentException("Learning prerequisite cycle: " + id);
        if (visited.add(id))
            for (String parent : topics.get(id).prerequisites)
                validateAcyclic(parent, path, visited);
        path.remove(id);
    }

    public String version() {
        return version;
    }

    public int size() {
        return skills.size();
    }

    public Collection<Skill> skills() {
        return Collections.unmodifiableCollection(skills.values());
    }

    public Optional<Skill> resolve(
        String name) {
        var found = aliases.getOrDefault(key(name), List.of());
        return found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty();
    }

    public String canonicalName(
        String name) {
        return resolve(name).map(Skill::name).orElse(name == null ? "" : name);
    }

    public String comparisonKey(
        String name) {
        return key(canonicalName(name));
    }

    private static boolean token(
        char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '+' || value == '#';
    }

    public List<Mention> mentions(
        String source) {
        String text = fold(source);
        String fullKey = key(text);
        List<Mention> result = new ArrayList<>();
        for (int start = 0; start < text.length() && result.size() < 2000; start++) {
            if (start > 0 && token(text.charAt(start - 1)))
                continue;
            Trie node = root;
            for (int end = start; end < text.length() && end - start < 150; end++) {
                char character = text.charAt(end);
                node = node.children.get(Character.isWhitespace(character) ? ' ' : character);
                if (Character.isWhitespace(character)) {
                    while (end + 1 < text.length() && Character.isWhitespace(text.charAt(end + 1)))
                        end++;
                }
                if (node == null)
                    break;
                if (node.skills.isEmpty() || end + 1 < text.length() && token(text.charAt(end + 1)))
                    continue;
                String alias = key(text.substring(start, end + 1));
                String context = text.substring(Math.max(0, start - 80), Math.min(text.length(), end + 81));
                var eligible = node.skills.stream()
                    .filter(
                        skill -> skill.ambiguousAliases.stream().map(SkillCatalog::key).noneMatch(alias::equals)
                            || fullKey.equals(alias)
                            || contexts.containsKey(skill.id) && contexts.get(skill.id).matcher(context).find()
                    ).toList();
                if (eligible.size() == 1)
                    result.add(new Mention(eligible.getFirst(), start, end + 1, "ALIAS"));
            }
        }
        result.sort(
            Comparator.comparingInt(Mention::start).thenComparing(Comparator.comparingInt(Mention::end).reversed())
        );
        List<Mention> accepted = new ArrayList<>();
        int through = -1;
        for (Mention mention : result) {
            if (mention.start >= through) {
                accepted.add(mention);
                through = mention.end;
            }
        }
        return List.copyOf(accepted);
    }

    public List<Mention> contextualMentions(
        String source) {
        String text = fold(source);
        List<Mention> result = new ArrayList<>();
        for (ContextRule rule : contextual) {
            var matcher = rule.pattern.matcher(text);
            while (matcher.find() && result.size() < 100)
                result.add(new Mention(rule.skill, matcher.start(), matcher.end(), "CONTEXTUAL"));
        }
        return List.copyOf(result);
    }

    public List<Skill> recognize(
        String source) {
        Map<String, Skill> result = new LinkedHashMap<>();
        mentions(source).forEach(m -> result.putIfAbsent(m.skill.id, m.skill));
        contextualMentions(source).forEach(m -> result.putIfAbsent(m.skill.id, m.skill));
        return List.copyOf(result.values());
    }

    public boolean isPrerequisite(
        String prerequisite,
        String target) {
        var before = resolve(prerequisite);
        var after = resolve(target);
        if (before.isEmpty() || after.isEmpty())
            return false;
        Set<String> beforeTopics = new HashSet<>(before.get().learningTopics);
        return after.get().learningTopics.stream().map(topics::get)
            .anyMatch(topic -> topic.prerequisites.stream().anyMatch(beforeTopics::contains));
    }

    /** Only topics of the actual missing-skill whitelist; graph edges never turn into JD requirements. */
    public List<Map<String, Object>> learningContext(
        List<Map<String, Object>> missing) {
        Set<String> selectedTopics = new HashSet<>();
        missing.forEach(
            gap -> resolve(Objects.toString(gap.get("skill_name"), ""))
                .ifPresent(skill -> selectedTopics.addAll(skill.learningTopics))
        );
        List<Map<String, Object>> result = new ArrayList<>();
        for (var gap : missing) {
            var skill = resolve(Objects.toString(gap.get("skill_name"), ""));
            if (skill.isEmpty())
                continue;
            List<Map<String, Object>> learning = skill.get().learningTopics.stream().map(topics::get)
                .map(
                    topic -> Map.<String, Object>of(
                        "id",
                        topic.id,
                        "title",
                        topic.title,
                        "type",
                        topic.type,
                        "prerequisites",
                        topic.prerequisites.stream().filter(selectedTopics::contains).toList(),
                        "source_references",
                        topic.sourceReferences
                    )
                ).toList();
            result.add(Map.of("skill_id", skill.get().id, "skill_name", skill.get().name, "topics", learning));
        }
        return List.copyOf(result);
    }
}
