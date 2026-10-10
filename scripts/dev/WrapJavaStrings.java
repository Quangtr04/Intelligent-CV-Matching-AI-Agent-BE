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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Splits long ordinary Java String literals into constant concatenations without changing their value.
 * Text blocks are left unchanged. Run the configured Java formatter after applying these replacements.
 */
public final class WrapJavaStrings {

    private static final int LINE_LIMIT = 120;

    private static final int LONG_LITERAL_LENGTH = 85;

    private static final int CHUNK_CONTENT_LENGTH = 70;

    private WrapJavaStrings() {
    }

    public static void main(
        String[] arguments)
        throws IOException {
        if (arguments.length != 1 || !List.of("check", "apply").contains(arguments[0])) {
            throw new IllegalArgumentException("Usage: java scripts/dev/WrapJavaStrings.java <check|apply>");
        }

        boolean apply = arguments[0].equals("apply");
        Path root = Path.of("").toAbsolutePath().normalize();
        int literalCount = 0;
        int fileCount = 0;

        for (Path file : sourceFiles(root)) {
            Path resolvedFile = checkedSource(root, file);

            String source = Files.readString(resolvedFile, StandardCharsets.UTF_8);
            List<Replacement> replacements = replacements(resolvedFile, source);
            if (replacements.isEmpty()) {
                continue;
            }

            literalCount += replacements.size();
            fileCount++;
            if (!apply) {
                System.out.println(root.relativize(resolvedFile) + ": " + replacements.size() + " long literals");
                continue;
            }

            StringBuilder formatted = new StringBuilder(source);
            replacements.sort(Comparator.comparingInt(Replacement::start).reversed());
            for (Replacement replacement : replacements) {
                formatted.replace(replacement.start(), replacement.end(), replacement.source());
            }

            Files.writeString(resolvedFile, formatted, StandardCharsets.UTF_8);
        }

        System.out.println(
            (apply ? "Wrapped " : "Found ") + literalCount + " long literals in " + fileCount + " Java files."
        );
    }

    private static Path checkedSource(
        Path root,
        Path file) {
        Path resolvedFile = file.toAbsolutePath().normalize();
        if (!resolvedFile.startsWith(root)) {
            throw new IllegalArgumentException("Source file is outside the workspace: " + file);
        }

        for (Path current = resolvedFile; current != null && !current.equals(root); current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Symbolic links are not supported for source rewriting: " + current);
            }
        }

        return resolvedFile;
    }

    private static List<Path> sourceFiles(
        Path root)
        throws IOException {
        List<Path> sources = new ArrayList<>();

        for (String directory : List.of("src", "scripts/dev")) {
            Path sourceDirectory = root.resolve(directory);
            if (!Files.isDirectory(sourceDirectory)) {
                continue;
            }

            try (var files = Files.walk(sourceDirectory)) {
                sources.addAll(files.filter(path -> path.toString().endsWith(".java")).sorted().toList());
            }
        }

        return sources;
    }

    private static List<Replacement> replacements(
        Path file,
        String source)
        throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("A JDK is required for Java String-literal wrapping");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject sourceFile = new SimpleJavaFileObject(file.toUri(), JavaFileObject.Kind.SOURCE) {

            @Override
            public CharSequence getCharContent(
                boolean ignoreEncodingErrors) {
                return source;
            }
        };

        try (StandardJavaFileManager manager =
            compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(
                null,
                manager,
                diagnostics,
                List.of("-proc:none", "-XDallowStringFolding=false"),
                null,
                List.of(sourceFile)
            );
            CompilationUnitTree unit = task.parse().iterator().next();

            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    throw new IllegalStateException("Invalid Java source " + file + ": " + diagnostic);
                }
            }

            SourcePositions positions = Trees.instance(task).getSourcePositions();
            List<Replacement> replacements = new ArrayList<>();
            new TreeScanner<Void, Void>() {

                @Override
                public Void scan(
                    Tree tree,
                    Void ignored) {
                    if (!(tree instanceof LiteralTree) && !(tree instanceof BinaryTree)
                        && !(tree instanceof ParenthesizedTree)) {
                        return super.scan(tree, ignored);
                    }

                    String value = constantString(tree, unit, positions, source);
                    if (value == null) {
                        return super.scan(tree, ignored);
                    }

                    long startPosition = positions.getStartPosition(unit, tree);
                    long endPosition = positions.getEndPosition(unit, tree);
                    if (startPosition < 0 || endPosition < startPosition) {
                        throw new IllegalStateException("Missing String-literal source position in " + file);
                    }

                    int start = (int) startPosition;
                    int end = (int) endPosition;
                    long endColumn = unit.getLineMap().getColumnNumber(end - 1);
                    if (end - start <= LONG_LITERAL_LENGTH && endColumn <= LINE_LIMIT) {
                        return null;
                    }

                    List<String> chunks = split(value);
                    if (chunks.size() > 1) {
                        String original = withoutFormattingWhitespace(source.substring(start, end));
                        if (original == null) {
                            return super.scan(tree, ignored);
                        }

                        String replacement = "(" + String.join(" + ", chunks) + ")";
                        if (!original.equals(withoutFormattingWhitespace(replacement))) {
                            replacements.add(new Replacement(start, end, replacement));
                        }
                    }

                    return null;
                }
            }.scan(unit, null);

            return replacements;
        }
    }

    private static String constantString(
        Tree tree,
        CompilationUnitTree unit,
        SourcePositions positions,
        String source) {
        if (tree instanceof LiteralTree literal && literal.getValue() instanceof String value) {
            long start = positions.getStartPosition(unit, literal);
            if (start >= 0 && source.charAt((int) start) == '"' && !source.startsWith("\"\"\"", (int) start)) {
                return value;
            }

            return null;
        }

        if (tree instanceof ParenthesizedTree parentheses) {
            return constantString(parentheses.getExpression(), unit, positions, source);
        }

        if (tree instanceof BinaryTree binary && binary.getKind() == Tree.Kind.PLUS) {
            String left = constantString(binary.getLeftOperand(), unit, positions, source);
            String right = constantString(binary.getRightOperand(), unit, positions, source);
            if (left != null && right != null) {
                return left + right;
            }
        }

        return null;
    }

    private static String withoutFormattingWhitespace(
        String source) {
        StringBuilder expression = new StringBuilder();

        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (source.startsWith("//", index) || source.startsWith("/*", index)) {
                return null;
            }

            if (Character.isWhitespace(character)) {
                continue;
            }

            expression.append(character);
            if (character == '"') {
                while (++index < source.length()) {
                    char quoted = source.charAt(index);
                    expression.append(quoted);
                    if (quoted == '\\' && index + 1 < source.length()) {
                        expression.append(source.charAt(++index));
                    } else if (quoted == '"') {
                        break;
                    }
                }
            }
        }

        return expression.toString();
    }

    private static List<String> split(
        String value) {
        List<String> chunks = new ArrayList<>();
        int start = 0;

        while (start < value.length()) {
            int end = start;
            int serializedLength = 0;
            int preferredEnd = -1;

            while (end < value.length()) {
                int characterLength = escaped(value.charAt(end)).length();
                if (serializedLength + characterLength > CHUNK_CONTENT_LENGTH) {
                    break;
                }

                serializedLength += characterLength;
                end++;
                char previous = value.charAt(end - 1);
                if (Character.isWhitespace(previous) || previous == ',' || previous == ';') {
                    preferredEnd = end;
                }
            }

            if (end < value.length() && preferredEnd > start) {
                end = preferredEnd;
            }

            if (end == start) {
                throw new IllegalStateException("A character cannot fit within the String-literal chunk limit");
            }

            chunks.add(stringLiteral(value.substring(start, end)));
            start = end;
        }

        return chunks;
    }

    private static String stringLiteral(
        String value) {
        StringBuilder literal = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            literal.append(escaped(character));
        }

        return literal.append('"').toString();
    }

    private static String escaped(
        char character) {
        return switch (character) {
            case '\\' -> "\\\\";
            case '"' -> "\\\"";
            case '\b' -> "\\b";
            case '\t' -> "\\t";
            case '\n' -> "\\n";
            case '\f' -> "\\f";
            case '\r' -> "\\r";
            default -> {
                if (character < 32 || character == 127) {
                    yield String.format("\\%03o", (int) character);
                }

                if (Character.isSurrogate(character)) {
                    yield String.format("\\u%04x", (int) character);
                }

                yield Character.toString(character);
            }
        };
    }

    private record Replacement(
        int start,
        int end,
        String source
    ) {
    }
}
