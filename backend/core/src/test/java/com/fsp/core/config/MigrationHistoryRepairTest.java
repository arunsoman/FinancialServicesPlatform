package com.fsp.core.config;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Migration hygiene: a version is used by exactly one file (a clash stops every database at
 * boot), retired script names never come back, and the history repair removes exactly the
 * retired rows.
 */
class MigrationHistoryRepairTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db");
    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+(?:[._]\\d+)*)__.+\\.sql$");

    private static List<Path> versionedFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MIGRATIONS)) {
            return files.filter(p -> VERSIONED.matcher(p.getFileName().toString()).matches()).toList();
        }
    }

    @Test
    void everyMigrationVersionIsUsedByExactlyOneFile() throws IOException {
        Map<String, List<String>> byVersion = versionedFiles().stream()
            .map(p -> p.getFileName().toString())
            .collect(Collectors.groupingBy(n -> {
                Matcher m = VERSIONED.matcher(n);
                m.matches();
                return m.group(1).replace('_', '.');
            }));
        Map<String, List<String>> clashes = byVersion.entrySet().stream().filter(e -> e.getValue().size() > 1)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(clashes).as("two migrations share a version — renumber the newer one").isEmpty();
    }

    @Test
    void retiredScriptNamesNeverComeBack() throws IOException {
        List<String> names = versionedFiles().stream().map(p -> p.getFileName().toString()).toList();
        assertThat(names).doesNotContainAnyElementsOf(MigrationHistoryRepair.RETIRED_SCRIPTS);
    }

    @Test
    void renumberedInvoiceMigrationsAreReRunnable() throws IOException {
        String v41 = Files.readString(MIGRATIONS.resolve("migration/V41__invoices.sql"));
        assertThat(v41).doesNotContainPattern("CREATE (UNIQUE )?(TABLE|INDEX) (?!IF NOT EXISTS)");
        String v40 = Files.readString(MIGRATIONS.resolve("migration/V40__invoice_permissions.sql"));
        assertThat(v40.split("INSERT INTO", -1).length - 1)
            .isEqualTo(v40.split("ON CONFLICT", -1).length - 1);
    }

    @Test
    void repairDeletesTheRetiredHistoryRows() throws Exception {
        DataSource ds = Mockito.mock(DataSource.class);
        Connection c = Mockito.mock(Connection.class);
        PreparedStatement exists = Mockito.mock(PreparedStatement.class);
        PreparedStatement delete = Mockito.mock(PreparedStatement.class);
        ResultSet rs = Mockito.mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(c);
        when(c.getAutoCommit()).thenReturn(true);
        when(c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")).thenReturn(exists);
        when(exists.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getBoolean(1)).thenReturn(true);
        when(c.prepareStatement("DELETE FROM \"flyway_schema_history\" WHERE script = ?")).thenReturn(delete);
        when(delete.executeUpdate()).thenReturn(1);

        assertThat(MigrationHistoryRepair.repair(ds, "\"flyway_schema_history\"")).isEqualTo(2);
        verify(delete).setString(1, "V39__invoice_permissions.sql");
        verify(delete).setString(1, "V40__invoices.sql");
    }

    @Test
    void aBrandNewDatabaseIsLeftAlone() throws Exception {
        DataSource ds = Mockito.mock(DataSource.class);
        Connection c = Mockito.mock(Connection.class);
        PreparedStatement exists = Mockito.mock(PreparedStatement.class);
        ResultSet rs = Mockito.mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(c);
        when(c.prepareStatement("SELECT to_regclass(?) IS NOT NULL")).thenReturn(exists);
        when(exists.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getBoolean(1)).thenReturn(false);

        assertThat(MigrationHistoryRepair.repair(ds, "\"flyway_schema_history\"")).isZero();
        verify(c, never()).prepareStatement(Mockito.startsWith("DELETE"));
    }
}
