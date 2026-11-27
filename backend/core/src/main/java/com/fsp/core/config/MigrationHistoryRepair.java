package com.fsp.core.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Heals databases that applied a migration under a version number that was later given to a
 * different migration, so nobody has to fix their database by hand after pulling.
 *
 * <p>Flyway validates the history before it migrates: a history row "V39 invoice permissions"
 * against a repo file "V39 branch maintenance" stops core from starting. Before Flyway runs, this
 * strategy deletes the history rows of {@link #RETIRED_SCRIPTS} — script names that no longer
 * exist in the repo — and Flyway then applies the renumbered files. Each renumbered file is
 * re-runnable (IF NOT EXISTS / ON CONFLICT), so it applies cleanly over the objects the old run
 * already created. On every other database this is a no-op (one SELECT).
 *
 * <p>History:
 * <ul>
 *   <li>{@code V39__invoice_permissions.sql} → {@code V40__invoice_permissions.sql}</li>
 *   <li>{@code V40__invoices.sql} → {@code V41__invoices.sql}</li>
 * </ul>
 * (renumbered after {@code V39__branch_maintenance.sql} landed on the same branch.)
 *
 * <p>Rule for the future: only ever add a script here together with making its new file
 * re-runnable; never list a script whose name still exists in {@code db/migration}.
 */
@Slf4j
@Configuration
public class MigrationHistoryRepair {

    /** Script names applied on some databases under a version now used by another migration. */
    static final List<String> RETIRED_SCRIPTS = List.of(
        "V39__invoice_permissions.sql",
        "V40__invoices.sql");

    @Bean
    FlywayMigrationStrategy repairHistoryThenMigrate() {
        return flyway -> {
            repair(flyway.getConfiguration().getDataSource(), historyTable(flyway));
            flyway.migrate();
        };
    }

    /** The history table as Flyway names it: schema-qualified when a schema is configured. */
    static String historyTable(Flyway flyway) {
        var conf = flyway.getConfiguration();
        String schema = conf.getDefaultSchema();
        if (schema == null && conf.getSchemas() != null && conf.getSchemas().length > 0) schema = conf.getSchemas()[0];
        return schema == null ? quote(conf.getTable()) : quote(schema) + "." + quote(conf.getTable());
    }

    /** Deletes the history rows of retired scripts. Returns how many rows it removed. */
    static int repair(DataSource dataSource, String historyTable) {
        try (Connection c = dataSource.getConnection()) {
            if (!exists(c, historyTable)) return 0; // a brand-new database: nothing to heal
            int removed = 0;
            try (PreparedStatement del = c.prepareStatement("DELETE FROM " + historyTable + " WHERE script = ?")) {
                for (String script : RETIRED_SCRIPTS) {
                    del.setString(1, script);
                    int n = del.executeUpdate();
                    if (n > 0) {
                        log.warn("flyway_history_repaired script={} rows={} reason=renumbered_migration", script, n);
                        removed += n;
                    }
                }
            }
            if (!c.getAutoCommit()) c.commit();
            return removed;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not repair the Flyway history before migrating", e);
        }
    }

    private static boolean exists(Connection c, String table) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            q.setString(1, table);
            try (ResultSet rs = q.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
