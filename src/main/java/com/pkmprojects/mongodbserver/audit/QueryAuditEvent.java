package com.pkmprojects.mongodbserver.audit;

import com.pkmprojects.mongodbserver.model.DatabaseEngineType;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One normalized, redacted database-activity record in {@code query_audit}.
 *
 * <p>Every optional field is nullable and omitted when the observation source
 * cannot truthfully provide it. Query/command content is normalized/redacted
 * only (see {@link QueryShapeRedactor}); raw text is never stored.</p>
 */
@Document(collection = "query_audit")
public class QueryAuditEvent {

    @Id
    private String eventId;

    private int schemaVersion = QueryShapeRedactor.SCHEMA_VERSION;

    private Instant observedAt;

    private DatabaseEngineType engine;

    private String database;

    private String managedDatabaseId;

    private String provisionedUser;

    private String sourceIp;

    private Integer sourcePort;

    private String operationClass;

    private String commandType;

    private String normalizedShape;

    private String shapeHash;

    private Long durationMs;

    private Long rowsAffected;

    private Long rowsReturned;

    private Boolean success;

    private String errorCode;

    private String errorClass;

    private ObservationSource observationSource;

    private QueryAttribution attribution;

    private AuditConfidence auditConfidence;

    private String sessionId;

    private String connectionId;

    private Long sourceSequence;

    public QueryAuditEvent() {
    }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public Instant getObservedAt() { return observedAt; }
    public void setObservedAt(Instant observedAt) { this.observedAt = observedAt; }
    public DatabaseEngineType getEngine() { return engine; }
    public void setEngine(DatabaseEngineType engine) { this.engine = engine; }
    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }
    public String getManagedDatabaseId() { return managedDatabaseId; }
    public void setManagedDatabaseId(String managedDatabaseId) { this.managedDatabaseId = managedDatabaseId; }
    public String getProvisionedUser() { return provisionedUser; }
    public void setProvisionedUser(String provisionedUser) { this.provisionedUser = provisionedUser; }
    public String getSourceIp() { return sourceIp; }
    public void setSourceIp(String sourceIp) { this.sourceIp = sourceIp; }
    public Integer getSourcePort() { return sourcePort; }
    public void setSourcePort(Integer sourcePort) { this.sourcePort = sourcePort; }
    public String getOperationClass() { return operationClass; }
    public void setOperationClass(String operationClass) { this.operationClass = operationClass; }
    public String getCommandType() { return commandType; }
    public void setCommandType(String commandType) { this.commandType = commandType; }
    public String getNormalizedShape() { return normalizedShape; }
    public void setNormalizedShape(String normalizedShape) { this.normalizedShape = normalizedShape; }
    public String getShapeHash() { return shapeHash; }
    public void setShapeHash(String shapeHash) { this.shapeHash = shapeHash; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Long getRowsAffected() { return rowsAffected; }
    public void setRowsAffected(Long rowsAffected) { this.rowsAffected = rowsAffected; }
    public Long getRowsReturned() { return rowsReturned; }
    public void setRowsReturned(Long rowsReturned) { this.rowsReturned = rowsReturned; }
    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorClass() { return errorClass; }
    public void setErrorClass(String errorClass) { this.errorClass = errorClass; }
    public ObservationSource getObservationSource() { return observationSource; }
    public void setObservationSource(ObservationSource observationSource) { this.observationSource = observationSource; }
    public QueryAttribution getAttribution() { return attribution; }
    public void setAttribution(QueryAttribution attribution) { this.attribution = attribution; }
    public AuditConfidence getAuditConfidence() { return auditConfidence; }
    public void setAuditConfidence(AuditConfidence auditConfidence) { this.auditConfidence = auditConfidence; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }
    public Long getSourceSequence() { return sourceSequence; }
    public void setSourceSequence(Long sourceSequence) { this.sourceSequence = sourceSequence; }
}
