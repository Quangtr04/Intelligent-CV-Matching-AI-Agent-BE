package com.example.intelligent_cv_matching_ai_agent.util.shared.learning;

import java.io.IOException;
import java.net.URI;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import static com.example.intelligent_cv_matching_ai_agent.util.shared.Values.*;

/** Verified public links only; publisher topic text and graphs are not bundled. */
@Component
public class RoadmapReferenceCatalog {

    private final List<Reference> references;

    private final String catalogUrl;

    private final String verifiedOn;

    public RoadmapReferenceCatalog() {
        try (var input = new ClassPathResource("learning/roadmap-references.json").getInputStream()) {
            Map<String, Object> data = object(JsonMapper.builder().build().readValue(input, Map.class));
            catalogUrl = text(data.get("source_catalog_url"));
            verifiedOn = text(data.get("verified_on"));
            requireSourceUrl(catalogUrl);
            List<Reference> loaded = new ArrayList<>();
            Set<String> urls = new LinkedHashSet<>();
            Set<String> slugs = new LinkedHashSet<>();
            for (Object item : array(data.get("roadmaps"))) {
                Map<String, Object> reference = object(item);
                String slug = text(reference.get("slug"));
                String title = text(reference.get("title"));
                String url = text(reference.get("url"));
                requireSourceUrl(url);
                if (slug.isBlank() || title.isBlank() || title.length() > 240 || !urls.add(url) || !slugs.add(slug)) {
                    throw new IllegalArgumentException("Invalid roadmap reference metadata");
                }
                List<String> aliases = array(reference.get("aliases")).stream().map(v -> normalize(text(v)))
                    .filter(v -> !v.isBlank()).distinct().toList();
                loaded.add(new Reference(slug, title, url, aliases));
            }
            if (loaded.isEmpty()) {
                throw new IllegalArgumentException("Roadmap reference catalog is empty");
            }
            references = List.copyOf(loaded);
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("Unable to load verified roadmap references", ex);
        }
    }

    public String catalogUrl() {
        return catalogUrl;
    }

    public List<Map<String, Object>> select(
        String target,
        Collection<String> skills) {
        String career = normalize(target);
        List<String> names = skills.stream().limit(50).map(RoadmapReferenceCatalog::normalize).filter(v -> !v.isBlank())
            .distinct().toList();
        return references.stream().map(reference -> new Match(reference, score(reference, career, names)))
            .filter(match -> match.score() > 0)
            .sorted(Comparator.comparingInt(Match::score).reversed().thenComparing(match -> match.reference().slug()))
            .limit(5)
            .map(
                match -> Map.<String, Object>of(
                    "slug",
                    match.reference().slug(),
                    "title",
                    match.reference().title(),
                    "url",
                    match.reference().url(),
                    "verified_on",
                    verifiedOn
                )
            ).toList();
    }

    public List<String> skillSlugs(
        String skill) {
        Set<String> slugs = new LinkedHashSet<>();
        String normalized = normalize(skill);
        if (!normalized.isBlank()) {
            slugs.add(normalized.replace(' ', '-'));
        }
        for (Map<String, Object> reference : select("", List.of(skill == null ? "" : skill))) {
            slugs.add(text(reference.get("slug")));
        }
        if (slugs.contains("git-github")) {
            slugs.add("git");
        }
        if (slugs.contains("spring-boot")) {
            slugs.add("spring");
        }
        if (slugs.contains("api-design")) {
            slugs.add("rest-api");
        }
        return List.copyOf(slugs);
    }

    private static int score(
        Reference reference,
        String target,
        List<String> skills) {
        int career = reference.aliases().stream().filter(alias -> contains(target, alias))
            .mapToInt(alias -> 100 + alias.length()).max().orElse(0);
        int focus = skills.stream()
            .mapToInt(
                skill -> reference.aliases().stream().filter(alias -> contains(skill, alias))
                    .mapToInt(alias -> 10 + alias.length()).max().orElse(0)
            ).sum();
        return career + Math.min(75, focus);
    }

    private static boolean contains(
        String value,
        String alias) {
        return (" " + value + " ").contains(" " + alias + " ");
    }

    public static String normalize(
        String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
            .replace('đ', 'd').replaceAll("[^\\p{L}\\p{N}+#]+", " ").strip();
    }

    private static void requireSourceUrl(
        String value) {
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || !"roadmap.sh".equals(uri.getHost()) || uri.getUserInfo() != null
            || uri.getPort() != -1 || uri.getFragment() != null) {
            throw new IllegalArgumentException("Invalid roadmap source URL");
        }
    }

    private record Reference(
        String slug,
        String title,
        String url,
        List<String> aliases
    ) {
    }

    private record Match(
        Reference reference,
        int score
    ) {
    }
}
