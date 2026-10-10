import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import javax.tools.*;

/** One-time, syntax-aware layer refactor. Every mutation stays inside src/. */
public class RefactorLayers {

    static final String BASE = "com.example.intelligent_cv_matching_ai_agent";

    static final Set<String> FEATURES =
        Set.of("admin", "ai", "billing", "consultation", "identity", "jobs", "learning", "resume", "common");

    record Edit(
        int start,
        int end,
        String value
    ) {
    }

    record Source(
        Path file,
        String content,
        CompilationUnitTree tree
    ) {
    }

    record Moved(
        String name,
        String oldPackage,
        String newPackage
    ) {
    }

    static Path root;

    static SourcePositions positions;

    static List<Source> parse(
        List<Path> paths)
        throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null);
        JavacTask task = (JavacTask) compiler
            .getTask(null, files, null, List.of("-proc:none"), null, files.getJavaFileObjectsFromPaths(paths));
        List<Source> sources = new ArrayList<>();
        for (CompilationUnitTree unit : task.parse()) {
            Path file = Path.of(unit.getSourceFile().toUri());
            sources.add(new Source(file, Files.readString(file), unit));
        }
        positions = Trees.instance(task).getSourcePositions();
        return sources;
    }

    static List<Path> javaFiles(
        String scope)
        throws Exception {
        try (var paths = Files.walk(root.resolve(scope))) {
            return paths.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    static int start(
        Source s,
        Tree t) {
        return Math.toIntExact(positions.getStartPosition(s.tree(), t));
    }

    static int end(
        Source s,
        Tree t) {
        return Math.toIntExact(positions.getEndPosition(s.tree(), t));
    }

    static String slice(
        Source s,
        Tree t) {
        return s.content().substring(start(s, t), end(s, t));
    }

    static String apply(
        String value,
        List<Edit> edits) {
        edits.sort(
            Comparator.comparingInt(Edit::start).reversed().thenComparing(Comparator.comparingInt(Edit::end).reversed())
        );
        StringBuilder out = new StringBuilder(value);
        int previous = value.length() + 1;
        for (Edit edit : edits) {
            if (edit.end() > previous)
                throw new IllegalStateException("Overlapping source replacements");
            out.replace(edit.start(), edit.end(), edit.value());
            previous = edit.start();
        }
        return out.toString();
    }

    static void write(
        Path file,
        String content)
        throws Exception {
        Path checked = file.toAbsolutePath().normalize();
        if (!checked.startsWith(root.resolve("src")))
            throw new IllegalArgumentException("Refactor may only mutate project source files");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    static String targetPackage(
        String name,
        String feature) {
        if (name.endsWith("Controller"))
            return BASE + ".controller." + feature;
        if (name.endsWith("Config"))
            return BASE + ".config";
        if (name.startsWith("ApiException"))
            return BASE + ".exception";
        if (name.equals("SqlStore"))
            return BASE + ".repository";
        if (Set.of("CurrentUser", "BearerFilter", "AuthThrottle", "GoogleTokenVerifier").contains(name))
            return BASE + ".security";
        if (name.equals("WebhookVerifier"))
            return BASE + ".util.billing";
        if (Set.of("ResumeData", "ResumeFileExtractor").contains(name))
            return BASE + ".util.resume";
        if (Set.of("Values", "FlowSupport").contains(name))
            return BASE + ".util";
        return BASE + ".service." + feature;
    }

    static void packages() throws Exception {
        List<Path> all = new ArrayList<>(javaFiles("src/main/java"));
        all.addAll(javaFiles("src/test/java"));
        List<Source> sources = parse(all);
        Map<String, Moved> mapping = new LinkedHashMap<>();
        for (Source source : sources) {
            String pkg = source.tree().getPackageName() == null ? "" : source.tree().getPackageName().toString();
            String feature = pkg.startsWith(BASE + ".") ? pkg.substring(BASE.length() + 1) : "";
            if (!source.file().startsWith(root.resolve("src/main/java")) || !FEATURES.contains(feature))
                continue;
            String name = source.file().getFileName().toString().replace(".java", "");
            mapping.put(name, new Moved(name, pkg, targetPackage(name, feature)));
        }
        for (Source source : sources) {
            String value = source.content(),
                pkg = source.tree().getPackageName() == null ? "" : source.tree().getPackageName().toString();
            String name = source.file().getFileName().toString().replace(".java", "");
            Moved move = source.file().startsWith(root.resolve("src/main/java")) ? mapping.get(name) : null;
            String target = move == null ? pkg : move.newPackage();
            Set<String> identifiers = new HashSet<>();
            new TreeScanner<Void, Void>() {

                @Override
                public Void visitIdentifier(
                    IdentifierTree tree,
                    Void unused) {
                    identifiers.add(tree.getName().toString());
                    return super.visitIdentifier(tree, unused);
                }
            }.scan(source.tree(), null);
            for (Moved known : mapping.values())
                value = value.replace(known.oldPackage() + "." + known.name(), known.newPackage() + "." + known.name());
            value = value.replace("import " + BASE + ".common.*;", "");
            if (move != null)
                value = value.replace("package " + pkg + ";", "package " + target + ";");
            Set<String> needed = new TreeSet<>();
            for (Moved known : mapping.values()) {
                if (identifiers.contains(known.name()) && !known.newPackage().equals(target)
                    && !known.name().equals(name))
                    needed.add("import " + known.newPackage() + "." + known.name() + ";");
            }
            for (String imp : needed)
                if (!value.contains(imp))
                    value = value.replaceFirst(
                        "(?m)^package ([^;]+);",
                        Matcher.quoteReplacement("package " + target + ";\n\n" + imp)
                    );
            Path destination = move == null
                ? source.file()
                : root.resolve("src/main/java/" + target.replace('.', '/') + "/" + name + ".java");
            write(destination, value);
            if (!destination.equals(source.file()))
                Files.delete(source.file());
        }
        System.out.println(
            "Relocated " + mapping.size()
                + (" production classes into "
                    + "controller/service/repository/util/config/security/exception packages.")
        );
    }

    static String pascal(
        String sql) {
        StringBuilder out = new StringBuilder();
        boolean upper = true;
        for (char ch : sql.toCharArray()) {
            if (ch == '_')
                upper = true;
            else {
                out.append(upper ? Character.toUpperCase(ch) : ch);
                upper = false;
            }
        }
        return out.toString();
    }

    static boolean literal(
        ExpressionTree expression) {
        if (expression instanceof LiteralTree l)
            return l.getValue() instanceof String;
        return expression instanceof BinaryTree b && b.getKind() == Tree.Kind.PLUS && literal(b.getLeftOperand())
            && literal(b.getRightOperand());
    }

    static String literalValue(
        ExpressionTree expression) {
        if (expression instanceof LiteralTree l)
            return (String) l.getValue();
        BinaryTree b = (BinaryTree) expression;
        return literalValue(b.getLeftOperand()) + literalValue(b.getRightOperand());
    }

    static String methodName(
        String operation,
        String sql) {
        if (operation.equals("insert"))
            return "insert" + pascal(sql);
        Matcher table = Pattern.compile("(?i)\\b(?:FROM|UPDATE|INTO)\\s+([a-z][a-z0-9_]*)").matcher(sql);
        String suffix = table.find() ? pascal(table.group(1)) : "DatabaseValue";
        String prefix = switch (operation) {
            case "one" -> "get";
            case "optional" -> "find";
            case "list" -> "list";
            case "scalar" -> sql.toLowerCase().contains("count(") ? "count" : "read";
            case "update" -> sql.stripLeading().toUpperCase().startsWith("DELETE") ? "delete" : "update";
            default -> throw new IllegalArgumentException(operation);
        };
        String extra = "";
        Matcher where = Pattern.compile("(?i)\\bWHERE\\s+(?:\\w+\\.)?([a-z][a-z0-9_]*)").matcher(sql);
        if (where.find())
            extra = "By" + pascal(where.group(1));
        if (sql.contains("FOR UPDATE") || sql.contains("FOR NO KEY UPDATE"))
            extra += "ForUpdate";
        return prefix + suffix + extra;
    }

    static void repositories() throws Exception {
        List<Source> sources = parse(javaFiles("src/main/java"));
        record Constructor(
            String repository,
            int index
        ) {
        }
        Map<String, Constructor> constructors = new HashMap<>();
        int queryCount = 0, repositoryCount = 0;
        for (Source source : sources) {
            String pkg = source.tree().getPackageName().toString();
            if (!pkg.startsWith(BASE + ".service.") && !pkg.equals(BASE + ".security"))
                continue;
            if (!source.content().matches("(?s).*\\bSqlStore\\s+db\\b.*"))
                continue;
            String className = source.file().getFileName().toString().replace(".java", "");
            String repository = className.endsWith("Service")
                ? className.substring(0, className.length() - 7) + "Repository"
                : className + "Repository";
            String feature = pkg.substring(pkg.lastIndexOf('.') + 1), repoPkg = BASE + ".repository." + feature;
            StringBuilder members = new StringBuilder();
            Map<String, String> generated = new LinkedHashMap<>();
            Map<String, Integer> usedNames = new HashMap<>();
            List<Edit> edits = new ArrayList<>();
            new TreeScanner<Void, Void>() {

                @Override
                public Void visitMethodInvocation(
                    MethodInvocationTree call,
                    Void unused) {
                    if (!(call.getMethodSelect() instanceof MemberSelectTree select))
                        return super.visitMethodInvocation(call, unused);
                    String scope = select.getExpression().toString(), operation = select.getIdentifier().toString();
                    if (!Set.of("db", "this.db").contains(scope)
                        || !Set.of("one", "optional", "list", "scalar", "update", "insert").contains(operation)
                        || call.getArguments().isEmpty())
                        return super.visitMethodInvocation(call, unused);
                    ExpressionTree sql = call.getArguments().getFirst();
                    if (!literal(sql)) {
                        System.out.println("Dynamic SQL remains for review: " + className + " " + slice(source, sql));
                        return super.visitMethodInvocation(call, unused);
                    }
                    String sqlCode = slice(source, sql), key = operation + ":" + literalValue(sql),
                        name = generated.get(key);
                    if (name == null) {
                        String proposed = methodName(operation, literalValue(sql));
                        int occurrence = usedNames.merge(proposed, 1, Integer::sum);
                        name = proposed + (occurrence == 1 ? "" : occurrence);
                        generated.put(key, name);
                        String type = switch (operation) {
                            case "one" -> "Map<String,Object>";
                            case "optional" -> "Optional<Map<String,Object>>";
                            case "list" -> "List<Map<String,Object>>";
                            case "scalar" -> "Object";
                            case "update" -> "int";
                            case "insert" -> "UUID";
                            default -> throw new IllegalArgumentException(operation);
                        };
                        String arguments = operation.equals("insert") ? "Map<String,Object> fields" : "Object... args";
                        String forwarding = operation.equals("insert") ? "fields" : "args";
                        members.append("    public ").append(type).append(' ').append(name).append('(')
                            .append(arguments).append(") {\n        return db.").append(operation).append('(')
                            .append(sqlCode).append(", ").append(forwarding).append(");\n    }\n\n");
                    }
                    edits.add(
                        new Edit(
                            start(source, call.getMethodSelect()),
                            end(source, call.getMethodSelect()),
                            scope + "." + name
                        )
                    );
                    int removeEnd =
                        call.getArguments().size() > 1 ? start(source, call.getArguments().get(1)) : end(source, sql);
                    edits.add(new Edit(start(source, sql), removeEnd, ""));
                    return super.visitMethodInvocation(call, unused);
                }

                @Override
                public Void visitMethod(
                    MethodTree method,
                    Void unused) {
                    if (method.getName().contentEquals("<init>"))
                        for (int i = 0; i < method.getParameters().size(); i++)
                            if (method.getParameters().get(i).getType().toString().equals("SqlStore"))
                                constructors.put(className, new Constructor(repoPkg + "." + repository, i));
                    return super.visitMethod(method, unused);
                }
            }.scan(source.tree(), null);
            if (generated.isEmpty())
                continue;
            String value = apply(source.content(), edits).replaceAll("\\bSqlStore\\b", repository);
            value = value.replace(
                "import " + BASE + ".repository." + repository + ";",
                "import " + repoPkg + "." + repository + ";"
            );
            if (!value.contains("import " + repoPkg + "." + repository + ";"))
                value = value.replace(
                    "package " + pkg + ";",
                    "package " + pkg + ";\n\nimport " + repoPkg + "." + repository + ";"
                );
            write(source.file(), value);
            String repositoryAnnotation =
                repository.equals("JobRepository") ? "@Repository(\"marketJobRepository\")" : "@Repository";
            String repo = "package " + repoPkg + ";\n\nimport " + BASE
                + (".repository.SqlStore;\nimport java.util.*;\nimport "
                    + "org.springframework.stereotype.Repository;\n\n/** SQL persistence " + "operations used by ")
                + className + ". */\n" + repositoryAnnotation + "\npublic class " + repository
                + " {\n    private final SqlStore db;\n\n    public " + repository
                + ("(SqlStore db) { this.db=db; }\n\n    public Object json(Object value) "
                    + "{ return db.json(value); }\n\n")
                + members + "}\n";
            write(root.resolve("src/main/java/" + repoPkg.replace('.', '/') + "/" + repository + ".java"), repo);
            queryCount += generated.size();
            repositoryCount++;
        }
        List<Path> all = new ArrayList<>(javaFiles("src/main/java"));
        all.addAll(javaFiles("src/test/java"));
        for (Source source : parse(all)) {
            List<Edit> edits = new ArrayList<>();
            Set<String> imports = new TreeSet<>();
            new TreeScanner<Void, Void>() {

                @Override
                public Void visitNewClass(
                    NewClassTree node,
                    Void unused) {
                    String name = node.getIdentifier().toString();
                    Constructor ctor = constructors.get(name);
                    if (ctor != null && node.getArguments().size() > ctor.index()) {
                        Tree arg = node.getArguments().get(ctor.index());
                        String shortName = ctor.repository().substring(ctor.repository().lastIndexOf('.') + 1);
                        edits.add(new Edit(start(source, arg), start(source, arg), "new " + shortName + "("));
                        edits.add(new Edit(end(source, arg), end(source, arg), ")"));
                        imports.add("import " + ctor.repository() + ";");
                    }
                    return super.visitNewClass(node, unused);
                }
            }.scan(source.tree(), null);
            if (!edits.isEmpty()) {
                String value = apply(source.content(), edits), pkg = source.tree().getPackageName().toString();
                for (String imp : imports)
                    if (!value.contains(imp))
                        value = value.replace("package " + pkg + ";", "package " + pkg + ";\n\n" + imp);
                write(source.file(), value);
            }
        }
        System.out.println(
            "Extracted " + queryCount + " SQL operations into " + repositoryCount
                + " workflow repositories; updated direct constructor usage in tests."
        );
    }

    public static void main(
        String[] args)
        throws Exception {
        root = Path.of("").toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("pom.xml")))
            throw new IllegalStateException("Run from the backend Maven project");
        if (args.length != 1)
            throw new IllegalArgumentException("Specify packages or repositories");
        switch (args[0]) {
            case "packages" -> packages();
            case "repositories" -> repositories();
            default -> throw new IllegalArgumentException("Unknown mode");
        }
    }
}
