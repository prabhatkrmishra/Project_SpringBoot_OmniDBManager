package com.pkmprojects.mongodbserver.service;

import com.pkmprojects.mongodbserver.dto.TableInfo;
import com.pkmprojects.mongodbserver.dto.TableRowPage;
import com.pkmprojects.mongodbserver.error.DatabaseAlreadyExistsException;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.repository.PostgresDatabaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostgresExplorationServiceTest {

    @Mock
    private PostgresDatabaseRepository postgresRepository;

    private PostgresExplorationService service;

    @BeforeEach
    void setUp() {
        service = new PostgresExplorationService(postgresRepository, new DatabaseNameValidator());
    }

    // ── listTables ──────────────────────────────────────────────────

    @Test
    void listTablesReturnsTableInfos() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.listTables("myapp")).thenReturn(List.of("users", "orders"));
        when(postgresRepository.countRows("myapp", "users")).thenReturn(3L);
        when(postgresRepository.countRows("myapp", "orders")).thenReturn(7L);

        List<TableInfo> tables = service.listTables("myapp");

        assertThat(tables).extracting(TableInfo::name).containsExactly("users", "orders");
        assertThat(tables).extracting(TableInfo::rowCount).containsExactly(3L, 7L);
    }

    @Test
    void listTablesOnMissingDatabaseThrows() {
        when(postgresRepository.databaseExists("nope")).thenReturn(false);

        assertThatThrownBy(() -> service.listTables("nope"))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    @Test
    void listTablesRejectsInvalidDbName() {
        assertThatThrownBy(() -> service.listTables("MyApp"))
                .isInstanceOf(NameNotAllowedException.class);
        verify(postgresRepository, never()).listTables(anyString());
    }

    @Test
    void listTablesDegradesCountToZeroOnFailure() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.listTables("myapp")).thenReturn(List.of("users"));
        when(postgresRepository.countRows("myapp", "users")).thenThrow(new RuntimeException("boom"));

        List<TableInfo> tables = service.listTables("myapp");

        assertThat(tables).hasSize(1);
        assertThat(tables.get(0).rowCount()).isZero();
    }

    // ── getRows ─────────────────────────────────────────────────────

    @Test
    void getRowsFirstPage() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        when(postgresRepository.countRows("myapp", "users")).thenReturn(120L);
        when(postgresRepository.getTableColumns("myapp", "users")).thenReturn(List.of("name", "email"));
        when(postgresRepository.listRowsWithCtid("myapp", "users", 50, 0))
                .thenReturn(List.of(Map.of("name", "alice", "__pg_ctid", "(0,1)")));

        TableRowPage page = service.getRows("myapp", "users", 1);

        assertThat(page.totalCount()).isEqualTo(120L);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.hasPrev()).isFalse();
        assertThat(page.hasNext()).isTrue();
        assertThat(page.columns()).containsExactly("name", "email");
    }

    @Test
    void getRowsClampsExcessPageToLastPage() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        when(postgresRepository.countRows("myapp", "users")).thenReturn(120L);
        when(postgresRepository.getTableColumns("myapp", "users")).thenReturn(List.of("name"));
        when(postgresRepository.listRowsWithCtid("myapp", "users", 50, 100))
                .thenReturn(List.of(Map.of("name", "last")));

        TableRowPage page = service.getRows("myapp", "users", 9999);

        assertThat(page.page()).isEqualTo(3);
        assertThat(page.hasPrev()).isTrue();
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void getRowsClampsZeroPageToOne() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        when(postgresRepository.countRows("myapp", "users")).thenReturn(5L);
        when(postgresRepository.getTableColumns("myapp", "users")).thenReturn(List.of("name"));
        when(postgresRepository.listRowsWithCtid("myapp", "users", 50, 0))
                .thenReturn(List.of(Map.of("name", "a")));

        TableRowPage page = service.getRows("myapp", "users", 0);

        assertThat(page.page()).isEqualTo(1);
    }

    @Test
    void getRowsOnMissingTableThrows() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "nope")).thenReturn(false);

        assertThatThrownBy(() -> service.getRows("myapp", "nope", 1))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    @Test
    void getRowsRejectsInvalidTableName() {
        assertThatThrownBy(() -> service.getRows("myapp", "MyTable", 1))
                .isInstanceOf(NameNotAllowedException.class);
    }

    // ── createTable ─────────────────────────────────────────────────

    @Test
    void createTableSucceeds() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(false);

        service.createTable("myapp", "users", List.of("name", "email"));

        verify(postgresRepository).createTable("myapp", "users", List.of("name", "email"));
    }

    @Test
    void createTableNormalizesColumns() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(false);

        service.createTable("myapp", "users", List.of(" Name ", "EMAIL", "name", "  "));

        verify(postgresRepository).createTable("myapp", "users", List.of("name", "email"));
    }

    @Test
    void createTableRejectsReservedColumn() {
        assertThatThrownBy(() -> service.createTable("myapp", "users", List.of("__pg_ctid")))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> service.createTable("myapp", "users", List.of("__ctid")))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> service.createTable("myapp", "users", List.of("ctid")))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> service.createTable("myapp", "users", List.of("_csrf")))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> service.createTable("myapp", "users", List.of("__new_col")))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> service.createTable("myapp", "users", List.of("__new_val")))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void createTableRejectsDuplicateTable() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);

        assertThatThrownBy(() -> service.createTable("myapp", "users", List.of("name")))
                .isInstanceOf(DatabaseAlreadyExistsException.class);
    }

    @Test
    void createTableRejectsInvalidTableName() {
        assertThatThrownBy(() -> service.createTable("myapp", "MyTable", List.of()))
                .isInstanceOf(NameNotAllowedException.class);
    }

    // ── dropTable / truncateTable ───────────────────────────────────

    @Test
    void dropTableSucceeds() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);

        service.dropTable("myapp", "users");

        verify(postgresRepository).dropTable("myapp", "users");
    }

    @Test
    void dropTableOnMissingTableThrows() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "nope")).thenReturn(false);

        assertThatThrownBy(() -> service.dropTable("myapp", "nope"))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    @Test
    void truncateTableSucceeds() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);

        service.truncateTable("myapp", "users");

        verify(postgresRepository).truncateTable("myapp", "users");
    }

    // ── insertRow ───────────────────────────────────────────────────

    @Test
    void insertRowSucceedsAndAddsMissingColumns() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        when(postgresRepository.getTableColumns("myapp", "users")).thenReturn(List.of("name"));

        service.insertRow("myapp", "users", Map.of("name", "alice", "email", "alice@example.com"));

        verify(postgresRepository).executeInDatabase(eq("myapp"), contains("ADD COLUMN \"email\""));
        verify(postgresRepository).insertRow(eq("myapp"), eq("users"), argThat(m -> m.containsKey("name") && m.containsKey("email")));
    }

    @Test
    void insertRowDoesNotAddExistingColumns() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        when(postgresRepository.getTableColumns("myapp", "users")).thenReturn(List.of("name", "email"));

        service.insertRow("myapp", "users", Map.of("name", "alice", "email", "alice@example.com"));

        verify(postgresRepository, never()).executeInDatabase(anyString(), anyString());
        verify(postgresRepository).insertRow(eq("myapp"), eq("users"), anyMap());
    }

    @Test
    void insertRowRejectsEmptyValues() {
        assertThatThrownBy(() -> service.insertRow("myapp", "users", Map.of()))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> service.insertRow("myapp", "users", null))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void insertRowRejectsReservedColumn() {
        assertThatThrownBy(() -> service.insertRow("myapp", "users", Map.of("__pg_ctid", "x")))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> service.insertRow("myapp", "users", Map.of("__ctid", "x")))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> service.insertRow("myapp", "users", Map.of("ctid", "x")))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> service.insertRow("myapp", "users", Map.of("_csrf", "x")))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> service.insertRow("myapp", "users", Map.of("__new_col", "x")))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> service.insertRow("myapp", "users", Map.of("__new_val", "x")))
                .isInstanceOf(NameNotAllowedException.class);
    }

    @Test
    void insertRowRejectsInvalidColumnName() {
        // uppercase is lowercased before validation, so use a truly invalid pattern
        assertThatThrownBy(() -> service.insertRow("myapp", "users", Map.of("my-col", "x")))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> service.insertRow("myapp", "users", Map.of("1col", "x")))
                .isInstanceOf(NameNotAllowedException.class);
    }

    // ── deleteRow ───────────────────────────────────────────────────

    @Test
    void deleteRowSucceeds() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);

        service.deleteRow("myapp", "users", "(0,1)");

        verify(postgresRepository).deleteRowByCtid("myapp", "users", "(0,1)");
    }

    @Test
    void deleteRowRejectsBlankCtid() {
        assertThatThrownBy(() -> service.deleteRow("myapp", "users", ""))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> service.deleteRow("myapp", "users", null))
                .isInstanceOf(NameNotAllowedException.class);
        assertThatThrownBy(() -> service.deleteRow("myapp", "users", "  "))
                .isInstanceOf(NameNotAllowedException.class);
    }

    // ── ensureTableExists ───────────────────────────────────────────

    @Test
    void ensureTableExistsSucceeds() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);

        service.ensureTableExists("myapp", "users");
    }

    @Test
    void ensureTableExistsThrowsWhenMissing() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "nope")).thenReturn(false);

        assertThatThrownBy(() -> service.ensureTableExists("myapp", "nope"))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    // ── writeAllRowsAsJson ──────────────────────────────────────────

    @Test
    void writeAllRowsAsJsonWritesArray() throws Exception {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        when(postgresRepository.getTableColumns("myapp", "users")).thenReturn(List.of("name"));
        when(postgresRepository.listRows("myapp", "users", 1000, 0))
                .thenReturn(List.of(Map.of("name", "alice"), Map.of("name", "bob")));

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        service.writeAllRowsAsJson("myapp", "users", out);
        String json = out.toString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(json).startsWith("[");
        assertThat(json).endsWith("]");
        assertThat(json).contains("\"alice\"");
        assertThat(json).contains("\"bob\"");
    }

    @Test
    void writeAllRowsAsJsonEmptyTableWritesEmptyArray() throws Exception {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        when(postgresRepository.getTableColumns("myapp", "users")).thenReturn(List.of("name"));
        when(postgresRepository.listRows("myapp", "users", 1000, 0)).thenReturn(List.of());

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        service.writeAllRowsAsJson("myapp", "users", out);

        assertThat(out.toString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("[]");
    }

    @Test
    void getRowsOnEmptyTableReturnsEmptyPage() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        when(postgresRepository.countRows("myapp", "users")).thenReturn(0L);
        when(postgresRepository.getTableColumns("myapp", "users")).thenReturn(List.of("name"));
        when(postgresRepository.listRowsWithCtid("myapp", "users", 50, 0)).thenReturn(List.of());

        TableRowPage page = service.getRows("myapp", "users", 1);

        assertThat(page.totalCount()).isZero();
        assertThat(page.totalPages()).isZero();
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.rows()).isEmpty();
        assertThat(page.hasPrev()).isFalse();
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void createTableWithNullColumnsCreatesDefaultIdColumn() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(false);

        service.createTable("myapp", "users", null);

        verify(postgresRepository).createTable("myapp", "users", List.of());
    }

    @Test
    void insertRowWithBlankStringBecomesNull() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        when(postgresRepository.getTableColumns("myapp", "users")).thenReturn(List.of("name", "email"));

        service.insertRow("myapp", "users", java.util.Map.of("name", "alice", "email", "   "));

        verify(postgresRepository).insertRow(eq("myapp"), eq("users"), argThat(m -> m.get("email") == null && "alice".equals(m.get("name"))));
    }

    // ── exception wrapping ────────────────────────────────────────

    @Test
    void listTablesWrapsListTablesFailure() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.listTables("myapp")).thenThrow(new RuntimeException("boom"));
        assertThatThrownBy(() -> service.listTables("myapp"))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("Could not list tables");
    }

    @Test
    void listTablesWrapsDatabaseExistsFailure() {
        when(postgresRepository.databaseExists("myapp")).thenThrow(new RuntimeException("db down"));
        assertThatThrownBy(() -> service.listTables("myapp"))
                .isInstanceOf(ProvisioningException.class);
    }

    @Test
    void getRowsWrapsCountRowsFailure() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        when(postgresRepository.countRows("myapp", "users")).thenThrow(new RuntimeException("count boom"));
        assertThatThrownBy(() -> service.getRows("myapp", "users", 1))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("Could not count rows");
    }

    @Test
    void getRowsWrapsGetTableColumnsFailure() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        when(postgresRepository.countRows("myapp", "users")).thenReturn(10L);
        when(postgresRepository.getTableColumns("myapp", "users")).thenThrow(new RuntimeException("col boom"));
        assertThatThrownBy(() -> service.getRows("myapp", "users", 1))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("Could not read columns");
    }

    @Test
    void getRowsWrapsListRowsWithCtidFailure() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        when(postgresRepository.countRows("myapp", "users")).thenReturn(10L);
        when(postgresRepository.getTableColumns("myapp", "users")).thenReturn(List.of("name"));
        when(postgresRepository.listRowsWithCtid("myapp", "users", 50, 0)).thenThrow(new RuntimeException("row boom"));
        assertThatThrownBy(() -> service.getRows("myapp", "users", 1))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("Could not read rows");
    }

    @Test
    void getRowsOnMissingDatabaseThrows() {
        when(postgresRepository.databaseExists("nope")).thenReturn(false);
        assertThatThrownBy(() -> service.getRows("nope", "users", 1))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    @Test
    void getRowsWithNegativePageClampsToOne() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        when(postgresRepository.countRows("myapp", "users")).thenReturn(5L);
        when(postgresRepository.getTableColumns("myapp", "users")).thenReturn(List.of("name"));
        when(postgresRepository.listRowsWithCtid("myapp", "users", 50, 0))
                .thenReturn(List.of(Map.of("name", "a")));
        TableRowPage page = service.getRows("myapp", "users", -5);
        assertThat(page.page()).isEqualTo(1);
    }

    @Test
    void createTableWrapsFailure() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(false);
        doThrow(new RuntimeException("create boom")).when(postgresRepository).createTable("myapp", "users", List.of("name"));
        assertThatThrownBy(() -> service.createTable("myapp", "users", List.of("name")))
                .isInstanceOf(ProvisioningException.class)
                .hasMessageContaining("Could not create table");
    }

    @Test
    void createTableWithEmptyColumnsList() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(false);
        service.createTable("myapp", "users", List.of());
        verify(postgresRepository).createTable("myapp", "users", List.of());
    }

    @Test
    void dropTableWrapsFailure() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        doThrow(new RuntimeException("drop boom")).when(postgresRepository).dropTable("myapp", "users");
        assertThatThrownBy(() -> service.dropTable("myapp", "users"))
                .isInstanceOf(ProvisioningException.class);
    }

    @Test
    void truncateTableWrapsFailure() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        doThrow(new RuntimeException("trunc boom")).when(postgresRepository).truncateTable("myapp", "users");
        assertThatThrownBy(() -> service.truncateTable("myapp", "users"))
                .isInstanceOf(ProvisioningException.class);
    }

    @Test
    void insertRowWrapsGetTableColumnsFailure() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        when(postgresRepository.getTableColumns("myapp", "users")).thenThrow(new RuntimeException("col boom"));
        assertThatThrownBy(() -> service.insertRow("myapp", "users", Map.of("name", "alice")))
                .isInstanceOf(ProvisioningException.class);
    }

    @Test
    void insertRowWrapsInsertRowFailure() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        when(postgresRepository.getTableColumns("myapp", "users")).thenReturn(List.of("name"));
        doThrow(new RuntimeException("insert boom")).when(postgresRepository).insertRow(eq("myapp"), eq("users"), anyMap());
        assertThatThrownBy(() -> service.insertRow("myapp", "users", Map.of("name", "alice")))
                .isInstanceOf(ProvisioningException.class);
    }

    @Test
    void insertRowNormalizesUppercaseColumnName() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        when(postgresRepository.getTableColumns("myapp", "users")).thenReturn(List.of("name"));
        service.insertRow("myapp", "users", Map.of("EMAIL", "alice@example.com", "name", "alice"));
        verify(postgresRepository).executeInDatabase(eq("myapp"), contains("ADD COLUMN \"email\""));
        verify(postgresRepository).insertRow(eq("myapp"), eq("users"), argThat(m -> m.containsKey("email") && m.containsKey("name")));
    }

    @Test
    void deleteRowWrapsFailure() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        doThrow(new RuntimeException("delete boom")).when(postgresRepository).deleteRowByCtid("myapp", "users", "(0,1)");
        assertThatThrownBy(() -> service.deleteRow("myapp", "users", "(0,1)"))
                .isInstanceOf(ProvisioningException.class);
    }

    @Test
    void deleteRowOnMissingDatabaseThrows() {
        when(postgresRepository.databaseExists("nope")).thenReturn(false);
        assertThatThrownBy(() -> service.deleteRow("nope", "users", "(0,1)"))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    @Test
    void deleteRowOnMissingTableThrows() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "nope")).thenReturn(false);
        assertThatThrownBy(() -> service.deleteRow("myapp", "nope", "(0,1)"))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    @Test
    void ensureTableExistsOnMissingDatabaseThrows() {
        when(postgresRepository.databaseExists("nope")).thenReturn(false);
        assertThatThrownBy(() -> service.ensureTableExists("nope", "users"))
                .isInstanceOf(DatabaseNotFoundException.class);
    }

    @Test
    void writeAllRowsAsJsonWrapsGetTableColumnsFailure() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        when(postgresRepository.getTableColumns("myapp", "users")).thenThrow(new RuntimeException("col boom"));
        assertThatThrownBy(() -> service.writeAllRowsAsJson("myapp", "users", new java.io.ByteArrayOutputStream()))
                .isInstanceOf(ProvisioningException.class);
    }

    @Test
    void writeAllRowsAsJsonWrapsListRowsFailure() {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        when(postgresRepository.getTableColumns("myapp", "users")).thenReturn(List.of("name"));
        when(postgresRepository.listRows("myapp", "users", 1000, 0)).thenThrow(new RuntimeException("row boom"));
        assertThatThrownBy(() -> service.writeAllRowsAsJson("myapp", "users", new java.io.ByteArrayOutputStream()))
                .isInstanceOf(ProvisioningException.class);
    }

    @Test
    void writeAllRowsAsJsonHandlesPagination() throws Exception {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        when(postgresRepository.getTableColumns("myapp", "users")).thenReturn(List.of("name"));
        List<Map<String, Object>> firstBatch = new java.util.ArrayList<>();
        for (int i = 0; i < 1000; i++) firstBatch.add(Map.of("name", "user-" + i));
        when(postgresRepository.listRows("myapp", "users", 1000, 0)).thenReturn(firstBatch);
        when(postgresRepository.listRows("myapp", "users", 1000, 1000)).thenReturn(List.of(Map.of("name", "last")));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        service.writeAllRowsAsJson("myapp", "users", out);
        String json = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(json).startsWith("[");
        assertThat(json).endsWith("]");
        assertThat(json).contains("\"user-0\"");
        assertThat(json).contains("\"last\"");
    }

    @Test
    void writeAllRowsAsJsonHandlesSpecialCharsAndNulls() throws Exception {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        when(postgresRepository.getTableColumns("myapp", "users")).thenReturn(List.of("name", "note"));
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("name", "a\"b");
        row.put("note", null);
        when(postgresRepository.listRows("myapp", "users", 1000, 0)).thenReturn(List.of(row));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        service.writeAllRowsAsJson("myapp", "users", out);
        String json = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(json).contains("a\\\"b");
        assertThat(json).contains("\"note\":null");
    }

    @Test
    void writeAllRowsAsJsonHandlesNumbersAndBooleans() throws Exception {
        when(postgresRepository.databaseExists("myapp")).thenReturn(true);
        when(postgresRepository.tableExists("myapp", "users")).thenReturn(true);
        when(postgresRepository.getTableColumns("myapp", "users")).thenReturn(List.of("count", "active"));
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("count", 42);
        row.put("active", true);
        when(postgresRepository.listRows("myapp", "users", 1000, 0)).thenReturn(List.of(row));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        service.writeAllRowsAsJson("myapp", "users", out);
        String json = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(json).contains("\"count\":42");
        assertThat(json).contains("\"active\":true");
    }
}
