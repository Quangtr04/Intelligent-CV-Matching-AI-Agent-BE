import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import javax.tools.*;

/**
 * One-time package migration. Java strings/comments and HTTP contracts are
 * retained.
 */
public class OrganizeRolePackages {

    static final String BASE = "com.example.intelligent_cv_matching_ai_agent";

    static final Map<String, String> RENAMES = Map.ofEntries(
        Map.entry("Answer", "AssessmentAnswerRequest"),
        Map.entry("NodeStatus", "RoadmapNodeStatusRequest"),
        Map.entry("Assign", "AssignConsultantRequest"),
        Map.entry("CreateThread", "CreateConsultationRequest"),
        Map.entry("Message", "ConsultationMessageRequest"),
        Map.entry("Feedback", "MatchingFeedbackRequest"),
        Map.entry("EditDraft", "ApplicationDraftUpdateRequest"),
        Map.entry("ImportJob", "ImportJobRequest"),
        Map.entry("SkillInput", "JobSkillRequest"),
        Map.entry("ResumeDownload", "ResumeDownloadResponse")
    );

    record Source(
        Path file,
        String text,
        CompilationUnitTree tree
    ) {
    }

    record Move(
        String oldName,
        String name,
        String oldPackage,
        String targetPackage
    ) {

        String oldFqn() {
            return oldPackage + "." + oldName;
        }

        String fqn() {
            return targetPackage + "." + name;
        }
    }

    static Path root;

    static boolean member(
        String name,
        String words) {
        return Arrays.asList(words.split(" ")).contains(name);
    }

    static String entityDomain(
        String name) {
        if (name.endsWith("Id"))
            name = name.substring(0, name.length() - 2);
        if (member(name, "User EmailVerificationCode RefreshToken"))
            return "shared.identity";
        if (member(name, "GuestSession TrialUsageEvent"))
            return "guest.trial";
        if (name.startsWith("CandidateProfile") || name.equals("CareerProfileEmbedding"))
            return "candidate.profile";
        if (name.equals("ResumeTemplate"))
            return "shared.resume";
        if (name.startsWith("Resume"))
            return "candidate.resume";
        if (name.startsWith("Assessment") || name.startsWith("GapAnalysis") || name.startsWith("Roadmap"))
            return "candidate.learning";
        if (member(name, "Skill LearningResource"))
            return "shared.learning";
        if (name.startsWith("Consultation"))
            return "consultant.consultation";
        if (name.startsWith("CareerAssistant"))
            return "candidate.ai";
        if (member(name, "AiConfiguration AiPromptTemplate"))
            return "admin.ai";
        if (member(name, "AiUsageLog AiTokenReservation"))
            return "shared.ai";
        if (member(name, "RagDocument RagChunk RagChunkEmbedding"))
            return "admin.rag";
        if (name.startsWith("MarketCrawl") || name.equals("VectorIndexingJob"))
            return "admin.jobs";
        if (name.startsWith("MarketJob"))
            return "shared.jobs";
        if (name.startsWith("JobMatch") || name.startsWith("MatchingRunResume")
            || member(name, "JobResumeAnalysis ApplicationDraft ExternalApplyEvent SalaryEstimation"))
            return "candidate.jobs";
        if (name.startsWith("Payment") || name.startsWith("Subscription") || member(name, "Invoice UserSubscription"))
            return "shared.billing";
        if (name.startsWith("Announcement") || name.equals("Notification"))
            return "shared.announcement";
        if (name.startsWith("SupportTicket"))
            return "shared.support";
        if (member(name, "AdminAction AuditLog SystemLog VectorStoreMetric"))
            return "admin.monitoring";
        throw new IllegalArgumentException("Entity ownership not specified: " + name);
    }

    static String workflowDomain(
        String name,
        String feature) {
        if (member(
            name,
            ("GuestController GuestSessionService GuestSessionRepository " + "GuestAccess GuestAccessRepository")
        ))
            return "guest.identity";
        if (member(name, ("GuestTrialController GuestTrialService GuestTrialRepository " + "GuestAssistantController")))
            return "guest.ai";
        if (member(name, "CareerAssistantController CareerAssistantService CareerAssistantRepository"))
            return "candidate.ai";
        if (member(name, "RagAdminController RagDocumentService RagDocumentRepository"))
            return "admin.rag";
        if (name.startsWith("JobCrawl"))
            return "admin.jobs";
        if (member(
            name,
            ("CareerToolsController CareerToolsService CareerToolsRepository " + "MatchingService MatchingRepository")
        ))
            return "candidate.jobs";
        if (name.startsWith("Announcement"))
            return "shared.announcement";
        if (name.startsWith("Support"))
            return "shared.support";
        if (name.startsWith("Audit"))
            return "admin.monitoring";
        return switch (feature) {
            case "resume" -> "candidate.resume";
            case "learning" -> "candidate.learning";
            case "identity" -> "shared.identity";
            case "jobs" -> "shared.jobs";
            case "consultation" -> "shared.consultation";
            case "billing" -> "shared.billing";
            case "ai" -> "shared.ai";
            case "admin" -> "admin.management";
            default -> throw new IllegalArgumentException("Unknown workflow: " + name + "/" + feature);
        };
    }

    static String dtoPackage(
        String name,
        String feature) {
        if (name.equals("ExtractedResume"))
            return "util.shared.resume"; // Parser result is internal, not an HTTP DTO.
        if (name.equals("ResumeDownload"))
            return "dto.response.candidate.resume";
        if (name.equals("PersonalProfileResponse"))
            return "dto.response.shared.identity";
        String domain;
        if (feature.equals("identity"))
            domain = member(name, "CareerProfileRequest CareerSkillRequest") ? "candidate.profile" : "shared.identity";
        else if (feature.equals("resume"))
            domain = "candidate.resume";
        else if (feature.equals("learning"))
            domain = "candidate.learning";
        else if (feature.equals("jobs"))
            domain = member(name, "ImportJob SkillInput CrawlRequest") ? "admin.jobs" : "candidate.jobs";
        else if (feature.equals("consultation"))
            domain = switch (name) {
                case "CreateThread" -> "candidate.consultation";
                case "Assign" -> "admin.consultation";
                default -> "shared.consultation";
            };
        else if (feature.equals("billing"))
            domain = name.equals("PaymentWebhookRequest") ? "shared.billing" : "candidate.billing";
        else if (feature.equals("ai"))
            domain = name.startsWith("Guest")
                ? "guest.ai"
                : name.equals("CreateRagDocumentRequest")
                    ? "admin.rag"
                    : name.equals("AssistantMessageRequest") ? "shared.ai" : "candidate.ai";
        else if (feature.equals("admin"))
            domain = switch (name) {
                case "SupportTicketRequest" -> "candidate.support";
                case "SupportMessageRequest" -> "shared.support";
                case "SupportTicketUpdateRequest" -> "admin.support";
                case "AnnouncementRequest", "AnnouncementTargetRequest" -> "admin.announcement";
                case "AiConfigurationRequest", "AiPromptRequest" -> "admin.ai";
                case "SubscriptionTierRequest", "SubscriptionQuotaRequest", "PaymentReportResolutionRequest" ->
                    "admin.billing";
                default -> "admin.management";
            };
        else
            throw new IllegalArgumentException("Unknown DTO: " + name);
        return "dto.request." + domain;
    }

    static String targetPackage(
        String name,
        String pkg) {
        String relative = pkg.substring(BASE.length() + 1);
        String[] parts = relative.split("\\.");
        String layer = parts[0], feature = parts.length > 1 ? parts[1] : "";
        if (layer.equals("dto"))
            return BASE + "." + dtoPackage(name, feature);
        if (layer.equals("entity"))
            return BASE + ".entity." + entityDomain(name);
        if (layer.equals("repository") && feature.equals("entity"))
            return BASE + ".repository." + entityDomain(name.replace("JpaRepository", "")) + ".jpa";
        if (member(layer, "config security exception"))
            return BASE + "." + layer + ".shared";
        if (layer.equals("util"))
            return BASE + ".util.shared" + (feature.isEmpty() ? "" : "." + feature);
        if (name.equals("SqlStore"))
            return BASE + ".repository.shared";
        return BASE + "." + layer + "." + workflowDomain(name, feature);
    }

    static List<Source> sources() throws Exception {
        List<Path> paths = new ArrayList<>();
        for (String scope : List.of("src/main/java", "src/test/java"))
            try (var files = Files.walk(root.resolve(scope))) {
                paths.addAll(files.filter(p -> p.toString().endsWith(".java")).sorted().toList());
            }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, null)) {
            JavacTask task = (JavacTask) compiler
                .getTask(null, manager, null, List.of("-proc:none"), null, manager.getJavaFileObjectsFromPaths(paths));
            List<Source> result = new ArrayList<>();
            for (CompilationUnitTree unit : task.parse()) {
                Path file = Path.of(unit.getSourceFile().toUri());
                result.add(new Source(file, Files.readString(file), unit));
            }
            return result;
        }
    }

    static String rewriteCode(
        String source,
        Collection<Move> mapping) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < source.length();) {
            int begin = i;
            if (source.startsWith("//", i)) {
                i = source.indexOf('\n', i);
                if (i < 0)
                    i = source.length();
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                i += 3;
                while (i < source.length() && !source.startsWith("\"\"\"", i)) {
                    if (source.charAt(i) == '\\')
                        i++;
                    i++;
                }
                i = Math.min(source.length(), i + 3);
            } else if (source.charAt(i) == '\"' || source.charAt(i) == '\'') {
                char quote = source.charAt(i++);
                while (i < source.length()) {
                    char ch = source.charAt(i++);
                    if (ch == '\\' && i < source.length())
                        i++;
                    else if (ch == quote)
                        break;
                }
            } else if (Character.isJavaIdentifierStart(source.charAt(i))) {
                Move qualified = null;
                if (source.startsWith(BASE, i))
                    for (Move move : mapping) {
                        int end = i + move.oldFqn().length();
                        if (source.startsWith(move.oldFqn(), i)
                            && (end == source.length() || !Character.isJavaIdentifierPart(source.charAt(end)))) {
                            qualified = move;
                            break;
                        }
                    }
                if (qualified != null) {
                    out.append(qualified.fqn());
                    i += qualified.oldFqn().length();
                    continue;
                }
                i++;
                while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i)))
                    i++;
                String token = source.substring(begin, i);
                out.append(RENAMES.getOrDefault(token, token));
                continue;
            } else {
                i++;
            }
            out.append(source, begin, i);
        }
        return out.toString();
    }

    static Path checked(
        Path path) {
        Path value = path.toAbsolutePath().normalize();
        if (!value.startsWith(root.resolve("src")))
            throw new IllegalArgumentException("Source path outside project: " + value);
        return value;
    }

    public static void main(
        String[] args)
        throws Exception {
        root = Path.of("").toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("pom.xml")))
            throw new IllegalStateException("Run from the Maven backend project");
        List<Source> sources = sources();
        Map<String, Move> moves = new TreeMap<>();
        for (Source source : sources) {
            if (!source.file().startsWith(root.resolve("src/main/java")))
                continue;
            String pkg = source.tree().getPackageName().toString();
            if (pkg.equals(BASE))
                continue;
            String oldName = source.file().getFileName().toString().replace(".java", "");
            Move move = new Move(oldName, RENAMES.getOrDefault(oldName, oldName), pkg, targetPackage(oldName, pkg));
            if (moves.putIfAbsent(oldName, move) != null)
                throw new IllegalStateException("Duplicate class name: " + oldName);
        }
        // Prevent a second run after the one-time migration from reclassifying roles as
        // features.
        if (moves.values().stream().anyMatch(m -> m.oldPackage().contains(".dto.request.")))
            throw new IllegalStateException("Role migration already applied");
        StringBuilder properties = new StringBuilder(
            ("# Class ownership and generator output packages. Shared classes serve " + "multiple roles.\n")
        );
        for (Move move : moves.values())
            properties.append(move.name()).append('=').append(move.targetPackage().substring(BASE.length() + 1))
                .append('\n');
        Files.writeString(root.resolve("scripts/dev/role-packages.properties"), properties);
        int mainCount = 0, testCount = 0;
        for (Source source : sources) {
            String oldName = source.file().getFileName().toString().replace(".java", "");
            String pkg = source.tree().getPackageName().toString();
            boolean main = source.file().startsWith(root.resolve("src/main/java"));
            Move self = main ? moves.get(oldName) : null;
            String newPackage = self == null ? pkg : self.targetPackage();
            if (!main)
                newPackage = switch (oldName) {
                    case "JobCrawlServiceTests" -> moves.get("JobCrawlService").targetPackage();
                    case "SubscriptionMaintenanceTest" -> moves.get("SubscriptionMaintenance").targetPackage();
                    case "WebhookVerifierTest" -> moves.get("WebhookVerifier").targetPackage();
                    case "GoogleTokenVerifierTest" -> moves.get("GoogleTokenVerifier").targetPackage();
                    default -> pkg;
                };
            Set<String> needed = new TreeSet<>();
            new TreeScanner<Void, Void>() {

                @Override
                public Void visitIdentifier(
                    IdentifierTree node,
                    Void ignored) {
                    if (moves.containsKey(node.getName().toString()))
                        needed.add(node.getName().toString());
                    return super.visitIdentifier(node, ignored);
                }
            }.scan(source.tree(), null);
            String value = rewriteCode(source.text(), moves.values());
            value = value.replaceFirst("package\\s+[\\w.]+;", "package " + newPackage + ";");
            value = value.replaceAll("(?m)^import " + Pattern.quote(BASE) + "\\.[\\w.]+\\.\\*;\\R?", "");
            Set<String> imports = new TreeSet<>();
            for (String name : needed) {
                Move move = moves.get(name);
                if (move.equals(self) || move.targetPackage().equals(newPackage))
                    continue;
                if (!value.contains("import " + move.fqn() + ";"))
                    imports.add("import " + move.fqn() + ";");
            }
            if (!imports.isEmpty())
                value = value.replace(
                    "package " + newPackage + ";",
                    "package " + newPackage + ";\n\n" + String.join("\n", imports)
                );
            String className = self == null ? oldName : self.name();
            Path destination = checked(
                root.resolve(main ? "src/main/java" : "src/test/java").resolve(newPackage.replace('.', '/'))
                    .resolve(className + ".java")
            );
            checked(source.file());
            if (!destination.equals(source.file()) && Files.exists(destination))
                throw new IllegalStateException("Destination exists: " + destination);
            Files.createDirectories(destination.getParent());
            Files.writeString(destination, value);
            if (!destination.equals(source.file()))
                Files.delete(source.file());
            if (main)
                mainCount++;
            else
                testCount++;
        }
        System.out.println(
            "Organized " + mainCount + " main and " + testCount
                + " test sources; package map written to scripts/dev/role-packages.properties."
        );
    }
}
