import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Regenerates the persistence model from the checked-in PostgreSQL schema. */
public class GenerateEntities {

    static final String BASE = "com.example.intelligent_cv_matching_ai_agent";

    static final Set<String> VECTOR_TABLES = Set.of("market_job_embeddings", "rag_chunk_embeddings");

    static final Map<String, String> SPECIAL = Map.ofEntries(
        Map.entry("ai_token_reservations", "AiTokenReservation"),
        Map.entry("ai_usage_logs", "AiUsageLog"),
        Map.entry("resume_analyses", "ResumeAnalysis"),
        Map.entry("gap_analyses", "GapAnalysis"),
        Map.entry("job_resume_analyses", "JobResumeAnalysis"),
        Map.entry("salary_estimations", "SalaryEstimation"),
        Map.entry("system_analytics", "SystemAnalytics")
    );

    record Column(
        String sqlName,
        String sqlType,
        String definition
    ) {
    }

    record TableModel(
        String table,
        String name,
        List<Column> columns,
        List<String> primary
    ) {
    }

    static String camel(
        String name) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char ch : name.toCharArray()) {
            if (ch == '_')
                upper = true;
            else {
                out.append(upper ? Character.toUpperCase(ch) : ch);
                upper = false;
            }
        }
        return out.toString();
    }

    static String pascal(
        String name) {
        String value = camel(name);
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    static String entityName(
        String table) {
        if (SPECIAL.containsKey(table))
            return SPECIAL.get(table);
        String singular = table.endsWith("ies")
            ? table.substring(0, table.length() - 3) + "y"
            : table.endsWith("s") ? table.substring(0, table.length() - 1) : table;
        return pascal(singular);
    }

    static String javaType(
        String sql) {
        if (sql.equals("uuid"))
            return "UUID";
        if (sql.equals("uuid[]"))
            return "UUID[]";
        if (sql.equals("boolean"))
            return "Boolean";
        if (sql.equals("smallint"))
            return "Short";
        if (sql.equals("int") || sql.equals("integer"))
            return "Integer";
        if (sql.equals("bigint"))
            return "Long";
        if (sql.startsWith("decimal") || sql.startsWith("numeric"))
            return "BigDecimal";
        if (sql.equals("timestamptz"))
            return "OffsetDateTime";
        if (sql.equals("date"))
            return "LocalDate";
        if (sql.equals("jsonb"))
            return "Object";
        if (sql.startsWith("vector") || sql.equals("inet") || sql.equals("text") || sql.startsWith("varchar")
            || sql.startsWith("char"))
            return "String";
        throw new IllegalArgumentException("Unmapped SQL type: " + sql);
    }

    static void write(
        Path root,
        Path relative,
        String value)
        throws Exception {
        Path destination = root.resolve(relative).normalize();
        if (!destination.startsWith(root))
            throw new IllegalArgumentException("Generated path escapes project");
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, value);
    }

    static String mappedPackage(
        Properties packages,
        String className,
        String layer) {
        String relative = packages.getProperty(className);
        if (relative == null)
            throw new IllegalArgumentException("Missing role package mapping: " + className);
        if (!relative.matches(layer + "(?:\\.[a-z][a-z0-9_]*)+"))
            throw new IllegalArgumentException("Unsafe role package mapping for " + className + ": " + relative);
        return BASE + "." + relative;
    }

    static Path sourcePath(
        String packageName,
        String className) {
        return Path.of("src/main/java", packageName.replace('.', '/'), className + ".java");
    }

    public static void main(
        String[] args)
        throws Exception {
        Path root = Path.of("").toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("pom.xml")))
            throw new IllegalStateException("Run from the backend Maven project");
        Properties packages = new Properties();
        try (var reader = Files.newBufferedReader(root.resolve("scripts/dev/role-packages.properties"))) {
            packages.load(reader);
        }
        String schema =
            Files.readString(root.resolve("src/main/resources/db/migration/V1__edunexus_complete_schema.sql")) + "\n"
                + Files.readString(root.resolve("src/main/resources/db/migration/V3__ai_token_reservations.sql"));
        Matcher tables = Pattern.compile("CREATE TABLE ([a-z_]+) \\(\\s*(.*?)\\n\\);", Pattern.DOTALL).matcher(schema);
        int tableCount = 0, columnCount = 0;
        List<TableModel> models = new ArrayList<>();
        while (tables.find()) {
            String table = tables.group(1), body = tables.group(2), name = entityName(table);
            List<Column> columns = new ArrayList<>();
            List<String> primary = new ArrayList<>();
            for (String line : body.split("\\R")) {
                Matcher col = Pattern.compile("^\\s*([a-z][a-z0-9_]*)\\s+([a-z]+(?:\\([\\d,]+\\))?(?:\\[\\])?)(.*)$")
                    .matcher(line);
                if (col.find()) {
                    Column c = new Column(col.group(1), col.group(2), col.group(3));
                    // V10 retains the V1 vector as a read-only legacy column and
                    // gives current embeddings their own 1024-dimensional column.
                    if (VECTOR_TABLES.contains(table) && c.sqlName().equals("embedding")) {
                        columns.add(new Column("embedding", "vector(1024)", ""));
                        columns.add(new Column("embedding_legacy_1536", "vector(1536)", ""));
                    } else {
                        columns.add(c);
                    }
                    if (c.definition().contains("PRIMARY KEY"))
                        primary.add(c.sqlName());
                }
            }
            if (table.equals("resume_templates")) {
                columns.add(new Column("description", "varchar(2000)", " NOT NULL DEFAULT ''"));
                columns.add(new Column("html_content", "text", " NOT NULL"));
            }
            if (table.equals("candidate_profiles")) {
                columns.add(new Column("expected_salary", "numeric(14,2)", ""));
            }
            if (table.equals("resume_analyses")) {
                columns.add(new Column("writing_quality_json", "jsonb", ""));
            }
            Matcher composite = Pattern.compile("PRIMARY KEY \\(([^)]+)\\)").matcher(body);
            if (composite.find())
                for (String part : composite.group(1).split(","))
                    primary.add(part.trim());
            if (primary.isEmpty())
                throw new IllegalArgumentException("Missing primary key: " + table);
            models.add(new TableModel(table, name, List.copyOf(columns), List.copyOf(primary)));
            tableCount++;
            columnCount += columns.size();
        }
        if (tableCount != 76 || columnCount != 713)
            throw new IllegalStateException(
                "Unexpected model coverage: " + tableCount + " tables, " + columnCount + " columns"
            );
        // Validate every generated destination before touching any source file.
        for (TableModel model : models) {
            String entityPackage = mappedPackage(packages, model.name(), "entity");
            mappedPackage(packages, model.name() + "JpaRepository", "repository");
            if (model.primary().size() > 1 && packages.containsKey(model.name() + "Id")
                && !entityPackage.equals(mappedPackage(packages, model.name() + "Id", "entity")))
                throw new IllegalArgumentException(
                    "Composite ID must share its entity package: " + model.name() + "Id"
                );
        }
        for (TableModel model : models) {
            String table = model.table(), name = model.name();
            List<Column> columns = model.columns();
            List<String> primary = model.primary();
            String entityPackage = mappedPackage(packages, name, "entity");
            String repositoryPackage = mappedPackage(packages, name + "JpaRepository", "repository");
            StringBuilder entity = new StringBuilder("package " + entityPackage + ";\n\n");
            entity.append(
                ("import com.fasterxml.jackson.annotation.JsonIgnore;\nimport "
                    + "com.fasterxml.jackson.annotation.JsonProperty;\nimport "
                    + "jakarta.persistence.*;\nimport java.math.BigDecimal;\nimport "
                    + "java.time.LocalDate;\nimport java.time.OffsetDateTime;\nimport "
                    + "java.util.UUID;\nimport lombok.Getter;\nimport "
                    + "lombok.NoArgsConstructor;\nimport lombok.Setter;\nimport "
                    + "org.hibernate.annotations.ColumnTransformer;\nimport "
                    + "org.hibernate.annotations.DynamicInsert;\nimport "
                    + "org.hibernate.annotations.DynamicUpdate;\nimport "
                    + "org.hibernate.annotations.JdbcTypeCode;\nimport "
                    + "org.hibernate.annotations.UuidGenerator;\nimport " + "org.hibernate.type.SqlTypes;\n\n")
            );
            entity.append("/** Persistence model for the ").append(table)
                .append(
                    (" table; foreign keys retain their explicit UUID identifiers. */\n" + "@Entity\n@Table(name = \"")
                ).append(table).append("\")\n@Getter\n@Setter\n@NoArgsConstructor\n@DynamicInsert\n@DynamicUpdate\n");
            if (primary.size() > 1)
                entity.append("@IdClass(").append(name).append("Id.class)\n");
            entity.append("public class ").append(name).append(" {\n");
            for (Column c : columns) {
                boolean pk = primary.contains(c.sqlName());
                entity.append("    @JsonProperty(\"").append(c.sqlName()).append("\")\n");
                if (c.sqlName().matches(".*(?:password_hash|token_hash|code_hash|session_key|api_key|secret).*")
                    || c.sqlName().equals("embedding_legacy_1536") || table.equals("candidate_profiles")
                        && Set.of("expected_salary_min", "expected_salary_max").contains(c.sqlName()))
                    entity.append("    @JsonIgnore\n");
                if (pk) {
                    entity.append("    @Id\n");
                    if (primary.size() == 1 && c.sqlName().equals("id") && c.sqlType().equals("uuid"))
                        entity.append("    @GeneratedValue\n    @UuidGenerator\n");
                }
                if (c.sqlType().equals("jsonb"))
                    entity.append("    @JdbcTypeCode(SqlTypes.JSON)\n");
                if (c.sqlType().endsWith("[]"))
                    entity.append("    @JdbcTypeCode(SqlTypes.ARRAY)\n");
                if (c.sqlType().startsWith("vector"))
                    entity.append("    @ColumnTransformer(read = \"").append(c.sqlName())
                        .append("::text\", write = \"?::vector\")\n");
                if (c.sqlType().equals("inet"))
                    entity.append("    @ColumnTransformer(read = \"").append(c.sqlName())
                        .append("::text\", write = \"?::inet\")\n");
                entity.append("    @Column(name = \"").append(c.sqlName()).append("\", nullable = ")
                    .append(!(pk || c.definition().contains("NOT NULL")));
                if (c.sqlName().equals("embedding_legacy_1536") || table.equals("candidate_profiles")
                    && Set.of("expected_salary_min", "expected_salary_max").contains(c.sqlName()))
                    entity.append(", insertable = false, updatable = false");
                Matcher length = Pattern.compile("(?:varchar|char)\\((\\d+)\\)").matcher(c.sqlType());
                if (length.matches())
                    entity.append(", length = ").append(length.group(1));
                Matcher decimal = Pattern.compile("(?:decimal|numeric)\\((\\d+),(\\d+)\\)").matcher(c.sqlType());
                if (decimal.matches())
                    entity.append(", precision = ").append(decimal.group(1)).append(", scale = ")
                        .append(decimal.group(2));
                if (Set.of("text", "jsonb", "inet", "uuid[]", "timestamptz").contains(c.sqlType())
                    || c.sqlType().startsWith("vector"))
                    entity.append(", columnDefinition = \"").append(c.sqlType()).append("\"");
                entity.append(")\n    private ").append(javaType(c.sqlType())).append(" ").append(camel(c.sqlName()))
                    .append(";\n\n");
            }
            entity.append("}\n");
            write(root, sourcePath(entityPackage, name), entity.toString());
            if (primary.size() > 1) {
                StringBuilder id = new StringBuilder(
                    "package " + entityPackage
                        + (";\n\nimport java.io.Serializable;\nimport java.util.UUID;\nimport "
                            + "lombok.AllArgsConstructor;\nimport lombok.Data;\nimport "
                            + "lombok.NoArgsConstructor;\n\n/** Composite identifier for ")
                        + table + ". */\n@Data\n@NoArgsConstructor\n@AllArgsConstructor\npublic class " + name
                        + ("Id implements Serializable {\n    private static final long " + "serialVersionUID = 1L;\n")
                );
                for (String key : primary) {
                    Column c = columns.stream().filter(col -> col.sqlName().equals(key)).findFirst().orElseThrow();
                    id.append("    private ").append(javaType(c.sqlType())).append(" ").append(camel(key))
                        .append(";\n");
                }
                id.append("}\n");
                write(root, sourcePath(entityPackage, name + "Id"), id.toString());
            }
            String idType = primary.size() > 1
                ? name + "Id"
                : javaType(
                    columns.stream().filter(c -> c.sqlName().equals(primary.getFirst())).findFirst().orElseThrow()
                        .sqlType()
                );
            String repository = "package " + repositoryPackage + ";\n\nimport " + entityPackage + "." + name + ";\n"
                + (primary.size() > 1 ? "import " + entityPackage + "." + idType + ";\n" : "import java.util.UUID;\n")
                + ("import org.springframework.data.jpa.repository.JpaRepository;\n\n/** "
                    + "Typed persistence access for ")
                + table + ". */\npublic interface " + name + "JpaRepository extends JpaRepository<" + name + ", "
                + idType + "> {\n}\n";
            write(root, sourcePath(repositoryPackage, name + "JpaRepository"), repository);
        }
        System.out.println(
            "Generated " + tableCount + " entity models and JPA repositories; " + columnCount + " mapped columns."
        );
    }
}
