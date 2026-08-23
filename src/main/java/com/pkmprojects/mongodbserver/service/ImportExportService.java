package com.pkmprojects.mongodbserver.service;

import com.mongodb.MongoException;
import com.pkmprojects.mongodbserver.error.DatabaseNotFoundException;
import com.pkmprojects.mongodbserver.error.NameNotAllowedException;
import com.pkmprojects.mongodbserver.error.ProvisioningException;
import com.pkmprojects.mongodbserver.model.AuditEvent;
import com.pkmprojects.mongodbserver.model.AuditEventRecorded;
import com.pkmprojects.mongodbserver.repository.AuditLogRepository;
import com.pkmprojects.mongodbserver.repository.MongoDatabaseRepository;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bulk collection data movement:
 * <ul>
 * <li>export - streams the whole collection as a JSON array (extended JSON, so
 *     dates/ObjectIds/binary round-trip exactly) or as a flattened CSV for
 *     spreadsheets;</li>
 * <li>import - appends documents from an uploaded JSON (array or single object)
 *     or CSV file, auto-detected by content. CSV cells import as strings (empty
 *     cells become {@code null}); use JSON when exact types matter.</li>
 * </ul>
 * Imports are append-only (never destructive) and are recorded in the audit
 * trail. Both exports are read-only; imports require the collection to exist.
 */
@Service
public class ImportExportService {

    private static final Logger log = LoggerFactory.getLogger(ImportExportService.class);

    static final int INSERT_BATCH_SIZE = 1000;

    private static final JsonWriterSettings EXTENDED = JsonWriterSettings.builder()
            .outputMode(JsonMode.EXTENDED)
            .build();

    private static final byte[] CSV_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final MongoDatabaseRepository mongoDatabaseRepository;
    private final MongoNameValidator nameValidator;
    private final AuditLogRepository auditLogRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final DatabaseLockRegistry databaseLocks;
    private final Clock clock;

    public ImportExportService(MongoDatabaseRepository mongoDatabaseRepository,
                               MongoNameValidator nameValidator,
                               AuditLogRepository auditLogRepository,
                               ApplicationEventPublisher applicationEventPublisher,
                               DatabaseLockRegistry databaseLocks,
                               Clock clock) {
        this.mongoDatabaseRepository = mongoDatabaseRepository;
        this.nameValidator = nameValidator;
        this.auditLogRepository = auditLogRepository;
        this.applicationEventPublisher = applicationEventPublisher;
        this.databaseLocks = databaseLocks;
        this.clock = clock;
    }

    /**
     * Verifies the database and collection exist. The export controllers call
     * this <em>before</em> returning a streaming response so a missing
     * collection yields a normal 404 page instead of a truncated download.
     */
    public void requireCollection(String dbName, String collectionName) {
        nameValidator.validateDatabaseName(dbName);
        nameValidator.validateCollectionName(collectionName);
        requireDatabase(dbName);
        if (!mongoDatabaseRepository.collectionExists(dbName, collectionName)) {
            throw new DatabaseNotFoundException("Collection '" + collectionName + "' does not exist");
        }
    }

    /**
     * Streams every document of a collection as a JSON array to {@code out},
     * one document at a time (memory stays bounded). Documents use extended
     * JSON so an {@link #importDocuments} round-trip preserves types exactly.
     */
    public void writeAllDocumentsAsJson(String dbName, String collectionName, OutputStream out) {
        requireCollection(dbName, collectionName);
        try {
            out.write('[');
            boolean[] first = {true};
            mongoDatabaseRepository.streamDocuments(dbName, collectionName, doc -> {
                try {
                    if (!first[0]) {
                        out.write(COMMA);
                    }
                    first[0] = false;
                    out.write(doc.toJson(EXTENDED).getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new TransferWriteException(e);
                }
            });
            out.write(']');
        } catch (TransferWriteException e) {
            log.error("Failed to write JSON export of {}.{}", dbName, collectionName, e);
            throw new ProvisioningException("Could not export collection '" + collectionName + "'", e);
        } catch (MongoException e) {
            log.error("Failed to export {}.{}", dbName, collectionName, e);
            throw new ProvisioningException("Could not export collection '" + collectionName + "'", e);
        } catch (IOException e) {
            log.error("Failed to write JSON export of {}.{}", dbName, collectionName, e);
            throw new ProvisioningException("Could not export collection '" + collectionName + "'", e);
        }
    }

    /**
     * Streams every document of a collection as a flattened CSV (RFC 4180, UTF-8
     * with BOM for spreadsheet compatibility). Columns are the union of top-level
     * fields, ordered by first appearance. Nested values are rendered as compact
     * extended JSON; CSV is for humans/spreadsheets, not exact round-trips. Two
     * passes over the collection: one to discover columns, one to write rows.
     */
    public void writeAllDocumentsAsCsv(String dbName, String collectionName, OutputStream out) {
        requireCollection(dbName, collectionName);
        try {
            LinkedHashSet<String> columns = new LinkedHashSet<>();
            mongoDatabaseRepository.streamDocuments(dbName, collectionName, doc -> columns.addAll(doc.keySet()));

            out.write(CSV_BOM);
            out.write(csvRow(List.copyOf(columns)).getBytes(StandardCharsets.UTF_8));
            mongoDatabaseRepository.streamDocuments(dbName, collectionName, doc -> {
                try {
                    List<String> cells = new ArrayList<>(columns.size());
                    for (String column : columns) {
                        cells.add(renderCell(doc.get(column)));
                    }
                    out.write(csvRow(cells).getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new TransferWriteException(e);
                }
            });
        } catch (TransferWriteException e) {
            log.error("Failed to write CSV export of {}.{}", dbName, collectionName, e);
            throw new ProvisioningException("Could not export collection '" + collectionName + "'", e);
        } catch (MongoException e) {
            log.error("Failed to export {}.{}", dbName, collectionName, e);
            throw new ProvisioningException("Could not export collection '" + collectionName + "'", e);
        } catch (IOException e) {
            log.error("Failed to write CSV export of {}.{}", dbName, collectionName, e);
            throw new ProvisioningException("Could not export collection '" + collectionName + "'", e);
        }
    }

    /**
     * Appends documents from an uploaded file to an existing collection.
     * JSON (an array of objects or a single object) and CSV are auto-detected
     * from content. Documents are inserted in batches.
     *
     * @throws NameNotAllowedException when the file is empty, malformed, or CSV
     *                                 headers are unusable
     * @throws DatabaseNotFoundException when the database or collection does not
     *                                 exist
     */
    public ImportResult importDocuments(String dbName, String collectionName, byte[] content) {
        // Parsing is a pure function of the file content - run it before taking
        // the per-database lock so a malformed upload never blocks other admins.
        List<Document> documents = parse(content);

        // Existence check plus batched inserts are a check-then-act span; hold
        // the same per-database lock as the provisioning lifecycle so a
        // concurrent delete cannot drop the collection (or database) between
        // the check and the inserts.
        return databaseLocks.withLock(dbName, () -> {
            requireCollection(dbName, collectionName);
            try {
                for (int i = 0; i < documents.size(); i += INSERT_BATCH_SIZE) {
                    mongoDatabaseRepository.insertDocuments(dbName, collectionName,
                            documents.subList(i, Math.min(i + INSERT_BATCH_SIZE, documents.size())));
                }
                audit(AuditEvent.IMPORT, dbName, collectionName, clock.instant());
            } catch (MongoException e) {
                log.error("Failed to import into {}.{}", dbName, collectionName, e);
                throw new ProvisioningException("Could not import into collection '" + collectionName + "'", e);
            }
            log.info("Imported {} document(s) into {}.{}", documents.size(), dbName, collectionName);
            return new ImportResult(dbName, collectionName, documents.size());
        });
    }

    /**
     * Parses the uploaded bytes into documents, auto-detecting JSON vs CSV from
     * content. All structural problems surface here, before anything is written.
     */
    private List<Document> parse(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        String trimmed = text.stripLeading();
        if (trimmed.isEmpty()) {
            throw new NameNotAllowedException("The uploaded file is empty");
        }
        try {
            if (trimmed.startsWith("[")) {
                return parseJsonArray(text);
            }
            if (trimmed.startsWith("{")) {
                return List.of(Document.parse(text));
            }
            return parseCsv(text);
        } catch (NameNotAllowedException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new NameNotAllowedException("Could not parse the uploaded file: " + e.getMessage());
        }
    }

    /**
     * Parses a top-level JSON array of objects. The array is wrapped in a
     * document and read back through {@link Document#parse}, which is the
     * driver's extended-JSON parser (handles {@code $oid}, {@code $date}, ...).
     */
    private List<Document> parseJsonArray(String json) {
        Document wrapped = Document.parse("{\"documents\":" + json + "}");
        List<Document> documents = wrapped.getList("documents", Document.class);
        return documents == null ? List.of() : documents;
    }

    /**
     * Parses RFC 4180 CSV into documents. The first row is the header and defines
     * the fields; every cell imports as a string (empty cells become {@code null}).
     * Header names must be valid BSON field names: non-empty, no leading '$',
     * no '.' (which would create nested documents), and unique.
     */
    private List<Document> parseCsv(String text) {
        List<List<String>> rows = parseCsvRows(text);
        if (rows.isEmpty()) {
            throw new NameNotAllowedException("The CSV file has no header row");
        }
        List<String> headers = rows.get(0);
        if (headers.isEmpty() || headers.stream().anyMatch(String::isBlank)) {
            throw new NameNotAllowedException("The CSV header row must name every column");
        }
        Set<String> seen = new HashSet<>();
        for (String header : headers) {
            if (header.startsWith("$")) {
                throw new NameNotAllowedException("CSV column '" + header + "' must not start with '$'");
            }
            if (header.contains(".")) {
                throw new NameNotAllowedException("CSV column '" + header + "' must not contain '.'");
            }
            if (!seen.add(header)) {
                throw new NameNotAllowedException("CSV column '" + header + "' appears more than once");
            }
        }

        List<Document> documents = new ArrayList<>();
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            List<String> cells = rows.get(rowIndex);
            Document document = new Document();
            boolean hasValue = false;
            for (int columnIndex = 0; columnIndex < headers.size(); columnIndex++) {
                String cell = columnIndex < cells.size() ? cells.get(columnIndex) : "";
                if (!cell.isEmpty()) {
                    hasValue = true;
                }
                document.put(headers.get(columnIndex), cell.isEmpty() ? null : cell);
            }
            if (hasValue) {
                documents.add(document);
            }
        }
        return documents;
    }

    /**
     * Minimal RFC 4180 parser: handles quoted fields, doubled quotes, commas and
     * newlines inside quotes, and CRLF/CR/LF line endings.
     */
    private static List<List<String>> parseCsvRows(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else if (c == '\r') {
                // CRLF is terminated by the '\n' branch; a lone CR also ends a row.
                if (i + 1 >= text.length() || text.charAt(i + 1) != '\n') {
                    row.add(field.toString());
                    field.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                }
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    /**
     * Renders one top-level field value for a CSV cell.
     */
    private static String renderCell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Document document) {
            return document.toJson(EXTENDED);
        }
        if (value instanceof List<?> list) {
            return "[" + list.stream().map(ImportExportService::renderCell).collect(Collectors.joining(",")) + "]";
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant().toString();
        }
        if (value instanceof ObjectId objectId) {
            return objectId.toHexString();
        }
        return value.toString();
    }

    /**
     * Builds one CSV line from field values, quoting fields that need it.
     */
    private static String csvRow(List<String> fields) {
        return fields.stream().map(ImportExportService::csvField).collect(Collectors.joining(",")) + "\r\n";
    }

    private static String csvField(String value) {
        if (startsWithFormulaChar(value)) {
            value = "'" + value;
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * Cells starting with these characters can be interpreted as spreadsheet
     * formulas (CWE-1236). A leading {@code '} neutralizes them. A leading
     * {@code -} is neutralized too, unless the whole cell is a plain number
     * (e.g. "-5"), so common negative values keep working while expressions
     * like "-2+3" or "--cmd" are still defused.
     */
    private static boolean startsWithFormulaChar(String value) {
        if (value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '@' || first == '\t' || first == '\r') {
            return true;
        }
        return first == '-' && !isPlainNumber(value);
    }

    private static boolean isPlainNumber(String value) {
        return value.matches("[+-]?\\d+(\\.\\d+)?([eE][+-]?\\d+)?");
    }

    private void requireDatabase(String dbName) {
        if (!mongoDatabaseRepository.databaseExists(dbName)) {
            throw new DatabaseNotFoundException("Database '" + dbName + "' does not exist");
        }
    }

    private void audit(String eventType, String dbName, String collectionName, Instant performedAt) {
        AuditEvent event = new AuditEvent(eventType, dbName, collectionName, currentUsername(), performedAt);
        auditLogRepository.save(event);
        applicationEventPublisher.publishEvent(new AuditEventRecorded(event));
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getName() != null ? authentication.getName() : "unknown";
    }

    /**
     * Summary of a completed import.
     */
    public record ImportResult(String dbName, String collectionName, int documentsImported) {
    }

    /**
     * Wraps an {@link IOException} escaping the streaming document consumers,
     * where checked exceptions are not allowed.
     */
    private static class TransferWriteException extends RuntimeException {
        TransferWriteException(IOException cause) {
            super(cause);
        }
    }

    private static final byte[] COMMA = ",".getBytes(StandardCharsets.UTF_8);
}
