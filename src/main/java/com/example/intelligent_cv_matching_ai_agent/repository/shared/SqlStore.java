package com.example.intelligent_cv_matching_ai_agent.repository.shared;

import com.example.intelligent_cv_matching_ai_agent.exception.shared.ApiException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Parameterized JDBC access. Identifiers come exclusively from server code. */
@Repository
public class SqlStore {

    private final JdbcTemplate jdbc;

    private final JsonMapper mapper = JsonMapper.builder().build();

    public SqlStore(
        JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record JsonValue(
        String content
    ) {
    }

    public Object json(
        Object value) {
        return new JsonValue(mapper.writeValueAsString(value));
    }

    private PreparedStatementSetter bind(
        Object[] args) {
        return statement -> {
            for (int i = 0; i < args.length; i++) {
                Object value = args[i];
                if (value instanceof JsonValue j)
                    statement.setObject(i + 1, j.content(), Types.OTHER);
                else if (value instanceof Enum<?> e)
                    statement.setString(i + 1, e.name());
                else if (value instanceof java.time.Instant instant)
                    statement.setObject(i + 1, instant.atOffset(java.time.ZoneOffset.UTC));
                else
                    statement.setObject(i + 1, value);
            }
        };
    }

    private Map<String, Object> row(
        ResultSet rs,
        int rowNumber)
        throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
            Object value = rs.getObject(i);
            String type = rs.getMetaData().getColumnTypeName(i);
            if (value != null && ("jsonb".equals(type) || "json".equals(type)))
                value = mapper.readValue(value.toString(), Object.class);
            if (value instanceof java.sql.Array array) {
                value = array.getArray();
                array.free();
            }
            row.put(rs.getMetaData().getColumnLabel(i), value);
        }
        return row;
    }

    public List<Map<String, Object>> list(
        String sql,
        Object... args) {
        return jdbc.query(sql, bind(args), this::row);
    }

    public Optional<Map<String, Object>> optional(
        String sql,
        Object... args) {
        return list(sql, args).stream().findFirst();
    }

    public Map<String, Object> one(
        String sql,
        Object... args) {
        return optional(sql, args).orElseThrow(() -> ApiException.notFound("Resource not found"));
    }

    public Object scalar(
        String sql,
        Object... args) {
        return one(sql, args).values().iterator().next();
    }

    public int update(
        String sql,
        Object... args) {
        return jdbc.update(sql, bind(args));
    }

    public UUID insert(
        String table,
        Map<String, Object> fields) {
        identifier(table);
        if (fields.isEmpty())
            return (UUID) scalar("INSERT INTO " + table + " DEFAULT VALUES RETURNING id");
        fields.keySet().forEach(SqlStore::identifier);
        String columns = String.join(",", fields.keySet());
        String placeholders = String.join(",", Collections.nCopies(fields.size(), "?"));
        return (UUID) scalar(
            "INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ") RETURNING id",
            fields.values().toArray()
        );
    }

    private static void identifier(
        String value) {
        if (!value.matches("[a-z][a-z0-9_]*"))
            throw new IllegalArgumentException("Invalid SQL identifier");
    }
}
