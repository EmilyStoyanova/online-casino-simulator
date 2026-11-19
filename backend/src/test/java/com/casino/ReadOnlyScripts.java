package com.casino;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a read-only check of {@code infrastructure/neon} the way {@code readonly-query.sh} does: every
 * statement in a read-only transaction, so a script that tried to write would fail
 * ({@code default_transaction_read_only = on}). psql meta-commands and comment lines are removed; the
 * scripts contain no {@code ;} other than statement ends.
 */
public final class ReadOnlyScripts {

    private ReadOnlyScripts() {
    }

    /**
     * Runs {@code file} on {@code connection} (auto-commit) and returns one result per statement: its rows
     * separated by {@code |}, each row's values by {@code ,} ({@code null} for SQL NULL).
     */
    public static List<String> run(Connection connection, String file) throws SQLException {
        String script;
        try {
            script = Files.readString(Path.of("..", "infrastructure", "neon", file), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        String sql = script.lines().filter(line -> !line.startsWith("\\") && !line.strip().startsWith("--"))
                .reduce("", (all, line) -> all + line + "\n");
        List<String> results = new ArrayList<>();
        execute(connection, "SET default_transaction_read_only = on");
        try {
            for (String statement : sql.split(";")) {
                if (statement.isBlank()) {
                    continue;
                }
                try (Statement query = connection.createStatement(); ResultSet row = query.executeQuery(statement)) {
                    List<String> rows = new ArrayList<>();
                    while (row.next()) {
                        List<String> values = new ArrayList<>();
                        for (int column = 1; column <= row.getMetaData().getColumnCount(); column++) {
                            values.add(row.getString(column));
                        }
                        rows.add(String.join(",", values));
                    }
                    results.add(String.join("|", rows));
                }
            }
        } finally {
            execute(connection, "SET default_transaction_read_only = off");
        }
        return results;
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
