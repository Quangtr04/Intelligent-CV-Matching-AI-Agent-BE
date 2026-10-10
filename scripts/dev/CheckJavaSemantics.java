import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Compares Java syntax trees before and after formatting without compiling or
 * resolving dependencies. Whitespace and comments are ignored. Concatenated
 * String literals are compared by their decoded value.
 */
public final class CheckJavaSemantics {

    private CheckJavaSemantics() {
    }

    public static void main(
        String[] arguments)
        throws IOException {
        if (arguments.length != 2 || !List.of("snapshot", "check").contains(arguments[0])) {
            throw new IllegalArgumentException(
                ("Usage: java scripts/dev/CheckJavaSemantics.java <snapshot|check> " + "<snapshot-directory>")
            );
        }

        Path root = Path.of("").toAbsolutePath().normalize();
        Path snapshot = root.resolve(arguments[1]).normalize();
        Map<String, String> sources = readSources(root);

        if (arguments[0].equals("snapshot")) {
            writeSnapshot(snapshot, sources);
            System.out.println("Saved Java syntax snapshot for " + sources.size() + " files: " + snapshot);
            return;
        }

        compare(snapshot, sources);
    }

    private static Map<String, String> readSources(
        Path root)
        throws IOException {
        Map<String, String> sources = new TreeMap<>();

        for (String directory : List.of("src", "scripts/dev")) {
            Path sourceDirectory = root.resolve(directory);
            if (!Files.isDirectory(sourceDirectory)) {
                continue;
            }

            try (var files = Files.walk(sourceDirectory)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                    if (file.getFileName().toString().equals("CheckJavaSemantics.java")) {
                        continue;
                    }

                    String relative = root.relativize(file).toString().replace('\\', '/');
                    sources.put(relative, canonical(relative, Files.readString(file)));
                }
            }
        }

        return sources;
    }

    private static String canonical(
        String name,
        String source)
        throws IOException {
        ParsedSource parsed = parse(name, source);
        List<Replacement> replacements = new ArrayList<>();

        new TreeScanner<Void, Void>() {

            @Override
            public Void scan(
                Tree tree,
                Void ignored) {
                if (tree instanceof BinaryTree || tree instanceof ParenthesizedTree) {
                    String value = constantString(tree);
                    if (value != null) {
                        long start = parsed.positions().getStartPosition(parsed.unit(), tree);
                        long end = parsed.positions().getEndPosition(parsed.unit(), tree);
                        if (start < 0 || end < start) {
                            throw new IllegalStateException("Missing source position in " + name);
                        }

                        replacements.add(new Replacement((int) start, (int) end, stringLiteral(value)));
                        return null;
                    }
                }

                return super.scan(tree, ignored);
            }
        }.scan(parsed.unit(), null);

        if (replacements.isEmpty()) {
            return syntaxOnly(parsed.unit().toString());
        }

        StringBuilder normalized = new StringBuilder(source);
        replacements.sort(Comparator.comparingInt(Replacement::start).reversed());
        for (Replacement replacement : replacements) {
            normalized.replace(replacement.start(), replacement.end(), replacement.source());
        }

        return syntaxOnly(parse(name, normalized.toString()).unit().toString());
    }

    private static String syntaxOnly(
        String source) {
        StringBuilder syntax = new StringBuilder();
        boolean whitespace = false;

        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (source.startsWith("/**", index)) {
                int end = source.indexOf("*/", index + 3);
                if (end < 0) {
                    throw new IllegalStateException("Unterminated Javadoc in the parsed Java syntax tree");
                }

                index = end + 1;
                whitespace = true;
                continue;
            }

            if (Character.isWhitespace(character)) {
                whitespace = true;
                continue;
            }

            if (whitespace && !syntax.isEmpty()) {
                syntax.append(' ');
            }

            whitespace = false;
            syntax.append(character);
            if (character == '"' || character == '\'') {
                char quote = character;
                while (++index < source.length()) {
                    char quoted = source.charAt(index);
                    syntax.append(quoted);
                    if (quoted == '\\' && index + 1 < source.length()) {
                        syntax.append(source.charAt(++index));
                    } else if (quoted == quote) {
                        break;
                    }
                }
            }
        }

        return syntax.toString();
    }

    private static String constantString(
        Tree tree) {
        if (tree instanceof LiteralTree literal && literal.getValue() instanceof String value) {
            return value;
        }

        if (tree instanceof ParenthesizedTree parentheses) {
            return constantString(parentheses.getExpression());
        }

        if (tree instanceof BinaryTree binary && binary.getKind() == Tree.Kind.PLUS) {
            String left = constantString(binary.getLeftOperand());
            String right = constantString(binary.getRightOperand());
            if (left != null && right != null) {
                return left + right;
            }
        }

        return null;
    }

    private static String stringLiteral(
        String value) {
        StringBuilder literal = new StringBuilder("\"");

        for (char character : value.toCharArray()) {
            switch (character) {
                case '\\' -> literal.append("\\\\");
                case '"' -> literal.append("\\\"");
                case '\b' -> literal.append("\\b");
                case '\t' -> literal.append("\\t");
                case '\n' -> literal.append("\\n");
                case '\f' -> literal.append("\\f");
                case '\r' -> literal.append("\\r");
                default -> {
                    if (character < 32 || character == 127) {
                        literal.append(String.format("\\%03o", (int) character));
                    } else {
                        literal.append(character);
                    }
                }
            }
        }

        return literal.append('"').toString();
    }

    private static ParsedSource parse(
        String name,
        String source)
        throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("A JDK is required for Java syntax-tree validation");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject sourceFile =
            new SimpleJavaFileObject(URI.create("string:///" + name.replace(" ", "%20")), JavaFileObject.Kind.SOURCE) {

                @Override
                public CharSequence getCharContent(
                    boolean ignoreEncodingErrors) {
                    return source;
                }
            };

        try (StandardJavaFileManager manager =
            compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler
                .getTask(null, manager, diagnostics, List.of("-proc:none"), null, List.of(sourceFile));
            CompilationUnitTree unit = task.parse().iterator().next();

            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    throw new IllegalStateException("Invalid Java source " + name + ": " + diagnostic);
                }
            }

            return new ParsedSource(unit, Trees.instance(task).getSourcePositions());
        }
    }

    private static void writeSnapshot(
        Path snapshot,
        Map<String, String> sources)
        throws IOException {
        Files.createDirectories(snapshot);
        Files.write(snapshot.resolve("files.txt"), sources.keySet(), StandardCharsets.UTF_8);

        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Path file = snapshot.resolve(entry.getKey() + ".ast");
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
        }
    }

    private static void compare(
        Path snapshot,
        Map<String, String> sources)
        throws IOException {
        List<String> files = Files.readAllLines(snapshot.resolve("files.txt"), StandardCharsets.UTF_8);
        List<String> failures = new ArrayList<>();

        for (String name : files) {
            String original = syntaxOnly(Files.readString(snapshot.resolve(name + ".ast"), StandardCharsets.UTF_8));
            String current = sources.get(name);
            if (current == null || !original.equals(current)) {
                failures.add(name);
                if (current != null) {
                    Path difference = snapshot.resolve(name + ".after.ast");
                    Files.writeString(difference, current, StandardCharsets.UTF_8);
                }
            }
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException("Java syntax changed in " + failures.size() + " files: " + failures);
        }

        long added = sources.keySet().stream().filter(name -> !files.contains(name)).count();
        System.out.println(
            "Java syntax is unchanged for " + files.size() + " original files (" + added
                + " new files excluded from the baseline)."
        );
    }

    private record ParsedSource(
        CompilationUnitTree unit,
        SourcePositions positions
    ) {
    }

    private record Replacement(
        int start,
        int end,
        String source
    ) {
    }
}
